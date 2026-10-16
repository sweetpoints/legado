#!/usr/bin/env python3
"""Authenticate the real Flutter Release bundle against its pinned V8 consumer.

No V8 source build or release publication. This verifies packaging, not GUI
behavior; the separate native application integration suite verifies behavior.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import struct
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / 'v8'))
import linux_sdk
import prebuilt


def elf64(path, machine, kinds):
    with Path(path).open('rb') as stream:
        header = stream.read(64)
    if (len(header) != 64 or header[:6] != b'\x7fELF\x02\x01' or
            struct.unpack_from('<H', header, 18)[0] != machine or
            struct.unpack_from('<H', header, 16)[0] not in kinds):
        raise ValueError('Linux bundle ELF architecture/type differs: ' + str(path))


def verify(bundle, output):
    bundle = Path(bundle).resolve()
    if not bundle.is_dir():
        raise ValueError('Built Linux Release bundle is missing')
    for candidate in bundle.rglob('*'):
        if candidate.is_symlink() and not candidate.resolve().is_relative_to(bundle):
            raise ValueError('Linux bundle contains a symlink outside its root')
    executable = bundle / 'legado_app'
    elf64(executable, 62, {2, 3})
    if not os.access(executable, os.X_OK):
        raise ValueError('Linux application does not have executable permission')
    # libapp.so is Flutter's AOT output. A debug-only bundle lacks this file.
    elf64(bundle / 'lib/libapp.so', 62, {3})
    matches = list(bundle.rglob('libsource_v8.so'))
    if len(matches) != 1:
        raise ValueError('Linux bundle must contain exactly one V8 bridge')
    binary = matches[0]
    elf64(binary, 62, {3})
    root = linux_sdk.prepare_dynamic('linux-x64')
    manifest = json.loads((root / 'manifest.json').read_text())
    entry = manifest['targets']['linux-x64']
    if prebuilt.sha(binary) != entry['sha256'] or binary.stat().st_size != entry['size']:
        raise ValueError('Bundled V8 differs from the authenticated native-hook artifact')
    pins = json.loads((HERE / 'v8/linux-pins.json').read_text())
    release = json.loads((HERE / 'v8/linux-release-pin.json').read_text())
    sdk = prebuilt.install(release, 'linux-x64', local_pins=pins)
    sdk_manifest = json.loads((sdk / 'manifest.json').read_text())
    notices = bundle / 'THIRD_PARTY_NOTICES/v8'
    notices.mkdir(parents=True, exist_ok=True)
    if not notices.resolve().is_relative_to(bundle):
        raise ValueError('V8 notices resolve outside the bundle')
    for item in sdk_manifest['licenses']:
        relative = prebuilt._relative(item['path']).relative_to('licenses')
        source = sdk / item['path']
        destination = notices.joinpath(*relative.parts)
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.is_symlink() or not destination.resolve().is_relative_to(bundle):
            raise ValueError('V8 notice destination must not be a symlink')
        shutil.copyfile(source, destination)
        if prebuilt.sha(destination) != item['sha256']:
            raise ValueError('Packaged V8 notice differs from authenticated SDK')
    files = {}
    for file in sorted(bundle.rglob('*')):
        if file.is_symlink():
            if not file.resolve().is_relative_to(bundle):
                raise ValueError('Linux bundle contains a symlink outside its root')
        if file.is_file():
            files[file.relative_to(bundle).as_posix()] = {
                'size': file.stat().st_size, 'sha256': prebuilt.sha(file)}
    receipt = {'schemaVersion': 1, 'target': 'linux-x64',
        'sourceCommit': os.environ.get('GITHUB_SHA'), 'buildMode': 'release-aot',
        'v8BuiltLocally': False, 'v8Version': pins['v8']['version'],
        'v8BridgeSha256': entry['sha256'], 'sdkProvenance': entry['sdkProvenance'],
        'linuxConsumerInputsSha256': entry['linuxConsumerInputsSha256'],
        'linuxUndefinedSymbolsSha256': entry['linuxUndefinedSymbolsSha256'],
        'files': files, 'guiRuntimeAccepted': False}
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({'target': 'linux-x64', 'buildMode': 'release-aot',
                      'v8BridgeSha256': entry['sha256'], 'files': len(files)}))
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    verify(args.bundle, args.output)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        raise SystemExit('Linux application bundle verification failed: ' + str(error))
