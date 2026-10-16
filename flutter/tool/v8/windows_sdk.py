#!/usr/bin/env python3
"""Compile Legado's C bridge against reviewed pure Windows V8 SDKs.

No V8 checkout/build or automatic host SDK discovery. A separately reviewed,
SHA-authenticated complete Windows compiler/MSVC/SDK file inventory is required.
This standalone consumer does not register an untested Flutter platform target.
"""
import argparse
import ctypes
import json
import os
import platform
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile

import prebuilt
import link_sdk

HERE = Path(__file__).resolve().parent
MACHINES = {'windows-x64': 0x8664, 'windows-arm64': 0xaa64}
# V8 explicitly dllexports this handler for generated-code unwind metadata.
# Preserve it instead of modifying the upstream static library.
UPSTREAM_EXPORTS = {'CrashForExceptionInNonABICompliantCodeRange'}
WINDOWS_EXPORTS = link_sdk.EXPORTS | UPSTREAM_EXPORTS


def inspect_pe(data, target):
    """Read PE32+ section/export directories without trusting external tools."""
    if target not in MACHINES:
        raise ValueError("Unsupported Windows target")

    def take(offset, size):
        if offset < 0 or size < 0 or offset + size > len(data):
            raise ValueError('Truncated PE image')
        return data[offset:offset + size]

    def unpack(fmt, offset):
        return struct.unpack(fmt, take(offset, struct.calcsize(fmt)))

    if take(0, 2) != b'MZ':
        raise ValueError('Expected DOS header')
    pe, = unpack('<I', 0x3c)
    if take(pe, 4) != b'PE\0\0':
        raise ValueError('Expected PE signature')
    machine, count = unpack('<HH', pe + 4)
    optional_size, flags = unpack('<HH', pe + 20)
    if machine != MACHINES[target] or not flags & 0x2000:
        raise ValueError('Expected target architecture DLL')
    optional = pe + 24
    if optional_size < 120 or unpack('<H', optional)[0] != 0x20b:
        raise ValueError('Expected PE32+ optional header')
    take(optional, optional_size)
    if unpack('<I', optional + 108)[0] < 1:
        raise ValueError('PE export directory absent')
    export_rva, export_size = unpack('<II', optional + 112)
    sections = []
    for index in range(count):
        section = optional + optional_size + 40 * index
        take(section, 40)
        virtual_size, rva, raw_size, raw = unpack('<IIII', section + 8)
        take(raw, raw_size)
        sections.append((rva, virtual_size, raw, raw_size))

    def address(rva, size):
        matches = [raw + rva - start for start, virtual, raw, length in sections
                   if start <= rva and rva + size <= start + min(virtual, length)]
        if len(matches) != 1:
            raise ValueError('PE RVA is unmapped or ambiguous')
        take(matches[0], size)
        return matches[0]

    directory = address(export_rva, export_size)
    if export_size < 40:
        raise ValueError('Truncated PE exports')
    functions, names, function_rva, names_rva, ordinals_rva = unpack('<IIIII', directory + 20)
    if functions != len(WINDOWS_EXPORTS) or names != functions:
        raise ValueError('PE ABI must contain only the reviewed named exports')
    function_offset = address(function_rva, functions * 4)
    names_offset = address(names_rva, names * 4)
    ordinals_offset = address(ordinals_rva, names * 2)
    exports = set()
    for index in range(names):
        name_rva, = unpack('<I', names_offset + index * 4)
        name_offset = address(name_rva, 1)
        label = bytearray()
        for delta in range(128):
            value = take(address(name_rva + delta, 1), 1)[0]
            if not value:
                break
            label.append(value)
        else:
            raise ValueError('Unterminated PE export name')
        try:
            name = label.decode('ascii')
        except UnicodeDecodeError as error:
            raise ValueError('Invalid PE export name') from error
        ordinal, = unpack('<H', ordinals_offset + index * 2)
        if ordinal >= functions:
            raise ValueError('Invalid PE export ordinal')
        function, = unpack('<I', function_offset + ordinal * 4)
        if not function or export_rva <= function < export_rva + export_size:
            raise ValueError('Null or forwarded PE ABI export')
        address(function, 1)
        if name in exports:
            raise ValueError('Duplicate PE export')
        exports.add(name)
    if exports != WINDOWS_EXPORTS:
        raise ValueError('Windows C ABI exports differ')
    return {'format': 'PE32+', 'machine': machine, 'exports': sorted(exports),
            'bridgeExports': sorted(link_sdk.EXPORTS), 'upstreamExports': sorted(UPSTREAM_EXPORTS)}


def verify_toolchain(root, lock_path, expected_sha, target):
    """Authenticate all files before executing even the compiler --version."""
    root, lock_path = Path(root).resolve(), Path(lock_path)
    if prebuilt.sha(lock_path) != prebuilt._digest(expected_sha):
        raise ValueError('Windows toolchain lock SHA256 differs')
    lock = json.loads(lock_path.read_text())
    if lock.get('schemaVersion') != 1 or lock.get('target') != target:
        raise ValueError('Windows toolchain lock target/schema differs')
    if not all(isinstance(lock.get(k), str) and lock[k] for k in
               ('clangVersion', 'msvcVersion', 'windowsSdkVersion')):
        raise ValueError('Explicit fixed toolchain versions required')
    for key in ('msvcVersion', 'windowsSdkVersion'):
        if not re.fullmatch(r'[0-9]+(?:\.[0-9]+){2,3}', lock[key]):
            raise ValueError('Fixed numeric MSVC/Windows SDK versions required')
    files = lock.get('files')
    if not isinstance(files, dict) or not files:
        raise ValueError('Complete Windows toolchain file inventory required')
    actual = set()
    for path in root.rglob('*'):
        if path.is_symlink():
            raise ValueError('Toolchain symlinks forbidden')
        if path.is_file():
            relative = path.relative_to(root).as_posix()
            actual.add(relative)
            if relative not in files or prebuilt.sha(path) != prebuilt._digest(files[relative]):
                raise ValueError('Windows toolchain file hash differs: ' + relative)
    if actual != set(files):
        raise ValueError('Windows toolchain inventory differs')

    def selected(key, directory=False):
        relative = str(prebuilt._relative(lock.get(key)))
        path = root / relative
        if not (path.is_dir() if directory else relative in files):
            raise ValueError('Missing pinned toolchain input: ' + key)
        return path

    compiler = selected('compiler')
    if compiler.name.lower() != 'clang-cl.exe':
        raise ValueError('Pinned Chromium clang-cl.exe required')
    linker = selected('linker')
    if linker.name.lower() != 'lld-link.exe' or linker.parent != compiler.parent:
        raise ValueError('Pinned sibling lld-link.exe required')
    includes = lock.get('includeDirs')
    libs = lock.get('libraryDirs')
    if not isinstance(includes, list) or not includes or not isinstance(libs, list) or not libs:
        raise ValueError('Explicit Windows include/library directories required')
    for value in includes + libs:
        if not (root / str(prebuilt._relative(value))).is_dir():
            raise ValueError('Missing pinned Windows include/library directory')
    selected('msvcRoot', True)
    selected('windowsSdkRoot', True)
    return lock


def sdk_contract(root, target, pins):
    manifest = json.loads((root / 'manifest.json').read_text())
    prebuilt.validate_sdk(root, manifest, pins, pins, target)
    linking = json.loads((root / target / 'linking.json').read_text())
    if (linking.get('compilerStyle') != 'clang-cl' or
            linking.get('crt') != 'static MSVC /MT' or
            linking.get('cxxRuntime') != 'pinned Chromium libc++ (__Cr ABI)' or
            linking.get('linkOptions') != ['/machine:' + target.split('-')[1]]):
        raise ValueError('Windows SDK compiler/runtime/CPU contract differs')
    triple = {'windows-x64': 'x86_64', 'windows-arm64': 'aarch64'}[target] + '-pc-windows-msvc'
    if linking.get('compileOptions') != ['/std:c++20', '/MT', '/GR-', '/clang:-fno-exceptions',
                                         '-fuse-ld=lld', '--target=' + triple, '-fcomplete-member-pointers']:
        raise ValueError('Windows compiler ABI flags differ')
    if linking.get('featureProfile') != {'internationalization': True, 'temporal': True,
        'icuData': 'embedded', 'jit': 'upstream-default', 'webAssembly': 'upstream-default',
        'experimentalRuntimeFlags': []}:
        raise ValueError('Full Windows V8 feature profile differs')
    for key in ('includeDirs', 'libraries'):
        for value in link_sdk._strings(linking.get(key), key):
            prebuilt._relative(value)
    if linking['libraries'][0] != 'lib/v8_monolith.lib':
        raise ValueError('Windows monolith must be first')
    for item in linking['defines']:
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(?:=[A-Za-z0-9_]+)?', item):
            raise ValueError('Invalid SDK define')
    return manifest, linking


def build(target, output, toolchain_root, toolchain_lock, lock_sha256, sdk_cache=None):
    output = Path(output).resolve()
    if output.exists():
        raise ValueError('Windows bridge output must be a new directory')
    if os.name != 'nt':
        raise ValueError('Windows bridge compilation requires a Windows host; no runtime acceptance on macOS')
    pins = json.loads((HERE / 'windows-pins.json').read_text())
    release = json.loads((HERE / 'windows-release-pin.json').read_text())
    lock = verify_toolchain(toolchain_root, toolchain_lock, lock_sha256, target)
    root = Path(toolchain_root).resolve()
    env = {k: v for k, v in os.environ.items() if k.upper() not in
           {'CL', '_CL_', 'LINK', '_LINK_', 'INCLUDE', 'LIB', 'LIBPATH', 'VCINSTALLDIR', 'WINDOWSSDKDIR'}}
    env['PATH'] = str((root / lock['compiler']).parent) + os.pathsep + os.environ.get('SystemRoot', 'C:\\Windows') + '\\System32'

    def run(command):
        return subprocess.run([str(v) for v in command], env=env, check=True,
                              capture_output=True, text=True).stdout.strip()

    version = run([root / lock['compiler'], '--version'])
    if link_sdk.compiler_identity(version) != lock['clangVersion']:
        raise ValueError('Windows compiler version differs from approved lock')
    sdk = prebuilt.install(release, target, local_pins=pins, cache_root=sdk_cache)
    manifest, linking = sdk_contract(sdk, target, pins)
    if link_sdk.compiler_identity(manifest['targets'][target]['toolchain']['clang']) != lock['clangVersion']:
        raise ValueError('Windows compiler differs from SDK compiler')
    source_sha = link_sdk.source_digest()
    output = Path(output).resolve()
    if output.exists():
        raise ValueError('Windows bridge output must be a new directory')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.windows-bridge-', dir=output.parent) as temp:
        work = Path(temp)
        definition = work / 'exports.def'
        definition.write_text('LIBRARY source_v8\nEXPORTS\n' + '\n'.join(sorted(link_sdk.EXPORTS)) + '\n')
        dll = work / 'source_v8.dll'
        command = [root / lock['compiler'], '--no-default-config', '/nologo', '/X', '/O2', '/LD',
                   '/vctoolsdir:' + str(root / lock['msvcRoot']),
                   '/vctoolsversion:' + lock['msvcVersion'],
                   '/winsdkdir:' + str(root / lock['windowsSdkRoot']),
                   '/winsdkversion:' + lock['windowsSdkVersion']]
        command += linking['compileOptions']
        command += ['/I' + str(sdk / target / item) for item in linking['includeDirs']]
        command += ['/imsvc' + str(root / item) for item in lock['includeDirs']]
        command += ['/D' + item for item in linking['defines']]
        command += [link_sdk.PACKAGE / 'src/source_v8.cpp', '/Fo:' + str(work / 'bridge.obj'), '/Fe:' + str(dll),
                    '/link', '/def:' + str(definition), '/IMPLIB:' + str(work / 'source_v8.lib'),
                    '/lldignoreenv', '/OPT:REF', '/OPT:ICF']
        command += linking['linkOptions'] + ['/libpath:' + str(root / item) for item in lock['libraryDirs']]
        command += [sdk / target / item for item in linking['libraries']] + linking['systemLibraries']
        run(command)
        inspection = inspect_pe(dll.read_bytes(), target)
        sdk_contract(sdk, target, pins)
        verify_toolchain(root, toolchain_lock, lock_sha256, target)
        if link_sdk.source_digest() != source_sha:
            raise ValueError('Bridge source changed during build')
        shutil.copytree(sdk / 'licenses', work / 'licenses')
        evidence = {'schemaVersion': 1, 'target': target, 'v8': pins['v8'],
                    'bridge': {'abi': 1, 'sourceSha256': source_sha},
                    'sdkManifestSha256': prebuilt.sha(sdk / 'manifest.json'),
                    'toolchainLockSha256': lock_sha256,
                    'consumerInputs': {name: prebuilt.sha(HERE / name) for name in
                                       ('windows_sdk.py', 'windows-pins.json', 'windows-release-pin.json')},
                    'binary': {'name': dll.name, 'size': dll.stat().st_size, 'sha256': prebuilt.sha(dll)},
                    'inspection': inspection, 'validation': {'built': True, 'runtimeTested': False,
                                                           'flutterHookIntegrated': False}}
        (work / 'manifest.json').write_text(json.dumps(evidence, indent=2) + '\n')
        # Publish a standalone artifact, never an unaccepted hook cache entry.
        work.rename(output)
    return output


def verify_runtime(directory):
    """Load the exact DLL and execute its ABI on a matching native Windows host."""
    if os.name != 'nt':
        raise ValueError('Runtime verification requires a native Windows process')
    directory = Path(directory).resolve()
    manifest = json.loads((directory / 'manifest.json').read_text())
    target = manifest['target']
    native = {'amd64': 'windows-x64', 'x86_64': 'windows-x64',
              'arm64': 'windows-arm64', 'aarch64': 'windows-arm64'}.get(platform.machine().lower())
    if target != native:
        raise ValueError('Runtime probe must execute on the matching native architecture')
    dll = directory / 'source_v8.dll'
    if (manifest['binary']['sha256'] != prebuilt.sha(dll) or
            manifest['binary']['size'] != dll.stat().st_size or
            manifest['bridge'] != {'abi': 1, 'sourceSha256': link_sdk.source_digest()}):
        raise ValueError('Runtime probe DLL/source checksum differs')
    inspect_pe(dll.read_bytes(), target)
    library = ctypes.CDLL(str(dll))
    pointer, text = ctypes.c_void_p, ctypes.c_char_p
    signatures = {'sv8_create': ([ctypes.c_int, ctypes.c_int], pointer),
                  'sv8_start': ([pointer, text, text, text], None),
                  'sv8_poll': ([pointer], pointer), 'sv8_free': ([pointer], None),
                  'sv8_resolve': ([pointer, ctypes.c_int, text, ctypes.c_int], None),
                  'sv8_destroy': ([pointer], None), 'sv8_version': ([], text)}
    for name, (args, result) in signatures.items():
        function = getattr(library, name)
        function.argtypes, function.restype = args, result
    expected_version = manifest['v8']['version']
    actual_version = library.sv8_version().decode()
    if actual_version != expected_version:
        raise ValueError('Loaded V8 version differs from pinned SDK')
    runtime = library.sv8_create(1000, 32)
    if not runtime:
        raise ValueError('Windows V8 runtime creation failed')

    def poll():
        value = library.sv8_poll(runtime)
        if not value:
            raise ValueError('Native poll returned null')
        try:
            return json.loads(ctypes.string_at(value))
        finally:
            library.sv8_free(value)

    def start(script):
        library.sv8_start(runtime, script.encode(), b'{}', b'')
        return poll()

    try:
        features = start('({answer:6*7,intl:typeof Intl,temporal:typeof Temporal,wasm:typeof WebAssembly})')
        if features != {'status': 'done', 'value': {'answer': 42, 'intl': 'object',
                                                  'temporal': 'object', 'wasm': 'object'}}:
            raise ValueError('Full V8 feature probe failed: ' + str(features))
        if start('(globalThis.savedValue=41, savedValue+1)') != {'status': 'done', 'value': 42}:
            raise ValueError('Initial context probe failed')
        if start('Promise.resolve(savedValue+2)') != {'status': 'done', 'value': 43}:
            raise ValueError('Promise/context retention probe failed')
        pending = start("__sourceHost('echo', []).then(value=>value+1)")
        if pending.get('status') != 'pending' or len(pending.get('requests', [])) != 1:
            raise ValueError('Async host request probe failed')
        library.sv8_resolve(runtime, pending['requests'][0]['id'], b'41', 0)
        if poll() != {'status': 'done', 'value': 42}:
            raise ValueError('Async host reply probe failed')
        timeout = start('while(true) {}')
        if timeout.get('error') != 'execution_timeout':
            raise ValueError('Timeout probe failed')
        if start('savedValue+3') != {'status': 'done', 'value': 44}:
            raise ValueError('Timeout recovery probe failed')
    finally:
        library.sv8_destroy(runtime)
    result = {'target': target, 'dllSha256': prebuilt.sha(dll), 'v8Version': actual_version,
              'passed': True, 'checks': ['features', 'context', 'promise', 'asyncHost',
                                       'timeout', 'timeoutRecovery']}
    (directory / 'runtime-result.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-runtime', type=Path)
    parser.add_argument('--target', choices=sorted(MACHINES))
    parser.add_argument('--output', type=Path)
    parser.add_argument('--toolchain-root', type=Path)
    parser.add_argument('--toolchain-lock', type=Path)
    parser.add_argument('--toolchain-lock-sha256')
    parser.add_argument('--sdk-cache', type=Path)
    args = parser.parse_args()
    if args.verify_runtime:
        print(json.dumps(verify_runtime(args.verify_runtime), indent=2))
        return
    if not all((args.target, args.output, args.toolchain_root, args.toolchain_lock, args.toolchain_lock_sha256)):
        parser.error('build requires target, output, toolchain-root, toolchain-lock and toolchain-lock-sha256')
    print(build(args.target, args.output, args.toolchain_root, args.toolchain_lock,
                args.toolchain_lock_sha256, args.sdk_cache))


if __name__ == '__main__':
    main()
