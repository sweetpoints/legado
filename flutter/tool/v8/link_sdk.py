#!/usr/bin/env python3
"""Compile Legado's unchanged C bridge against a verified pure V8 SDK.

No V8 source checkout or GN/Ninja execution is performed. Toolchain and system
SDK/sysroot are explicit inputs; published binaries keep the original cache ABI.
"""
import argparse
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PACKAGE = ROOT / 'flutter/packages/source_v8'
EXPORTS = {'sv8_create', 'sv8_start', 'sv8_poll', 'sv8_resolve', 'sv8_sync_poll',
           'sv8_sync_reply', 'sv8_cancel', 'sv8_destroy', 'sv8_free', 'sv8_version'}
PROFILES = {
    'android-arm64': ('aarch64-linux-android26', '.so', 183),
    'android-x64': ('x86_64-linux-android26', '.so', 62),
    'macos-arm64': ('arm64-apple-macos13.0', '.dylib', 0x0100000c),
    'macos-x64': ('x86_64-apple-macos13.0', '.dylib', 0x01000007),
}
SPEC = importlib.util.spec_from_file_location('v8_sdk_consumer', HERE / 'prebuilt.py')
consumer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(consumer)


def source_files():
    return {name: PACKAGE / name for name in ('src/source_v8.cpp', 'src/source_v8.h', 'src/android_exports.map')} | {
        'tool/v8/source_v8.gni': HERE / 'source_v8.gni'}


def source_digest(files=None):
    digest = hashlib.sha256()
    for label, path in sorted((source_files() if files is None else files).items()):
        name, data = label.encode(), path.read_bytes()
        digest.update(struct.pack('>Q', len(name)) + name + struct.pack('>Q', len(data)) + data)
    return digest.hexdigest()


def _strings(value, label):
    if not isinstance(value, list) or any(not isinstance(v, str) or not v or '\x00' in v for v in value):
        raise ValueError(label + ' must be a list of non-empty strings')
    return value


def static_library_grouping(linking, target):
    if 'staticLibraryGrouping' not in linking:
        return False
    if linking['staticLibraryGrouping'] != 'rescan' or not target.startswith('android-'):
        raise ValueError('SDK static library grouping supports Android rescan only')
    return True


def verified_sdk(sdk_root, target, pins):
    if target not in PROFILES or target not in pins['targets']:
        raise ValueError('Bridge linking supports pinned Android/macOS profiles only')
    sdk_root = Path(sdk_root)
    if sdk_root.is_symlink():
        raise ValueError('SDK root must not be a symlink')
    manifest = json.loads((sdk_root / 'manifest.json').read_text())
    consumer.validate_sdk(sdk_root, manifest, pins, pins, target)
    sdk_pins = json.loads((sdk_root / 'pins.json').read_text())
    if any(sdk_pins.get(k) != pins[k] for k in ('v8', 'depotTools')) or sdk_pins['targets'].get(target) != pins['targets'][target]:
        raise ValueError('SDK/application source or platform pins mismatch')
    linking = json.loads((sdk_root / target / 'linking.json').read_text())
    if type(linking.get('schemaVersion')) is not int or linking['schemaVersion'] != 1:
        raise ValueError('Unsupported SDK linking schema')
    for key in ('includeDirs', 'defines', 'compileOptions', 'libraries', 'linkOptions', 'systemLibraries'):
        _strings(linking.get(key), key)
    static_library_grouping(linking, target)
    platform_root = sdk_root / target
    indexed = {item['path'] for item in manifest['targets'][target]['files']}
    for label in linking['includeDirs']:
        relative = str(consumer._relative(label))
        folder = platform_root / relative
        if not folder.is_dir() or folder.is_symlink() or not any(p.startswith(target + '/' + relative + '/') for p in indexed):
            raise ValueError('SDK include directory is missing or not indexed')
    for label in linking['libraries']:
        relative = str(consumer._relative(label))
        library = platform_root / relative
        if target + '/' + relative not in indexed or library.suffix not in ('.a', '.lib'):
            raise ValueError('SDK linking library must be an indexed static archive')
        with library.open('rb') as stream:
            if stream.read(8) != b'!<arch>\n':
                raise ValueError('SDK linking library is not a self-contained archive')
    if manifest['targets'][target]['binary'] not in {target + '/' + path for path in linking['libraries']}:
        raise ValueError('SDK linking contract omits the pinned V8 monolith')
    for define in linking['defines']:
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(?:=[^\r\n]+)?', define):
            raise ValueError('Invalid SDK compiler define')
    triple = PROFILES[target][0]
    allowed = {'-std=c++20', '-std=c++17', '-fPIC', '-fno-rtti', '-fno-exceptions',
               '-nostdinc++', '-nostdlib++', '-pthread', '-stdlib=libc++', '-fvisibility=hidden',
               '-fvisibility-inlines-hidden', '-fexperimental-relative-c++-abi-vtables',
               '-fno-experimental-relative-c++-abi-vtables', '--target=' + triple, '-mmacosx-version-min=13.0',
               '-Wl,-z,max-page-size=16384'}
    if any(flag not in allowed for flag in linking['compileOptions']):
        raise ValueError('SDK compile/link option is outside the supported bridge profile')
    options = iter(linking['linkOptions'])
    for option in options:
        if option == '-framework' and target.startswith('macos-'):
            framework = next(options, None)
            if framework is None or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*', framework):
                raise ValueError('Invalid Apple framework option pair')
        elif option == '--unwindlib=none' and target.startswith('android-'):
            # The SDK runtime already contains Chromium's pinned libunwind.
            # Do not let Clang add an unrelated sysroot libunwind archive.
            continue
        elif option not in allowed:
            raise ValueError('SDK compile/link option is outside the supported bridge profile')
    if any(not re.fullmatch(r'[A-Za-z0-9_+-]+', library) for library in linking['systemLibraries']):
        raise ValueError('Invalid SDK system library')
    return manifest, linking


def command(sdk_root, target, linking, compiler, sysroot, output, files):
    triple, suffix, _ = PROFILES[target]
    platform_root = Path(sdk_root) / target
    args = [str(compiler), *linking['compileOptions'], '--target=' + triple,
            '--sysroot=' + str(sysroot), '-fPIC', '-fvisibility=hidden', '-fvisibility-inlines-hidden']
    args += ['-I' + str(platform_root / path) for path in linking['includeDirs']]
    args += ['-D' + define for define in linking['defines']]
    args += ['-I' + str(files['src/source_v8.h'].parent), str(files['src/source_v8.cpp'])]
    rescan = static_library_grouping(linking, target)
    if rescan:
        args += ['-Wl,--start-group']
    args += [str(platform_root / path) for path in linking['libraries']]
    if rescan:
        args += ['-Wl,--end-group']
    args += linking['linkOptions']
    args += ['--target=' + triple, '--sysroot=' + str(sysroot)]
    if suffix == '.so':
        args += ['-shared', '-Wl,--no-undefined', '-Wl,--exclude-libs,ALL',
                 '-Wl,--version-script=' + str(files['src/android_exports.map']), '-Wl,-z,max-page-size=16384',
                 '-Wl,-soname,libsource_v8.so']
    else:
        args += ['-dynamiclib', '-Wl,-undefined,error', '-mmacosx-version-min=13.0',
                 '-Wl,-install_name,@rpath/libsource_v8.dylib']
        args += ['-Wl,-exported_symbol,_' + name for name in sorted(EXPORTS)]
    args += ['-l' + library for library in linking['systemLibraries']]
    args += ['-o', str(output)]
    return args


def inspect_binary(path, target):
    data = path.read_bytes()
    expected = PROFILES[target][2]
    if target.startswith('android-'):
        if len(data) < 64 or data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<HH', data, 16) != (3, expected):
            raise ValueError('Bridge must be the expected Android ELF64 shared library')
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        if size < 56 or not count or offset + size * count > len(data):
            raise ValueError('Invalid ELF program headers')
        alignments = []
        for index in range(count):
            pos = offset + index * size
            if struct.unpack_from('<I', data, pos)[0] != 1:
                continue
            file_offset, address = struct.unpack_from('<QQ', data, pos + 8)
            alignment = struct.unpack_from('<Q', data, pos + 48)[0]
            if alignment < 16384 or alignment & (alignment - 1) or file_offset % 16384 != address % 16384:
                raise ValueError('Android bridge LOAD segments must support 16 KiB pages')
            alignments.append(alignment)
        if not alignments:
            raise ValueError('Android bridge has no LOAD segments')
        return {'elfMachine': expected, 'loadSegmentAlignments': alignments}
    if len(data) < 32 or data[:4] != b'\xcf\xfa\xed\xfe' or struct.unpack_from('<I', data, 4)[0] != expected or struct.unpack_from('<I', data, 12)[0] != 6:
        raise ValueError('Bridge must be the expected thin macOS Mach-O dylib')
    return {'format': 'mach-o', 'cpuType': expected}


def _run(args):
    return subprocess.run([str(a) for a in args], check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def inspect_exports(binary, target, nm):
    if target.startswith('android-'):
        output = _run([nm, '--dynamic', '--defined-only', '--format=posix', binary])
        actual = {line.split()[0].split('@')[0] for line in output.splitlines() if line.strip()}
    else:
        output = _run([nm, '-gU', binary])
        actual = {line.split()[-1].removeprefix('_') for line in output.splitlines() if line.strip()}
    if actual != EXPORTS:
        raise ValueError('Bridge exports must match exactly the ten sv8 ABI symbols')
    return sorted(actual)


@contextlib.contextmanager
def _publish_lock(path):
    import fcntl
    with path.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def verify_existing_cache(root, manifest, pins, bridge):
    if manifest.get('schemaVersion') != 1 or any(manifest.get(k) != value for k, value in (
            ('v8', pins['v8']), ('depotTools', pins['depotTools']), ('bridge', bridge))):
        raise ValueError('Existing bridge cache provenance differs')
    for path in root.rglob('*'):
        if path.is_symlink():
            raise ValueError('Existing bridge cache symlinks forbidden')
    for target, entry in manifest['targets'].items():
        relative = str(consumer._relative(entry['binary']))
        if not relative.startswith(target + '/'):
            raise ValueError('Existing bridge binary target path mismatch')
        binary = root / relative
        if not binary.is_file() or consumer.sha(binary) != entry['sha256'] or binary.stat().st_size != entry['size']:
            raise ValueError('Existing bridge binary checksum/size mismatch')
        for basename, field in (('args.gn', 'gnArgsSha256'), ('dependencies.txt', 'dependencyInventorySha256'), ('defines.json', 'definesSha256')):
            if consumer.sha(root / target / basename) != entry[field]:
                raise ValueError('Existing bridge metadata checksum mismatch')
    for item in manifest['licenses']:
        relative = str(consumer._relative(item['path']))
        if not relative.startswith('licenses/') or consumer.sha(root / relative) != item['sha256']:
            raise ValueError('Existing bridge license checksum mismatch')


def compiler_identity(version):
    if not isinstance(version, str) or not version.strip():
        raise ValueError('Pinned SDK Clang version evidence required')
    first_line = version.strip().splitlines()[0]
    if 'clang version' not in first_line.lower():
        raise ValueError('Pinned SDK toolchain must identify Clang')
    return first_line


def link(sdk_root, target, *, compiler, sysroot, nm, pins=None, cache_root=None, files=None):
    pins = pins or json.loads((HERE / 'pins.json').read_text())
    files = source_files() if files is None else files
    original_digest = source_digest(files)
    sdk_root = Path(sdk_root)
    manifest, linking = verified_sdk(sdk_root, target, pins)
    for path, label, directory in ((compiler, 'Compiler', False), (sysroot, 'Sysroot', True), (nm, 'Symbol inspector', False)):
        if not (Path(path).is_dir() if directory else Path(path).is_file()):
            raise ValueError(label + ' must be an explicit existing path')
    compiler_version = _run([compiler, '--version']).strip()
    if compiler_identity(manifest['targets'][target].get('toolchain', {}).get('clang')) != compiler_identity(compiler_version):
        raise ValueError('Compiler differs from the pinned SDK Clang toolchain')
    cache_root = Path(cache_root) if cache_root else PACKAGE / '.cache/self-built'
    if 'source_sdk' in cache_root.parts or cache_root.is_symlink():
        raise ValueError('Bridge output cache must be separate from SDK inputs')
    destination = cache_root / pins['v8']['revision']
    if destination.is_symlink():
        raise ValueError('Bridge cache destination symlinks forbidden')
    cache_root.mkdir(parents=True, exist_ok=True)
    sdk_manifest_hash = consumer.sha(sdk_root / 'manifest.json')
    sdk_linking_hash = consumer.sha(sdk_root / target / 'linking.json')
    with tempfile.TemporaryDirectory(prefix='.sdk-bridge-', dir=cache_root) as directory:
        work = Path(directory)
        output = work / ('libsource_v8' + PROFILES[target][1])
        args = command(sdk_root, target, linking, compiler, sysroot, output, files)
        _run(args)
        inspection = inspect_binary(output, target)
        inspection['exports'] = inspect_exports(output, target, nm)
        # Revalidate SDK/source after compilation: never publish mixed input revisions.
        verified_sdk(sdk_root, target, pins)
        if source_digest(files) != original_digest or consumer.sha(sdk_root / 'manifest.json') != sdk_manifest_hash:
            raise ValueError('SDK or bridge sources changed while linking')
        with _publish_lock(cache_root / ('.sdk-bridge-' + pins['v8']['revision'] + '.lock')):
            destination.mkdir(parents=True, exist_ok=True)
            manifest_path = destination / 'manifest.json'
            previous = json.loads(manifest_path.read_text()) if manifest_path.exists() else None
            bridge = {'abi': 1, 'sourceSha256': original_digest}
            if previous is None and any(p.name != '.publish.lock' for p in destination.iterdir()):
                raise ValueError('Existing bridge cache files have no provenance manifest')
            if previous:
                verify_existing_cache(destination, previous, pins, bridge)
            staged = work / 'staged'
            if previous:
                shutil.copytree(destination, staged)
            else:
                staged.mkdir()
            license_index = {item['path']: item for item in previous.get('licenses', [])} if previous else {}
            for item in manifest['licenses']:
                if item['path'] in license_index and license_index[item['path']] != item:
                    raise ValueError('Existing bridge cache license provenance conflict')
                license_index[item['path']] = item
            for item in manifest['licenses']:
                target_path = staged / item['path']; target_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(sdk_root / item['path'], target_path)
            target_dir = staged / target
            target_dir.mkdir(parents=True, exist_ok=True)
            binary = target_dir / output.name
            shutil.copyfile(output, binary)
            for name in ('args.gn', 'dependencies.txt', 'defines.json'):
                shutil.copyfile(sdk_root / target / name, target_dir / name)
            sdk_entry = manifest['targets'][target]
            entry = {
                'binary': target + '/' + output.name, 'sha256': consumer.sha(binary), 'size': binary.stat().st_size,
                'gnArgs': sdk_entry['gnArgs'], 'gnArgsSha256': consumer.sha(target_dir / 'args.gn'),
                'dependencyInventorySha256': consumer.sha(target_dir / 'dependencies.txt'),
                'definesSha256': consumer.sha(target_dir / 'defines.json'),
                'toolchain': {'clang': compiler_version}, 'binaryInspection': inspection,
                'validation': {'built': True, 'runtimeTested': False, 'sourceCompatibilityTested': False},
                'sdkProvenance': {'manifestSha256': sdk_manifest_hash, 'linkingSha256': sdk_linking_hash,
                                  'monolithSha256': sdk_entry['sha256'], 'target': target},
            }
            if 'depsSha256' in sdk_entry:
                entry['depsSha256'] = sdk_entry['depsSha256']
            config = pins['targets'][target]
            if target.startswith('android-'):
                entry.update(minApi=config['minApi'], abi=config['abi'])
            else:
                entry['minMacOS'] = config['minMacOS']
            result = {'schemaVersion': 1, 'v8': pins['v8'], 'depotTools': pins['depotTools'], 'bridge': bridge,
                      'targets': (previous['targets'] if previous else {}) | {target: entry},
                      'licenses': [license_index[k] for k in sorted(license_index)]}
            (staged / 'manifest.json').write_text(json.dumps(result, indent=2) + '\n')
            # Publish all new files only after successful compilation and complete validation.
            backup = work / 'previous'
            os.replace(destination, backup)
            try:
                os.replace(staged, destination)
            except OSError:
                os.replace(backup, destination)
                raise
        return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk-root', required=True, type=Path)
    parser.add_argument('--target', required=True, choices=PROFILES)
    parser.add_argument('--compiler', required=True, type=Path)
    parser.add_argument('--sysroot', required=True, type=Path)
    parser.add_argument('--nm', required=True, type=Path)
    parser.add_argument('--cache-root', type=Path)
    args = parser.parse_args()
    root = link(args.sdk_root, args.target, compiler=args.compiler, sysroot=args.sysroot, nm=args.nm, cache_root=args.cache_root)
    print(json.dumps({'artifactRoot': str(root), 'target': args.target, 'linkedFromSdk': True, 'runtimeTested': False}))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit('V8 SDK bridge link failed: ' + str(error))
