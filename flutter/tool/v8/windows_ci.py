#!/usr/bin/env python3
"""Prepare the independently reviewed Windows toolchain and bridge for native CI.

Uses fixed installed version paths; never installs system software or approves a
new candidate. A changed runner payload fails against the committed review lock.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile

import prebuilt
import link_sdk
import windows_sdk
import windows_toolchain_receipt as receipts

HERE = Path(__file__).resolve().parent
INPUTS = windows_sdk.consumer_inputs('windows-x64')


def review(target='windows-x64'):
    if target not in windows_sdk.MACHINES:
        raise ValueError('Unsupported Windows target')
    review_name = 'windows-reviewed-toolchain.json' if target == 'windows-x64' else 'windows-reviewed-arm64-toolchain.json'
    result = json.loads((HERE / review_name).read_text())
    if (result.get('status') != 'reviewed-for-compatibility-build' or
            result.get('review', {}).get('buildApproved') is not True or
            result.get('target') != target):
        raise ValueError('Independent Windows build review missing')
    lock = HERE / result['lockFile']
    if prebuilt.sha(lock) != result['lockSha256']:
        raise ValueError('Reviewed Windows lock hash differs')
    return result, lock


def verify_existing(output, target_name='windows-x64'):
    output = Path(output)
    manifest = json.loads((output / 'manifest.json').read_text())
    reviewed, _ = review(target_name)
    if (manifest.get('bridge') != {'abi': 1, 'sourceSha256': link_sdk.source_digest()} or
            manifest.get('target') != target_name or
            manifest.get('toolchainLockSha256') != reviewed['lockSha256']):
        raise ValueError('Cached Windows bridge provenance differs')
    release = json.loads((HERE / 'windows-release-pin.json').read_text())
    if manifest.get('v8') != release['v8'] or manifest.get('depotTools') != release['depotTools']:
        raise ValueError('Cached Windows source versions differ')
    target = manifest['targets'][target_name]
    if target.get('sdkProvenance') != {'target': target_name, 'releaseManifestSha256': release['releaseManifestSha256'], 'manifestSha256': release['sdkManifestSha256'][target_name]}:
        raise ValueError('Cached Windows SDK provenance differs')
    if any(target.get(key) != manifest['binary'][{'binary': 'name'}.get(key, key)] for key in ('binary', 'sha256', 'size')):
        raise ValueError('Cached Windows binary metadata differs')
    for name in windows_sdk.consumer_inputs(target_name):
        if target['windowsConsumerInputsSha256'].get(name) != prebuilt.sha(HERE / name):
            raise ValueError('Cached Windows consumer input changed: ' + name)
    dll = output / 'source_v8.dll'
    if prebuilt.sha(dll) != manifest['binary']['sha256'] or dll.stat().st_size != manifest['binary']['size']:
        raise ValueError('Cached Windows DLL checksum differs')
    windows_sdk.inspect_pe(dll.read_bytes(), target_name)
    return output


def prepare(work, output, *, msvc_root=None, msvc_version_file=None, sdk_root=None,
            image_version='', source_sha='', target='windows-x64'):
    reviewed, lock_file = review(target)
    if os.name != 'nt':
        raise ValueError('Reviewed Windows bridge requires a Windows x64 build host')
    work, output = Path(work).resolve(), Path(output).resolve()
    if output.exists():
        return verify_existing(output, target)
    work.mkdir(parents=True, exist_ok=True)
    clang_root = work / 'clang'
    if not clang_root.exists():
        receipts.fetch_clang(clang_root)
    else:
        if receipts.inventory_digest(receipts.file_inventory(clang_root)) != receipts.clang_pin()['clangArchive']['filesSha256']:
            raise ValueError('Cached official Windows Clang inventory changed')
    # These versioned paths were observed in the independently reviewed CI job.
    # No VS latest/registry/environment discovery is permitted in this consumer.
    vs_root = Path('C:/Program Files/Microsoft Visual Studio/18/Enterprise')
    msvc_root = Path(msvc_root) if msvc_root else vs_root / 'VC/Tools/MSVC' / reviewed['msvcVersion']
    msvc_version_file = Path(msvc_version_file) if msvc_version_file else vs_root / 'VC/Auxiliary/Build/Microsoft.VCToolsVersion.default.txt'
    sdk_root = Path(sdk_root) if sdk_root else Path('C:/Program Files (x86)/Windows Kits/10')
    staged = work / ('toolchain-' + reviewed['lockSha256'])
    if staged.exists():
        windows_sdk.verify_toolchain(staged, lock_file, reviewed['lockSha256'], target)
    else:
        with tempfile.TemporaryDirectory(prefix='candidate-', dir=work) as temporary:
            candidate = Path(temporary) / 'receipt'
            receipts.collect(clang_root, msvc_root, sdk_root, reviewed['msvcVersion'],
                             reviewed['windowsSdkVersion'], target, candidate,
                             image_version, source_sha, msvc_version_file)
            if prebuilt.sha(candidate / 'candidate-lock.json') != reviewed['lockSha256']:
                raise ValueError('Installed Windows toolchain differs from independent review; collect a new candidate for review')
            receipts.stage(candidate, reviewed['lockSha256'], staged)
    return windows_sdk.build(target, output, staged, lock_file,
                             reviewed['lockSha256'], work / 'pure-sdk')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare-dynamic', choices=sorted(windows_sdk.MACHINES))
    parser.add_argument('--work-directory', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--msvc-root', type=Path)
    parser.add_argument('--msvc-version-file', type=Path)
    parser.add_argument('--sdk-root', type=Path)
    parser.add_argument('--image-version', default='')
    parser.add_argument('--source-sha', default='')
    args = parser.parse_args()
    target = args.prepare_dynamic or 'windows-x64'
    identity = hashlib.sha256((target + link_sdk.source_digest() + ''.join(prebuilt.sha(HERE / name) for name in windows_sdk.consumer_inputs(target))).encode()).hexdigest()
    work = args.work_directory or link_sdk.PACKAGE / '.cache/windows-toolchain'
    output = args.output or link_sdk.PACKAGE / '.cache/windows-built' / identity
    path = prepare(work, output, msvc_root=args.msvc_root, msvc_version_file=args.msvc_version_file,
                   sdk_root=args.sdk_root, image_version=args.image_version, source_sha=args.source_sha, target=target)
    print(json.dumps({'artifactRoot': str(path), 'target': target, 'runtimeTested': False}))


if __name__ == '__main__':
    main()
