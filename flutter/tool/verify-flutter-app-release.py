#!/usr/bin/env python3
"""Verify standalone Flutter Release APKs, not the legacy Android host APK.

Checks both split ABIs, release manifest, signatures, 16-KiB zip/ELF alignment,
and byte-for-byte equality with the authenticated SDK-linked V8 bridge cache.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('flutter_release_v8_linker', ROOT / 'flutter/tool/v8/link_sdk.py')
linker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(linker)
ABIS = {'arm64-v8a': 'android-arm64', 'x86_64': 'android-x64'}


def run(command):
    return subprocess.run([str(value) for value in command], check=True,
                          capture_output=True, text=True).stdout


def tools_directory():
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise ValueError('ANDROID_HOME or ANDROID_SDK_ROOT is required')
    candidates = [p for p in (Path(sdk) / 'build-tools').iterdir()
                  if p.is_dir() and re.fullmatch(r'\d+(?:\.\d+)+', p.name)]
    for directory in sorted(candidates, key=lambda p: tuple(map(int, p.name.split('.'))), reverse=True):
        if all((directory / name).is_file() for name in ('aapt2', 'apksigner', 'zipalign')):
            return directory
    raise ValueError('Android build-tools with aapt2/apksigner/zipalign are required')


def verify(app_root, tools):
    app_root = Path(app_root)
    registrant = app_root / 'android/app/src/main/java/io/flutter/plugins/GeneratedPluginRegistrant.java'
    if 'IntegrationTestPlugin' in registrant.read_text():
        raise ValueError('Release registrant still contains the dev-only integration_test plugin')
    pins = json.loads((linker.HERE / 'pins.json').read_text())
    release = json.loads((linker.HERE / 'release-pin.json').read_text())
    root = linker.PACKAGE / '.cache/self-built' / pins['v8']['revision']
    manifest = json.loads((root / 'manifest.json').read_text())
    linker.verify_existing_cache(root, manifest, pins,
                                 {'abi': 1, 'sourceSha256': linker.source_digest()})
    results = []
    for abi, target in ABIS.items():
        apk = app_root / 'build/app/outputs/flutter-apk' / f'app-{abi}-release.apk'
        entry = manifest['targets'][target]
        origin = entry['sdkProvenance']
        if (origin['target'] != target or origin['manifestSha256'] != release['sdkManifestSha256'][target]
                or origin['releaseManifestSha256'] != release['releaseManifestSha256']):
            raise ValueError('V8 cache differs from the reviewed Release SDK provenance')
        badging = run([tools / 'aapt2', 'dump', 'badging', apk])
        if not re.search(r"^package: name='io\.legado\.legado_app'", badging, re.M):
            raise ValueError('Unexpected standalone Flutter application ID')
        if 'application-debuggable' in badging:
            raise ValueError('APK is debuggable')
        if not re.search(r"^(?:minSdkVersion|sdkVersion):'26'$", badging, re.M):
            raise ValueError('Standalone application must preserve Android API 26 minimum')
        run([tools / 'apksigner', 'verify', '--verbose', apk])
        run([tools / 'zipalign', '-c', '-P', '16', '4', apk])
        with zipfile.ZipFile(apk) as archive:
            entries = archive.namelist()
            actual_abis = {name.split('/')[1] for name in entries
                           if name.startswith('lib/') and name.endswith('.so')}
            if actual_abis != {abi}:
                raise ValueError('Split APK has unexpected native ABIs: ' + repr(actual_abis))
            names = [name for name in entries if name.startswith(f'lib/{abi}/') and
                     name.endswith('source_v8.so')]
            if len(names) != 1:
                raise ValueError('APK must contain exactly one real V8 bridge')
            native = archive.read(names[0])
        digest = hashlib.sha256(native).hexdigest()
        if digest != entry['sha256'] or len(native) != entry['size']:
            raise ValueError('Packaged V8 bridge bytes differ from the authenticated linked artifact')
        with tempfile.TemporaryDirectory(prefix='flutter-apk-inspect-') as temporary:
            binary = Path(temporary) / 'libsource_v8.so'
            binary.write_bytes(native)
            inspection = linker.inspect_binary(binary, target)
        results.append({'abi': abi, 'target': target, 'apk': str(apk.resolve()),
                        'bytes': apk.stat().st_size, 'sha256': linker.consumer.sha(apk),
                        'debuggable': False, 'signed': True, 'zipAligned16KiB': True,
                        'v8Sha256': digest, 'v8Inspection': inspection})
    return {'schemaVersion': 1, 'applicationId': 'io.legado.legado_app',
            'v8Version': pins['v8']['version'], 'productionSigningVerified': False,
            'deviceExecuted': False, 'apks': results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app-root', type=Path, default=ROOT / 'flutter/apps/legado')
    parser.add_argument('--build-tools', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = verify(args.app_root, args.build_tools or tools_directory())
    serialized = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized)
    print(serialized, end='')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        raise SystemExit('Flutter Release APK verification failed: ' + str(error))
