#!/usr/bin/env python3
"""Audit fixed-release SDK availability separately from Legado consumer readiness.

Read-only: authenticate an already downloaded release manifest; never download,
compile V8, discover a latest release, or infer app support from SDK availability.
"""
import argparse
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
REQUIRED_TARGETS = (
    'android-arm64', 'android-x64', 'ios-arm64', 'ios-simulator-arm64',
    'macos-arm64', 'macos-x64', 'linux-x64', 'linux-arm64',
    'windows-x64', 'windows-arm64',
)
BLOCKERS = {
    'ios-arm64': 'Signed physical-device application runtime and complete source conformance',
    'ios-simulator-arm64': 'Full migrated application and complete source conformance (example native asset integration passed)',
    'linux-x64': 'Pinned Linux sysroot, shared bridge linker/export validation and runtime conformance',
    'linux-arm64': 'Pinned ARM64 Linux sysroot, cross linker/export validation and ARM64 runtime conformance',
    'windows-x64': 'Pinned Windows Clang/MSVC SDK, DLL export inspection and portable publish lock',
    'windows-arm64': 'Pinned Windows cross Clang/MSVC SDK, ARM64 DLL inspection and portable publish lock',
}


def audit(manifest_bytes, pin, application_pins):
    if hashlib.sha256(manifest_bytes).hexdigest() != pin['releaseManifestSha256']:
        raise ValueError('Release manifest SHA256 differs from reviewed pin')
    manifest = json.loads(manifest_bytes)
    for key in ('v8', 'depotTools'):
        if manifest.get(key) != pin[key] or application_pins.get(key) != pin[key]:
            raise ValueError('Release/application source provenance differs')
    assets = manifest.get('assets', [])
    targets = manifest.get('targets', {})
    published = {}
    for asset in assets:
        # Release metadata (for example pins.json) is not a platform SDK.
        if 'target' not in asset:
            continue
        target = asset['target']
        if target in published:
            raise ValueError('Duplicate published SDK target')
        published[target] = asset
    for asset in pin['assets']:
        if published.get(asset['target']) != asset:
            raise ValueError('Reviewed SDK asset differs from authenticated release')
    rows = []
    for target in REQUIRED_TARGETS:
        sdk = targets.get(target)
        available = target in published and sdk is not None
        if available and target.startswith('ios-'):
            # An iOS SDK cannot be assumed to provide the desktop JIT/Wasm profile.
            args = sdk.get('gnArgs', '').splitlines()
            for setting in ('v8_jitless = true', 'v8_enable_webassembly = false',
                            'v8_enable_turbofan = false', 'v8_enable_maglev = false',
                            'v8_enable_sparkplug = false'):
                if setting not in args:
                    raise ValueError('iOS SDK jitless feature contract differs')
        configured = target in application_pins['targets']
        if configured:
            if not available or target not in pin['sdkManifestSha256']:
                raise ValueError('Configured consumer lacks a pinned SDK manifest')
            actual_config = sdk.get('targetConfig')
            if actual_config is None and target.startswith('ios-'):
                actual_config = {'cpu': sdk.get('binaryInspection', {}).get('architecture'),
                                 'environment': sdk.get('environment'), 'minIOS': sdk.get('minIOS')}
            if actual_config != application_pins['targets'][target]:
                raise ValueError('Configured consumer target metadata differs')
        rows.append({
            'target': target, 'sdkPublished': available,
            'consumerConfigured': configured,
            'sdkRuntimeTested': sdk.get('validation', {}).get('runtimeTested', False) if sdk else False,
            'applicationRuntimeAccepted': False,
            'remaining': BLOCKERS.get(target, 'Per-platform application Release and legacy-source acceptance'),
        })
    return {'schemaVersion': 1, 'v8Version': pin['v8']['version'],
            'releaseManifestSha256': pin['releaseManifestSha256'], 'targets': rows,
            'allConsumersConfigured': all(row['consumerConfigured'] for row in rows),
            'allApplicationPlatformsAccepted': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-manifest', required=True, type=Path)
    parser.add_argument('--require-all-consumers', action='store_true')
    args = parser.parse_args()
    release = json.loads((HERE / 'release-pin.json').read_text())
    pins = json.loads((HERE / 'pins.json').read_text())
    ios_release = json.loads((HERE / 'ios-release-pin.json').read_text())
    ios_pins = json.loads((HERE / 'ios-pins.json').read_text())
    if (any(ios_pins[key] != pins[key] for key in ('v8', 'depotTools')) or
            any(ios_release[key] != release[key] for key in
                ('v8', 'depotTools', 'releaseManifestSha256'))):
        raise ValueError('iOS and Android/macOS consumer source pins differ')
    pins['targets'] = pins['targets'] | ios_pins['targets']
    release['assets'] += ios_release['assets']
    release['sdkManifestSha256'] |= ios_release['sdkManifestSha256']
    result = audit(args.release_manifest.read_bytes(), release, pins)
    print(json.dumps(result, indent=2))
    if args.require_all_consumers and not result['allConsumersConfigured']:
        raise SystemExit(1)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        raise SystemExit('V8 platform audit failed: ' + str(error))
