#!/usr/bin/env python3
"""Link only Legado's Linux C bridge against authenticated released pure V8 SDKs.

Uses the SDK's Chromium libc++ headers and runtime, a SHA-pinned Chromium
Clang/LLD package, and the matching SHA-pinned Debian bullseye sysroot. Never
builds V8, registers a Flutter hook, signs or publishes an artifact.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import sys
import subprocess
import tarfile
import tempfile
import urllib.request

import prebuilt
import toolchains
import link_sdk

HERE = Path(__file__).resolve().parent
PACKAGE = link_sdk.PACKAGE
PROFILES = {'linux-x64': ('x86_64-linux-gnu', 62, 'amd64'),
            'linux-arm64': ('aarch64-linux-gnu', 183, 'arm64')}


def run(command):
    return subprocess.run([str(x) for x in command], check=True, text=True,
                          capture_output=True).stdout.strip()


def tree_inventory(root):
    """Fingerprint regular files and link text without following links."""
    files = {}
    for path in sorted(root.rglob('*')):
        if path == root / '.sysroot-receipt.json':
            continue
        name = path.relative_to(root).as_posix()
        if path.is_symlink():
            destination = path.resolve()
            if not destination.is_relative_to(root.resolve()):
                raise ValueError('Sysroot symlink escapes root')
            files[name] = {'link': os.readlink(path)}
        elif path.is_file():
            files[name] = {'sha256': prebuilt.sha(path), 'size': path.stat().st_size}
        elif not path.is_dir():
            raise ValueError('Sysroot contains special file')
    return files


def inventory_digest(files):
    return hashlib.sha256(json.dumps(files, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def install_sysroot(target, cache, pins):
    if target not in PROFILES:
        raise ValueError('Unsupported Linux target')
    pin = pins['sysroots'][target]
    digest = prebuilt._digest(pin['sha256'])
    if pin['url'] != 'https://commondatastorage.googleapis.com/chrome-linux-sysroot/' + digest:
        raise ValueError('Sysroot URL must name its fixed official content hash')
    prebuilt._positive_size(pin['size'])
    prebuilt._digest(pin['filesSha256'])
    cache = Path(cache)
    if cache.is_symlink():
        raise ValueError('Sysroot cache must not be a symlink')
    cache.mkdir(parents=True, exist_ok=True)
    destination = cache / (target + '-' + digest)

    def verify(root):
        if root.is_symlink() or (root / '.sysroot-receipt.json').is_symlink():
            raise ValueError('Sysroot root/receipt must not be symlinks')
        receipt = json.loads((root / '.sysroot-receipt.json').read_text())
        if receipt['pin'] != pin or inventory_digest(tree_inventory(root)) != pin['filesSha256']:
            raise ValueError('Sysroot cache differs from pinned authenticated archive')
        return root

    if destination.exists():
        return verify(destination)
    lock = cache / ('.' + target + '-' + digest + '.lock')
    try:
        lock.mkdir()
    except FileExistsError as error:
        raise ValueError('Sysroot installation already locked') from error
    try:
        with tempfile.TemporaryDirectory(prefix='.sysroot-', dir=cache) as directory:
            work = Path(directory)
            archive = work / 'sysroot.tar.xz'
            with urllib.request.urlopen(pin['url'], timeout=60) as response, archive.open('wb') as output:
                if response.geturl() != pin['url']:
                    raise ValueError('Unexpected sysroot download redirect')
                size = 0
                while data := response.read(1024 * 1024):
                    size += len(data)
                    if size > pin['size']:
                        raise ValueError('Sysroot archive exceeds fixed size')
                    output.write(data)
            if size != pin['size'] or prebuilt.sha(archive) != digest:
                raise ValueError('Sysroot archive SHA256/size mismatch')
            incoming = work / 'incoming'
            incoming.mkdir()
            with tarfile.open(archive, 'r:xz') as source:
                members = source.getmembers()
                if len(members) > 50000 or sum(m.size for m in members) > 1024**3:
                    raise ValueError('Sysroot archive unpacked size exceeds limit')
                # Official sysroots intentionally contain relative symlinks. The
                # data filter confines their resolved paths to this fresh root.
                source.extractall(incoming, filter='data')
            (incoming / '.sysroot-receipt.json').write_text(json.dumps({'pin': pin}, sort_keys=True))
            verify(incoming)
            os.replace(incoming, destination)
        return destination
    finally:
        lock.rmdir()


def contract(root, target, pins):
    if target not in PROFILES:
        raise ValueError('Unsupported Linux target')
    root = Path(root)
    manifest = json.loads((root / 'manifest.json').read_text())
    prebuilt.validate_sdk(root, manifest, pins, pins, target)
    linking = json.loads((root / target / 'linking.json').read_text())
    triple, _, architecture = PROFILES[target]
    expected = {
        'schemaVersion': 1, 'compilerStyle': 'clang++',
        'includeDirs': ['include', 'include/c++/config', 'include/c++/v1', 'include/c++abi'],
        'compileOptions': ['-std=c++20', '-nostdinc++', '-fPIC', '-fno-rtti', '-fno-exceptions',
                           '--target=' + triple, '-fcomplete-member-pointers', '-pthread'],
        'libraries': ['lib/libv8_monolith.a', 'lib/libv8_rust_runtime.a',
                      'lib/libv8_cxx_runtime.a', 'lib/libclang_rt.builtins.a'],
        'linkOptions': ['-nostdlib++', '--target=' + triple, '-fuse-ld=lld'],
        'systemLibraries': ['dl', 'rt', 'pthread', 'm'],
        'cxxRuntime': 'pinned Chromium libc++ (__Cr ABI)', 'crt': 'system glibc',
        'staticLibraryGrouping': 'rescan',
        'sysrootRequirement': {'kind': 'chromium-linux-sysroot', 'architecture': architecture,
            'distribution': 'debian-bullseye', 'sourceRevision': pins['v8']['revision']},
        'featureProfile': {'internationalization': True, 'temporal': True, 'icuData': 'embedded',
            'jit': 'upstream-default', 'webAssembly': 'upstream-default', 'experimentalRuntimeFlags': []}}
    if any(type(linking.get(k)) is not type(v) or linking.get(k) != v for k, v in expected.items()):
        raise ValueError('Linux SDK linking/feature contract differs')
    indexed = {item['path'] for item in manifest['targets'][target]['files']}
    for define in linking['defines']:
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(?:=[A-Za-z0-9_]+)?', define):
            raise ValueError('Invalid Linux SDK define')
    for relative in linking['libraries']:
        library = root / target / relative
        with library.open('rb') as archive:
            magic = archive.read(8)
        if target + '/' + relative not in indexed or magic != b'!<arch>\n':
            raise ValueError('Linux library must be an indexed self-contained archive')
    for relative in linking['includeDirs']:
        if not any(name.startswith(target + '/' + relative + '/') for name in indexed):
            raise ValueError('Linux include directory is not indexed')
    return manifest, linking


def command(root, target, linking, compiler_root, sysroot, output):
    platform_root = Path(root) / target
    args = [compiler_root / 'bin/clang++', *linking['compileOptions'], '--sysroot=' + str(sysroot),
            '-Oz', '-ffunction-sections', '-fdata-sections', '-fvisibility=hidden',
            '-fvisibility-inlines-hidden']
    args += ['-I' + str(platform_root / relative) for relative in linking['includeDirs']]
    args += ['-D' + define for define in linking['defines']]
    args += [PACKAGE / 'src/source_v8.cpp', '-Wl,--start-group']
    args += [platform_root / relative for relative in linking['libraries']]
    args += ['-Wl,--end-group', *linking['linkOptions'], '-nodefaultlibs', '-lc',
             '--ld-path=' + str(compiler_root / 'bin/ld.lld'), '-shared', '-Wl,--no-undefined',
             '-Wl,--exclude-libs,ALL', '-Wl,--gc-sections', '-Wl,-O2', '-Wl,--icf=all',
             '-Wl,--version-script=' + str(PACKAGE / 'src/android_exports.map'),
             '-Wl,-soname,libsource_v8.so']
    # Chromium's Linux Clang driver normally adds the target GCC unwind runtime.
    # Use the authenticated sysroot's exact library, never host GCC discovery.
    triple = PROFILES[target][0]
    unwind = Path(sysroot) / 'lib' / triple / 'libgcc_s.so.1'
    if not unwind.is_file() or not unwind.resolve().is_relative_to(Path(sysroot).resolve()):
        raise ValueError('Pinned sysroot unwind runtime missing')
    args += [unwind]
    args += ['-l' + library for library in linking['systemLibraries']]
    return args + ['-o', output]


def inspect_elf(data, target):
    if target not in PROFILES:
        raise ValueError('Unsupported Linux target')
    if (len(data) < 64 or data[:6] != b'\x7fELF\x02\x01' or
            struct.unpack_from('<HH', data, 16) != (3, PROFILES[target][1])):
        raise ValueError('Expected target Linux ELF64 shared library')
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    if size < 56 or not count or offset + size * count > len(data):
        raise ValueError('Invalid ELF program headers')
    if not any(struct.unpack_from('<I', data, offset + i * size)[0] == 1 for i in range(count)):
        raise ValueError('ELF shared library has no LOAD segments')
    return {'format': 'elf64', 'machine': PROFILES[target][1]}


def build(output, targets, *, sdk_cache=None, compiler_cache=None, sysroot_cache=None):
    output = Path(output).resolve()
    if output.exists():
        raise ValueError('Linux output must be a new directory')
    if not targets or len(set(targets)) != len(targets) or set(targets) - PROFILES.keys():
        raise ValueError('Explicit unique Linux targets required')
    pins = json.loads((HERE / 'linux-pins.json').read_text())
    release = json.loads((HERE / 'linux-release-pin.json').read_text())
    compiler = toolchains.install(compiler_cache)
    identity = link_sdk.compiler_identity(run([compiler / 'bin/clang++', '--version']))
    source_digest = link_sdk.source_digest()
    inputs = {name: prebuilt.sha(HERE / name) for name in
              ('linux_sdk.py', 'linux-pins.json', 'linux-release-pin.json')}
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.linux-bridge-', dir=output.parent) as temporary:
        work = Path(temporary)
        evidence = {}
        for target in targets:
            root = prebuilt.install(release, target, local_pins=pins, cache_root=sdk_cache)
            manifest, linking = contract(root, target, pins)
            if identity != link_sdk.compiler_identity(manifest['targets'][target]['toolchain']['clang']):
                raise ValueError('Linux bridge compiler differs from released SDK Clang')
            sysroot = install_sysroot(target, sysroot_cache or PACKAGE / '.cache/linux-sysroots', pins)
            folder = work / target
            folder.mkdir()
            binary = folder / 'libsource_v8.so'
            args = command(root, target, linking, compiler, sysroot, binary)
            run(args)
            inspection = inspect_elf(binary.read_bytes(), target)
            exported = run([compiler / 'bin/llvm-readelf', '--dyn-syms', '--wide', binary])
            defined = {parts[7] for line in exported.splitlines() if len(parts := line.split()) >= 8
                       and parts[0].rstrip(':').isdigit() and parts[6] != 'UND'
                       and parts[4] in ('GLOBAL', 'WEAK')}
            if defined != link_sdk.EXPORTS:
                raise ValueError('Linux bridge exports differ from the ten sv8 ABI symbols')
            dynamic = run([compiler / 'bin/llvm-readelf', '--dynamic', '--version-info', binary])
            dependencies = re.findall(r'Shared library: \[([^\]]+)\]', dynamic)
            allowed = {'libc.so.6', 'libm.so.6', 'libdl.so.2', 'libpthread.so.0', 'librt.so.1',
                       'ld-linux-x86-64.so.2', 'ld-linux-aarch64.so.1', 'libgcc_s.so.1'}
            if not dependencies or set(dependencies) - allowed:
                raise ValueError('Linux bridge has an unexpected dynamic dependency')
            glibc = {tuple(map(int, item.split('.'))) for item in re.findall(r'GLIBC_(\d+\.\d+)', dynamic)}
            if not glibc or max(glibc) > (2, 31):
                raise ValueError('Linux bridge exceeds pinned glibc 2.31 baseline')
            contract(root, target, pins)
            install_sysroot(target, sysroot.parent, pins)
            shutil.copytree(root / 'licenses', work / 'licenses' / target)
            evidence[target] = {'librarySha256': prebuilt.sha(binary), 'sdkManifestSha256': prebuilt.sha(root / 'manifest.json'),
                'sysrootSha256': pins['sysroots'][target]['sha256'], 'inspection': inspection,
                'command': [str(x) for x in args], 'dependencies': dependencies,
                'maxGlibcRequired': '.'.join(map(str, max(glibc))),
                'runtimeTested': False, 'sourceCompatibilityTested': False}
        if source_digest != link_sdk.source_digest() or any(prebuilt.sha(HERE / name) != value for name, value in inputs.items()):
            raise ValueError('Linux bridge sources/pins changed while building')
        (work / 'provenance.json').write_text(json.dumps({'schemaVersion': 1,
            'bridgeSourceSha256': source_digest, 'inputsSha256': inputs,
            'releaseManifestSha256': release['releaseManifestSha256'],
            'v8BuiltLocally': False, 'flutterHookEnabled': False, 'targets': evidence}, indent=2) + '\n')
        shutil.move(str(work), output)
    return output



def _native_probe(package, target):
    """Explicit same-architecture Linux runtime probe; no emulation claim."""
    import ctypes
    import threading
    import time
    host = {'x86_64': 'linux-x64', 'amd64': 'linux-x64',
            'aarch64': 'linux-arm64', 'arm64': 'linux-arm64'}.get(platform.machine().lower())
    if platform.system() != 'Linux' or target != host:
        raise ValueError('Native runtime requires matching Linux host architecture')
    package = Path(package).resolve()
    provenance = json.loads((package / 'provenance.json').read_text())
    binary = package / target / 'libsource_v8.so'
    if prebuilt.sha(binary) != provenance['targets'][target]['librarySha256']:
        raise ValueError('Runtime library differs from package evidence')
    inspect_elf(binary.read_bytes(), target)
    library = ctypes.CDLL(str(binary))
    signatures = {
        'sv8_create': ([ctypes.c_int, ctypes.c_int], ctypes.c_void_p),
        'sv8_start': ([ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p], None),
        'sv8_poll': ([ctypes.c_void_p], ctypes.c_void_p),
        'sv8_resolve': ([ctypes.c_void_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_int], None),
        'sv8_sync_poll': ([ctypes.c_void_p], ctypes.c_void_p),
        'sv8_sync_reply': ([ctypes.c_void_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_int], None),
        'sv8_cancel': ([ctypes.c_void_p], None), 'sv8_destroy': ([ctypes.c_void_p], None),
        'sv8_free': ([ctypes.c_void_p], None), 'sv8_version': ([], ctypes.c_char_p)}
    for name, (arguments, result) in signatures.items():
        function = getattr(library, name)
        function.argtypes, function.restype = arguments, result
    runtime = library.sv8_create(2000, 32)
    if not runtime:
        raise ValueError('Linux V8 runtime allocation failed')
    checks = []
    def start(script):
        library.sv8_start(runtime, script.encode(), b'{}', b'')
    def poll(function=library.sv8_poll):
        value = function(runtime)
        if not value:
            return None
        try:
            return json.loads(ctypes.string_at(value))
        finally:
            library.sv8_free(value)
    def expect(value):
        actual = poll()
        if actual != {'status': 'done', 'value': value}:
            raise ValueError('Linux runtime result differs: ' + repr(actual))
    try:
        start('JSON.stringify({answer:6*7,wasm:typeof WebAssembly,intl:typeof Intl,temporal:typeof Temporal})')
        result = poll()
        if not result or result.get('status') != 'done' or json.loads(result.get('value', '{}')) != {
                'answer': 42, 'wasm': 'object', 'intl': 'object', 'temporal': 'object'}:
            raise ValueError('Linux V8 feature profile differs: ' + repr(result))
        checks += ['javascript', 'wasm', 'intl', 'temporal']
        start('(globalThis.savedValue = 41, savedValue + 1)')
        expect(42)
        start('savedValue + 2')
        expect(43)
        checks.append('retained-session')
        start("__sourceHost('echo', []).then(value => value + 1)")
        response = poll()
        request = response.get('requests', [{}])[0] if response else None
        if not request or type(request.get('id')) is not int:
            raise ValueError('Async host request missing')
        library.sv8_resolve(runtime, request['id'], b'41', 0)
        expect(42)
        checks.append('async-host')
        worker = threading.Thread(target=start, args=("__sourceHostSync('echo', []) + 1",))
        worker.start()
        request = None
        for _ in range(100):
            response = poll(library.sv8_sync_poll)
            requests = response if isinstance(response, list) else []
            request = requests[0] if requests else None
            if request:
                break
            time.sleep(0.01)
        if not request or type(request.get('id')) is not int:
            library.sv8_cancel(runtime)
            worker.join(5)
            raise ValueError('Sync host request missing')
        library.sv8_sync_reply(runtime, request['id'], b'41', 0)
        worker.join(5)
        if worker.is_alive():
            raise ValueError('Sync host request did not finish')
        expect(42)
        checks.append('sync-host')
        worker = threading.Thread(target=start, args=('while (true) {}',))
        worker.start()
        time.sleep(0.05)
        library.sv8_cancel(runtime)
        worker.join(5)
        if worker.is_alive() or 'execution_timeout' not in json.dumps(poll()):
            raise ValueError('Linux runtime cancellation failed')
        start('savedValue + 3')
        expect(44)
        checks += ['cancellation', 'recovery-after-cancellation']
        receipt = {'schemaVersion': 1, 'target': target, 'hostMachine': platform.machine(),
                   'librarySha256': prebuilt.sha(binary), 'v8Version': library.sv8_version().decode(),
                   'checks': checks, 'applicationRuntimeAccepted': False,
                   'sourceCompatibilityTested': False}
        return receipt
    finally:
        library.sv8_destroy(runtime)



def run_native(package, target):
    """Accept a probe only after the child process completes normal teardown."""
    host = {'x86_64': 'linux-x64', 'amd64': 'linux-x64',
            'aarch64': 'linux-arm64', 'arm64': 'linux-arm64'}.get(platform.machine().lower())
    if platform.system() != 'Linux' or target != host:
        raise ValueError('Native runtime requires matching Linux host architecture')
    result = subprocess.run([sys.executable, str(HERE / 'linux_sdk.py'), '--probe-worker',
                             str(Path(package).resolve()), target], capture_output=True, text=True, timeout=30)
    if result.returncode != 0:
        raise ValueError('Linux probe failed, including process teardown (exit ' + str(result.returncode) + '): ' +
                         result.stderr[-8192:])
    receipt = json.loads(result.stdout)
    if receipt.get('target') != target or receipt.get('applicationRuntimeAccepted') is not False:
        raise ValueError('Linux runtime probe receipt differs')
    (Path(package) / (target + '-runtime.json')).write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def main():
    if len(sys.argv) == 4 and sys.argv[1] == '--probe-worker':
        print(json.dumps(_native_probe(sys.argv[2], sys.argv[3])), flush=True)
        return
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--target', action='append', required=True, choices=PROFILES)
    parser.add_argument('--sdk-cache', type=Path)
    parser.add_argument('--compiler-cache', type=Path)
    parser.add_argument('--sysroot-cache', type=Path)
    parser.add_argument('--verify-existing', action='store_true')
    parser.add_argument('--run-native', action='store_true')
    args = parser.parse_args()
    if not args.verify_existing:
        print(build(args.output, args.target, sdk_cache=args.sdk_cache, compiler_cache=args.compiler_cache,
                    sysroot_cache=args.sysroot_cache))
    elif not args.run_native:
        raise ValueError('--verify-existing requires --run-native')
    if args.run_native:
        if len(args.target) != 1:
            raise ValueError('--run-native requires exactly one host target')
        print(json.dumps(run_native(args.output, args.target[0]), indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        detail = getattr(error, 'stderr', None) or getattr(error, 'stdout', None) or str(error)
        raise SystemExit('Linux V8 bridge build failed: ' + detail)
