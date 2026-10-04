#!/usr/bin/env python3
"""Build the embedded shared bridge from fixed official V8 source and DEPS."""
import argparse
import hashlib
import fcntl
import json
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PACKAGE = ROOT / 'flutter/packages/source_v8'


def read_pins():
    pins = json.loads((HERE / 'pins.json').read_text())
    for key in ('v8', 'depotTools'):
        if not re.fullmatch(r'[a-f0-9]{40}', pins[key]['revision']):
            raise ValueError('Pins require complete lowercase commit SHA')
        if not pins[key]['repository'].startswith('https://chromium.googlesource.com/'):
            raise ValueError('Only official Chromium source repositories permitted')
    return pins


def run(args, cwd, env=None, capture=False):
    print('+ ' + ' '.join(str(arg) for arg in args), flush=True)
    return subprocess.run([str(arg) for arg in args], cwd=cwd, env=env,
                          check=True, text=True, stdout=subprocess.PIPE if capture else None).stdout


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bridge_files():
    return {name: PACKAGE / name for name in ('src/source_v8.cpp', 'src/source_v8.h', 'src/android_exports.map')} | {
        'tool/v8/source_v8.gni': HERE / 'source_v8.gni'}


def bridge_digest(files=None):
    digest = hashlib.sha256()
    for label, path in sorted((bridge_files() if files is None else files).items()):
        name = label.encode('utf-8')
        data = path.read_bytes()
        digest.update(struct.pack('>Q', len(name)))
        digest.update(name)
        digest.update(struct.pack('>Q', len(data)))
        digest.update(data)
    return digest.hexdigest()


def require_host(target):
    host = (platform.system(), platform.machine())
    expected = ('Darwin', 'arm64') if target == 'macos-arm64' else ('Linux', 'x86_64')
    if host != expected:
        raise ValueError(f'{target} requires host {expected}; found {host}. Android must use Linux x86_64 runner.')


def bootstrap(cache, target, pins):
    require_host(target)
    cache.mkdir(parents=True, exist_ok=True)
    depot = cache / ('depot_tools' if target == 'macos-arm64' else 'depot_tools-linux-x86_64')
    if not depot.exists():
        run(['git', 'clone', '--depth', '1', pins['depotTools']['repository'], depot], cache)
    origin = run(['git', 'remote', 'get-url', 'origin'], depot, capture=True).strip()
    if origin != pins['depotTools']['repository']:
        raise ValueError('Unexpected depot_tools origin')
    run(['git', 'fetch', '--depth', '1', 'origin', pins['depotTools']['revision']], depot)
    run(['git', 'checkout', '--detach', pins['depotTools']['revision']], depot)
    env = dict(os.environ, PATH=str(depot) + os.pathsep + os.environ['PATH'], DEPOT_TOOLS_UPDATE='0')
    workspace = cache / pins['v8']['revision'] / target
    workspace.mkdir(parents=True, exist_ok=True)
    gclient = 'solutions = ' + repr([{'name': 'v8', 'url': pins['v8']['repository'] + '@' + pins['v8']['revision'], 'deps_file': 'DEPS', 'managed': False, 'custom_deps': {}, 'custom_vars': {}}]) + '\n'
    if target == 'android-arm64':
        gclient += "target_os = ['android']\n"
    (workspace / '.gclient').write_text(gclient)
    run([depot / 'gclient', 'sync', '--no-history', '--shallow', '--revision', 'v8@' + pins['v8']['revision']], workspace, env)
    source = workspace / 'v8'
    actual = run(['git', 'rev-parse', 'HEAD'], source, capture=True).strip()
    if actual != pins['v8']['revision']:
        raise ValueError('V8 checkout revision mismatch')
    return source, depot, env


def gn_arguments(target):
    args = {'is_debug': False, 'is_component_build': False, 'v8_monolithic': True,
            'v8_monolithic_for_shared_library': True, 'v8_use_external_startup_data': False,
            'use_custom_libcxx': True, 'v8_enable_i18n_support': False,
            'v8_enable_temporal_support': False, 'use_remoteexec': False,
            'symbol_level': 0, 'target_cpu': 'arm64', 'v8_target_cpu': 'arm64'}
    if target == 'android-arm64':
        args.update(target_os='android', android_ndk_api_level=26)
    else:
        args.update(target_os='mac', mac_deployment_target='13.0')
    return '\n'.join(f'{key} = {json.dumps(value)}' for key, value in sorted(args.items())) + '\n'


def source_version(source):
    header = (source / 'include/v8-version.h').read_text()
    names = ['V8_MAJOR_VERSION', 'V8_MINOR_VERSION', 'V8_BUILD_NUMBER', 'V8_PATCH_LEVEL']
    parts = [re.search(r'#define\s+' + name + r'\s+(\d+)', header).group(1) for name in names]
    return '.'.join(parts[:-1] if parts[-1] == '0' else parts)


def package_licenses(source, destination, existing=()):
    entries = {entry['path']: entry for entry in existing}
    copies = []
    roots = [source / name for name in ('LICENSE', 'AUTHORS')]
    for pattern in ('LICENSE*', 'COPYING*', 'NOTICE*', 'AUTHORS*'):
        roots.extend((source / 'third_party').rglob(pattern))
    for path in sorted(set(roots)):
        if not path.is_file() or '.git' in path.parts:
            continue
        relative = Path('licenses') / path.relative_to(source)
        label = str(relative)
        digest = sha(path)
        if label in entries and entries[label]['sha256'] != digest:
            raise ValueError(f'License provenance conflict at {label}')
        entries[label] = {'path': label, 'sha256': digest}
        copies.append((path, destination / relative))
    # Preflight all conflicts before replacing any indexed license file.
    for path, output in copies:
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, output)
    return [entries[label] for label in sorted(entries)]


def build(source, depot, env, target, jobs, pins):
    if source_version(source) != pins['v8']['version']:
        raise ValueError('Official source version differs from pins')
    compiled_bridge_digest = bridge_digest()
    overlay = source / 'source_v8'
    overlay.mkdir(exist_ok=True)
    for label, path in bridge_files().items():
        shutil.copyfile(path, overlay / path.name)
    copied_files = {label: overlay / path.name for label, path in bridge_files().items()}
    if bridge_digest(copied_files) != compiled_bridge_digest:
        raise ValueError('Bridge source changed while copying build overlay')
    (overlay / 'BUILD.gn').write_text('import("//source_v8/source_v8.gni")\nsource_v8_library("source_v8") {}\n')
    out = source / 'out/source_v8'
    out.mkdir(parents=True, exist_ok=True)
    args = gn_arguments(target)
    (out / 'args.gn').write_text(args)
    run([depot / 'gn', 'gen', out, '--root-target=//source_v8:source_v8', '--fail-on-unused-args'], source, env)
    run([depot / 'autoninja', '-C', out, '-j', jobs, 'source_v8:source_v8'], source, env)
    suffix = '.dylib' if target == 'macos-arm64' else '.so'
    built = out / ('libsource_v8' + suffix)
    if not built.is_file():
        raise ValueError('Shared bridge output missing')
    artifact = PACKAGE / '.cache/self-built' / pins['v8']['revision']
    artifact.mkdir(parents=True, exist_ok=True)
    with (artifact / '.publish.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if bridge_digest() != compiled_bridge_digest or bridge_digest(copied_files) != compiled_bridge_digest:
            raise ValueError('Bridge source or compiled overlay changed during build; refusing publication')
        manifest_path = artifact / 'manifest.json'
        previous = None
        if manifest_path.exists():
            previous = json.loads(manifest_path.read_text())
            if (previous['v8'] != pins['v8'] or previous['depotTools'] != pins['depotTools']
                    or previous['bridge'] != {'abi': 1, 'sourceSha256': compiled_bridge_digest}):
                raise ValueError('Existing artifact manifest provenance differs; use clean artifact directory')
        licenses = package_licenses(source, artifact, previous.get('licenses', []) if previous else [])
        destination = artifact / target
        destination.mkdir(parents=True, exist_ok=True)
        binary = destination / built.name
        temporary = binary.with_name(binary.name + '.publishing')
        shutil.copyfile(built, temporary)
        os.replace(temporary, binary)
        (destination / 'args.gn').write_text(args)
        revinfo = run([depot / 'gclient', 'revinfo', '--actual'], source.parent, env, capture=True)
        (destination / 'dependencies.txt').write_text(revinfo)
        defines = run([depot / 'gn', 'desc', out, '//source_v8:source_v8', 'defines', '--format=json'], source, env, capture=True)
        (destination / 'defines.json').write_text(defines)
        clang = source / 'third_party/llvm-build/Release+Asserts/bin/clang++'
        toolchain = {'clang': run([clang, '--version'], source, env, capture=True).strip(),
                     'gn': run([depot / 'gn', '--version'], source, env, capture=True).strip()}
        entry = {'binary': str(Path(target) / binary.name), 'sha256': sha(binary), 'size': binary.stat().st_size,
                 'host': {'os': platform.system(), 'cpu': platform.machine()},
                 'gnArgs': args, 'gnArgsSha256': sha(destination / 'args.gn'),
                 'depsSha256': sha(source / 'DEPS'), 'dependencyInventorySha256': sha(destination / 'dependencies.txt'),
                 'definesSha256': sha(destination / 'defines.json'), 'toolchain': toolchain,
                 'validation': {'built': True, 'runtimeTested': False, 'sourceCompatibilityTested': False}}
        if target == 'macos-arm64':
            entry['minMacOS'] = '13.0'
        else:
            entry['minApi'] = 26
        manifest = {'schemaVersion': 1, 'v8': pins['v8'], 'depotTools': pins['depotTools'],
                    'bridge': {'abi': 1, 'sourceSha256': compiled_bridge_digest}, 'targets': {}}
        if previous is not None:
            manifest['targets'] = previous['targets']
        manifest['targets'][target] = entry
        manifest['licenses'] = licenses
        temporary_manifest = manifest_path.with_name('manifest.json.publishing')
        temporary_manifest.write_text(json.dumps(manifest, indent=2) + '\n')
        os.replace(temporary_manifest, manifest_path)
        print(json.dumps({'manifest': str(manifest_path), 'target': target, 'binarySha256': entry['sha256']}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['bootstrap', 'build'])
    parser.add_argument('--target', required=True, choices=['macos-arm64', 'android-arm64'])
    parser.add_argument('--jobs', type=int, default=max(1, (os.cpu_count() or 2) // 2))
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error('--jobs must be positive')
    pins = read_pins()
    cache = PACKAGE / '.cache/v8-source'
    source, depot, env = bootstrap(cache, args.target, pins)
    if args.action == 'bootstrap':
        print(json.dumps({'source': str(source), 'revision': pins['v8']['revision']}))
        return
    build(source, depot, env, args.target, args.jobs, pins)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, subprocess.CalledProcessError, OSError) as error:
        print(f'Official V8 build failed: {error}', file=sys.stderr)
        sys.exit(1)
