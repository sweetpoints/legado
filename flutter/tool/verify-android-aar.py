#!/usr/bin/env python3
"""Validate the fixed official native provenance and prepared local Flutter AARs."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('v8_builder', HERE / 'v8/build.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
REPO = builder.ROOT / 'flutter/modules/source_host/build/host/outputs/repo'


def source_digest():
    workspace = builder.ROOT / 'flutter'
    files = list((workspace / 'modules/source_host/lib').rglob('*.dart'))
    files += list((workspace / 'packages').glob('*/lib/**/*.dart'))
    files += list((workspace / 'packages').glob('*/pubspec.yaml'))
    files += [workspace / 'pubspec.yaml', workspace / 'pubspec.lock',
              workspace / 'modules/source_host/pubspec.yaml']
    return builder.bridge_digest({str(path.relative_to(builder.ROOT)): path for path in files})


ANDROID_ABIS = {'android-arm64': 'arm64-v8a', 'android-x64': 'x86_64'}


def configured_targets(value=None):
    names = (value if value is not None else os.environ.get('SOURCE_ENGINE_ANDROID_TARGETS', 'android-arm64,android-x64')).split(',')
    if not names or any(name not in ANDROID_ABIS for name in names) or len(set(names)) != len(names):
        raise ValueError('Android targets must be unique android-arm64 and/or android-x64')
    return names


def native_provenance(target_names=None, root=None):
    pins = builder.read_pins()
    release_pin = json.loads((HERE / 'v8/release-pin.json').read_text())
    root = root or builder.PACKAGE / '.cache/self-built' / pins['v8']['revision']
    manifest = json.loads((root / 'manifest.json').read_text())
    if (manifest.get('schemaVersion') != 1 or manifest['v8'] != pins['v8']
            or manifest['depotTools'] != pins['depotTools']
            or manifest['bridge'] != {'abi': 1, 'sourceSha256': builder.bridge_digest()}):
        raise ValueError('Official V8 source/bridge provenance differs from current pins')
    targets = {}
    for name in target_names or configured_targets():
        target = manifest['targets'][name]
        sdk = target.get('sdkProvenance', {})
        if (sdk.get('target') != name or sdk.get('manifestSha256') != release_pin['sdkManifestSha256'][name]
                or sdk.get('releaseManifestSha256') != release_pin['releaseManifestSha256']):
            raise ValueError('Android bridge must consume the pinned released V8 SDK')
        binary = (root / target['binary']).resolve()
        if not binary.is_relative_to(root.resolve()) or target.get('minApi') != 26:
            raise ValueError('Invalid Android native artifact contract')
        if builder.sha(binary) != target['sha256'] or binary.stat().st_size != target['size']:
            raise ValueError('Official Android V8 binary checksum or size mismatch')
        targets[name] = target
    return manifest, targets


def check_aar(repo, mode, native_shas):
    if isinstance(native_shas, str):
        native_shas = {"arm64-v8a": native_shas}
    relative = Path(f'io/legado/source/source_host/flutter_{mode}/1.0/flutter_{mode}-1.0.aar')
    aar = repo / relative
    if not aar.with_suffix('.pom').is_file():
        raise ValueError(f'Missing Flutter {mode} Maven POM')
    with zipfile.ZipFile(aar) as archive:
        import hashlib
        names = archive.namelist()
        for abi, digest in native_shas.items():
            native = f'jni/{abi}/libsource_v8.so'
            if native not in names:
                raise ValueError(f'Flutter {mode} AAR missing required {abi} V8 library')
            if hashlib.sha256(archive.read(native)).hexdigest() != digest:
                raise ValueError(f'Flutter {mode} AAR has stale or unverified V8 library')
        required = ['assets/flutter_assets/NativeAssetsManifest.json',
                    'assets/flutter_assets/AssetManifest.bin',
                    'assets/flutter_assets/kernel_blob.bin'] if mode == 'debug' else [
                    'assets/flutter_assets/NativeAssetsManifest.json',
                    'assets/flutter_assets/AssetManifest.bin',
                    *[f'jni/{abi}/libapp.so' for abi in native_shas]]
        if any(name not in names for name in required):
            raise ValueError(f'Flutter {mode} assets/AOT library missing')
        if any(name.startswith('jni/') and name.endswith('.so')
               and name.split('/')[1] not in native_shas for name in names):
            raise ValueError('Prepared AAR must contain only requested Android ABIs')
    return {'path': str(relative), 'sha256': builder.sha(aar),
            'abis': sorted(native_shas), 'nativeSha256ByAbi': native_shas}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-only', action='store_true')
    parser.add_argument('--targets')
    parser.add_argument('--write-stamp', action='store_true')
    parser.add_argument('--print-source-digest', action='store_true')
    parser.add_argument('--expected-source-sha')
    parser.add_argument('modes', nargs='*', choices=['debug', 'release'])
    args = parser.parse_args()
    if args.print_source_digest:
        print(source_digest())
        return
    if args.expected_source_sha and source_digest() != args.expected_source_sha:
        raise ValueError('Flutter source inputs changed during AAR compilation')
    if args.write_stamp and not args.expected_source_sha:
        parser.error("--write-stamp requires the source digest captured before compilation")
    target_names = configured_targets(args.targets)
    manifest, targets = native_provenance(target_names)
    native_shas = {ANDROID_ABIS[name]: target['sha256'] for name, target in targets.items()}
    if args.native_only:
        return
    if not args.modes:
        parser.error('Specify debug and/or release')
    entries = {mode: check_aar(REPO, mode, native_shas) for mode in args.modes}
    stamp = REPO / 'source-engine-artifacts.json'
    if args.write_stamp:
        previous = json.loads(stamp.read_text()) if stamp.exists() else {}
        bridge = manifest['bridge']
        source_sha = source_digest()
        if source_sha != args.expected_source_sha:
            raise ValueError("Flutter source inputs changed during AAR validation")
        if (previous.get('v8') == manifest['v8'] and previous.get('depotTools') == manifest['depotTools']
                and previous.get('bridge') == bridge
                and previous.get('sourceSha256') == source_sha
                and previous.get('abis') == sorted(native_shas)):
            entries = previous.get('artifacts', {}) | entries
        data = {'schemaVersion': 1, 'v8': manifest['v8'], 'depotTools': manifest['depotTools'], 'bridge': bridge,
                'sourceSha256': source_sha, 'abis': sorted(native_shas), 'artifacts': entries}
        temporary = stamp.with_suffix('.json.publishing')
        temporary.write_text(json.dumps(data, indent=2) + '\n')
        if source_digest() != source_sha:
            temporary.unlink()
            raise ValueError("Flutter source inputs changed before AAR publication")
        temporary.replace(stamp)
    print(json.dumps({'validatedModes': args.modes, 'nativeSha256ByAbi': native_shas}))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise SystemExit(f'Flutter source AAR preparation failed: {error}. '
                         'Link the application bridge from the pinned released SDK with prepare_sdk.py, then run '
                         'bash flutter/tool/prepare-android-aar.sh with JDK 21 and Flutter 3.47.6.')
