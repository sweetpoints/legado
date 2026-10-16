#!/usr/bin/env python3
"""Build only Legado's iOS bridge and package verified pure SDKs as XCFramework.

Experimental consumer tool, not a Flutter hook or application acceptance claim.
No V8 source builds, automatic signing, or remote release mutations.
"""
import argparse
import json
from pathlib import Path
import re
import plistlib
import shutil
import struct
import subprocess
import tempfile

import prebuilt
import toolchains
import link_sdk

HERE = Path(__file__).resolve().parent
PACKAGE = link_sdk.PACKAGE
PROFILES = {
    'ios-arm64': ('iphoneos', 'arm64-apple-ios15.0', 2, 'device'),
    'ios-simulator-arm64': ('iphonesimulator', 'arm64-apple-ios15.0-simulator', 7, 'simulator'),
}
PROBE = r'''#include "source_v8.h"
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <thread>
#include <chrono>
int request_id(const char *json) {
  const char *field = json ? std::strstr(json, "\"id\":") : nullptr;
  return field ? std::atoi(field + 5) : 0;
}
bool done(void *runtime, const char *expected) {
  char *value = sv8_poll(runtime);
  if (value) std::puts(value);
  bool ok = value && std::strstr(value, "\"status\":\"done\"") && std::strstr(value, expected);
  if (value) sv8_free(value);
  return ok;
}
int main() {
  void *runtime = sv8_create(2000, 32);
  if (!runtime) return 1;
  sv8_start(runtime, "JSON.stringify({answer:6*7,wasm:typeof WebAssembly,intl:typeof Intl,temporal:typeof Temporal})", "{}", "");
  char *value = sv8_poll(runtime);
  if (!value) return 2;
  std::puts(value);
  bool ok = std::strstr(value, "42") && std::strstr(value, "undefined") && std::strstr(value, "done");
  sv8_free(value);
  sv8_start(runtime, "(globalThis.savedValue = 41, savedValue + 1)", "{}", "");
  value = sv8_poll(runtime);
  if (value) std::puts(value);
  ok = ok && value && std::strstr(value, "42");
  if (value) sv8_free(value);
  sv8_start(runtime, "savedValue + 2", "{}", "");
  value = sv8_poll(runtime);
  if (value) std::puts(value);
  ok = ok && value && std::strstr(value, "43");
  if (value) sv8_free(value);
  sv8_start(runtime, "__sourceHost('echo', []).then(value => value + 1)", "{}", "");
  value = sv8_poll(runtime);
  int id = request_id(value);
  if (value) sv8_free(value);
  ok = ok && id > 0;
  if (id > 0) sv8_resolve(runtime, id, "41", 0);
  ok = done(runtime, "42") && ok;
  std::thread worker([&] { sv8_start(runtime, "__sourceHostSync('echo', []) + 1", "{}", ""); });
  id = 0;
  for (int attempt = 0; attempt < 100 && !id; ++attempt) {
    value = sv8_sync_poll(runtime);
    id = request_id(value);
    if (value) sv8_free(value);
    if (!id) std::this_thread::sleep_for(std::chrono::milliseconds(10));
  }
  if (id > 0) sv8_sync_reply(runtime, id, "41", 0);
  else sv8_cancel(runtime);
  worker.join();
  ok = done(runtime, "42") && id > 0 && ok;
  std::thread cancellable([&] { sv8_start(runtime, "while(true) {}", "{}", ""); });
  std::this_thread::sleep_for(std::chrono::milliseconds(50));
  sv8_cancel(runtime);
  cancellable.join();
  value = sv8_poll(runtime);
  ok = value && std::strstr(value, "execution_timeout") && ok;
  if (value) sv8_free(value);
  sv8_start(runtime, "savedValue + 3", "{}", "");
  ok = done(runtime, "44") && ok;
  sv8_destroy(runtime);
  return ok ? 0 : 3;
}
'''


def run(command):
    result = subprocess.run([str(x) for x in command], check=True, text=True, capture_output=True)
    return result.stdout.strip()


def xcrun(*args):
    return run(['xcrun', *args])


def contract(root, target, pins):
    if target not in PROFILES:
        raise ValueError('Unsupported iOS target')
    root = Path(root)
    manifest = json.loads((root / 'manifest.json').read_text())
    prebuilt.validate_sdk(root, manifest, pins, pins, target)
    sdk, triple, _, environment = PROFILES[target]
    linking = json.loads((root / target / 'linking.json').read_text())
    expected = {'schemaVersion': 1, 'kind': 'ios-static-sdk', 'target': target,
                'architecture': 'arm64', 'environment': environment, 'sdk': sdk,
                'minIOS': '15.0', 'clangTarget': triple, 'compileTimeJitless': True,
                'webAssembly': False, 'dynamicLoading': False, 'runtimeFlags': ['--jitless'],
                'compileOptions': ['-std=c++20'], 'includeDirs': ['include'],
                'systemLibraries': ['c++', 'objc'],
                'linkOptions': ['-framework', 'Security', '-framework', 'Foundation',
                                '-weak_framework', 'BrowserEngineKit']}
    if any(type(linking.get(k)) is not type(v) or linking.get(k) != v for k, v in expected.items()):
        raise ValueError('iOS SDK linking/jitless contract differs')
    for define in linking['defines']:
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(?:=[A-Za-z0-9_]+)?', define):
            raise ValueError('Invalid iOS SDK define')
    indexed = {item['path'] for item in manifest['targets'][target]['files']}
    libraries = linking['libraries']
    if not libraries or libraries[0] != 'lib/libv8_monolith.a':
        raise ValueError('iOS monolith must be first')
    for library in libraries:
        relative = str(prebuilt._relative(library))
        path = root / target / relative
        if target + '/' + relative not in indexed or path.suffix != '.a':
            raise ValueError('iOS library must be an indexed static archive')
        with path.open('rb') as stream:
            if stream.read(8) != b'!<arch>\n':
                raise ValueError('iOS SDK library must be a self-contained archive')
    return manifest, linking


def inspect_macho(data, target):
    if len(data) < 32 or data[:4] != b'\xcf\xfa\xed\xfe' or struct.unpack_from('<I', data, 4)[0] != 0x100000c:
        raise ValueError('Expected thin ARM64 Mach-O')
    count, size = struct.unpack_from('<II', data, 16)
    if 32 + size > len(data):
        raise ValueError('Truncated Mach-O load commands')
    offset = 32
    platform = None
    weak_bek = False
    for _ in range(count):
        if offset + 8 > 32 + size:
            raise ValueError('Truncated Mach-O load command')
        cmd, length = struct.unpack_from('<II', data, offset)
        if length < 8 or offset + length > 32 + size:
            raise ValueError('Invalid Mach-O load command')
        if cmd == 0x32:  # LC_BUILD_VERSION
            if length < 24:
                raise ValueError('Invalid platform load command')
            platform, minimum = struct.unpack_from('<II', data, offset + 8)
            if minimum != 15 << 16:
                raise ValueError('iOS minimum deployment target differs')
        if cmd in (0xc, 0x80000018):
            if length < 24:
                raise ValueError('Invalid dylib load command')
            start = struct.unpack_from('<I', data, offset + 8)[0]
            if start >= length:
                raise ValueError('Invalid dylib path offset')
            name = data[offset + start:offset + length].split(b'\x00')[0]
            if b'BrowserEngineKit.framework/BrowserEngineKit' in name:
                if cmd != 0x80000018:
                    raise ValueError('BrowserEngineKit must remain weakly linked')
                weak_bek = True
        offset += length
    if offset != 32 + size:
        raise ValueError('Mach-O load command count/size differs')
    if platform != PROFILES[target][2] or not weak_bek:
        raise ValueError('iOS platform or BrowserEngineKit weak dependency differs')
    return {'platform': platform, 'minIOS': '15.0', 'browserEngineKit': 'weak'}


def build(output, *, sdk_cache=None, compiler_cache=None, downloader=prebuilt.download):
    pins = json.loads((HERE / 'ios-pins.json').read_text())
    release = json.loads((HERE / 'ios-release-pin.json').read_text())
    output = Path(output).resolve()
    if output.exists():
        raise ValueError('iOS output must be a new directory')
    compiler_root = toolchains.install(compiler_cache)
    compiler = compiler_root / 'bin/clang++'
    identity = link_sdk.compiler_identity(run([compiler, '--version']))
    bridge_digest = link_sdk.source_digest()
    package_inputs = {name: prebuilt.sha(HERE / name) for name in
                      ('ios_sdk.py', 'ios-pins.json', 'ios-release-pin.json')}
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.ios-bridge-', dir=output.parent) as temporary:
        work = Path(temporary)
        evidence = {}
        for target, (sdk_name, triple, _, _) in PROFILES.items():
            root = prebuilt.install(release, target, local_pins=pins, cache_root=sdk_cache, downloader=downloader)
            manifest, linking = contract(root, target, pins)
            if link_sdk.compiler_identity(manifest['targets'][target]['toolchain']['clang']) != identity:
                raise ValueError('iOS bridge compiler differs from fixed SDK Clang')
            directory = work / target
            directory.mkdir()
            shutil.copytree(root / 'licenses', work / 'licenses' / target)
            include = directory / 'include'
            include.mkdir()
            shutil.copyfile(PACKAGE / 'src/source_v8.h', include / 'source_v8.h')
            sysroot = xcrun('--sdk', sdk_name, '--show-sdk-path')
            base = [compiler, '--target=' + triple, '-isysroot', sysroot, '-std=c++20']
            obj = directory / 'bridge.o'
            compile_command = base + ['-O2', '-fvisibility=hidden', '-DSV8_IOS_JITLESS=1',
                '-I' + str(root / target / 'include')] + ['-D' + item for item in linking['defines']]
            compile_command += ['-c', PACKAGE / 'src/source_v8.cpp', '-o', obj]
            run(compile_command)
            library = directory / 'libsource_v8.a'
            run(['xcrun', 'libtool', '-static', '-o', library, obj,
                 *[root / target / item for item in linking['libraries']]])
            symbols = run(['xcrun', 'nm', '-gU', library])
            actual = {line.split()[-1].removeprefix('_') for line in symbols.splitlines()
                      if line.strip() and line.split()[-1].startswith('_sv8_')}
            if actual != link_sdk.EXPORTS:
                raise ValueError('iOS static bridge ABI exports differ')
            probe_source = directory / 'probe.cpp'
            probe_source.write_text(PROBE)
            executable = directory / 'source_v8_probe'
            link_command = base + [probe_source, '-I' + str(include), library,
                *linking['linkOptions'], *['-l' + item for item in linking['systemLibraries']], '-o', executable]
            run(link_command)
            inspection = inspect_macho(executable.read_bytes(), target)
            # Re-check authenticated SDKs and bridge source after the complete link.
            contract(root, target, pins)
            if bridge_digest != link_sdk.source_digest():
                raise ValueError('Bridge changed while producing iOS package')
            evidence[target] = {'librarySha256': prebuilt.sha(library), 'probeSha256': prebuilt.sha(executable),
                'sdkManifestSha256': prebuilt.sha(root / 'manifest.json'), 'inspection': inspection,
                'compileCommand': [str(x) for x in compile_command], 'linkCommand': [str(x) for x in link_command],
                'runtimeTested': False, 'sourceCompatibilityTested': False}
        framework = work / 'SourceV8.xcframework'
        run(['xcodebuild', '-create-xcframework', *[arg for target in PROFILES for arg in
             ('-library', work / target / 'libsource_v8.a', '-headers', work / target / 'include')], '-output', framework])
        info = plistlib.loads((framework / 'Info.plist').read_bytes())
        libraries = info.get('AvailableLibraries', [])
        if {item['LibraryIdentifier'] for item in libraries} != {'ios-arm64', 'ios-arm64-simulator'}:
            raise ValueError('XCFramework platform slices differ')
        for item in libraries:
            target = 'ios-simulator-arm64' if item.get('SupportedPlatformVariant') == 'simulator' else 'ios-arm64'
            if item['SupportedPlatform'] != 'ios' or item['SupportedArchitectures'] != ['arm64']:
                raise ValueError('XCFramework target platform differs')
            packaged = framework / item['LibraryIdentifier'] / item['LibraryPath']
            if prebuilt.sha(packaged) != evidence[target]['librarySha256']:
                raise ValueError('XCFramework library changed while packaging')
        if any(prebuilt.sha(HERE / name) != digest for name, digest in package_inputs.items()):
            raise ValueError('iOS packager or pins changed while packaging')
        (work / 'provenance.json').write_text(json.dumps({'schemaVersion': 1,
            'bridgeSourceSha256': bridge_digest, 'releaseManifestSha256': release['releaseManifestSha256'],
            'v8BuiltLocally': False, 'flutterHookEnabled': False,
            'packagerSha256': package_inputs['ios_sdk.py'],
            'consumerPinsSha256': package_inputs['ios-pins.json'],
            'releasePinSha256': package_inputs['ios-release-pin.json'], 'targets': evidence}, indent=2) + '\n')
        shutil.move(str(work), output)
    return output



def run_simulator(package, device):
    if not re.fullmatch(r'[A-Fa-f0-9-]{36}', device):
        raise ValueError('Explicit simulator UUID required')
    package = Path(package).resolve()
    provenance = json.loads((package / 'provenance.json').read_text())
    target = 'ios-simulator-arm64'
    probe = package / target / 'source_v8_probe'
    entry = provenance['targets'][target]
    if prebuilt.sha(probe) != entry['probeSha256']:
        raise ValueError('Simulator probe checksum differs from package evidence')
    result = run(['xcrun', 'simctl', 'spawn', device, probe])
    lines = result.splitlines()
    expected = [42, 43, 42, 42, 44]
    if len(lines) != 6:
        raise ValueError('Simulator probe output is incomplete')
    first = json.loads(lines[0])
    value = json.loads(first.get('value', '{}'))
    if (first.get('status') != 'done' or value !=
            {'answer': 42, 'wasm': 'undefined', 'intl': 'object', 'temporal': 'object'}):
        raise ValueError('Simulator feature profile differs')
    for line, expected_value in zip(lines[1:], expected):
        if json.loads(line) != {'status': 'done', 'value': expected_value}:
            raise ValueError('Simulator host/session conformance differs')
    receipt = {'schemaVersion': 1, 'target': target, 'simulator': device,
               'probeSha256': entry['probeSha256'], 'processExitCode': 0,
               'checks': ['javascript', 'jitless-no-wasm', 'intl', 'temporal',
                          'retained-session', 'async-host', 'sync-host',
                          'cancellation', 'recovery-after-cancellation'],
               'applicationRuntimeAccepted': False, 'output': lines}
    (package / 'simulator-runtime.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--sdk-cache', type=Path)
    parser.add_argument('--compiler-cache', type=Path)
    parser.add_argument('--run-simulator', metavar='UUID')
    parser.add_argument('--verify-existing', action='store_true',
                        help='Only run the explicit simulator against an existing package')
    args = parser.parse_args()
    if args.verify_existing:
        if not args.run_simulator:
            raise ValueError('--verify-existing requires --run-simulator')
    else:
        build(args.output, sdk_cache=args.sdk_cache, compiler_cache=args.compiler_cache)
    if args.run_simulator:
        print(json.dumps(run_simulator(args.output, args.run_simulator), indent=2))
    else:
        print(args.output)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        detail = getattr(error, 'stderr', None) or getattr(error, 'stdout', None) or str(error)
        raise SystemExit('iOS V8 bridge packaging failed: ' + detail)
