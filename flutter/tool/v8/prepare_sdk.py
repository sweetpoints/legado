#!/usr/bin/env python3
"""Prepare the pinned released V8 SDK and compile only Legado's C++ bridge."""
import argparse
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import prebuilt
import link_sdk
import toolchains


def prepare(target):
    pins = json.loads((HERE / 'pins.json').read_text())
    release = json.loads((HERE / 'release-pin.json').read_text())
    sdk = prebuilt.install(release, target, local_pins=pins)
    bridge_root = link_sdk.PACKAGE / '.cache/self-built' / pins['v8']['revision']
    manifest_path = bridge_root / 'manifest.json'
    if manifest_path.is_file():
        existing = json.loads(manifest_path.read_text())
        bridge = {'abi': 1, 'sourceSha256': link_sdk.source_digest()}
        if existing.get('bridge') == bridge:
            link_sdk.verify_existing_cache(bridge_root, existing, pins, bridge)
            entry = existing.get('targets', {}).get(target)
            expected = release['sdkManifestSha256'][target]
            if (entry and entry.get('sdkProvenance', {}).get('manifestSha256') == expected
                    and entry['sdkProvenance'].get('releaseManifestSha256') == release['releaseManifestSha256']):
                return bridge_root
    compiler = toolchains.install()
    sysroot = toolchains.sysroot(target)
    result = link_sdk.link(sdk, target, compiler=compiler / 'bin/clang++',
                           sysroot=sysroot, nm=toolchains.symbol_inspector(target, sysroot), pins=pins)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', required=True, choices=['macos-arm64', 'android-arm64', 'android-x64'])
    args = parser.parse_args()
    print(json.dumps({'artifactRoot': str(prepare(args.target)), 'target': args.target,
                      'v8BuiltLocally': False, 'bridgeLinkedFromSdk': True}))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        raise SystemExit('Pinned V8 SDK preparation failed: ' + str(error))
