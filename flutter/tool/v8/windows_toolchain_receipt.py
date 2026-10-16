#!/usr/bin/env python3
"""Collect a candidate Windows SDK lock; never approve or alter system installs.

fetch-clang writes a new task-owned directory using the reviewed official archive.
collect only reads explicit roots and writes candidate metadata. stage copies only
reviewed hash-bound files into a new task directory after independent approval.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

import prebuilt
import windows_sdk

HERE = Path(__file__).resolve().parent


def file_inventory(root):
    result = {}
    for path in sorted(Path(root).rglob('*')):
        if path.is_symlink():
            raise ValueError('Toolchain links forbidden')
        if path.is_file():
            relative = path.relative_to(root).as_posix()
            prebuilt._relative(relative)
            result[relative] = prebuilt.sha(path)
    return result


def inventory_digest(files):
    return hashlib.sha256(json.dumps(files, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def clang_pin():
    return json.loads((HERE / 'windows-pins.json').read_text())['windowsConsumerToolchain']


def fetch_clang(output):
    pin = clang_pin()['clangArchive']
    output = Path(output).resolve()
    if output.exists():
        raise ValueError('Clang output must be a new task-owned directory')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.windows-clang-', dir=output.parent) as temporary:
        temporary = Path(temporary)
        archive = temporary / 'clang.tar.xz'
        with urllib.request.urlopen(pin['url'], timeout=60) as incoming, archive.open('xb') as stream:
            size = 0
            while data := incoming.read(1024 * 1024):
                size += len(data)
                if size > pin['size']:
                    raise ValueError('Windows Clang archive exceeds fixed size')
                stream.write(data)
        if size != pin['size'] or prebuilt.sha(archive) != pin['sha256']:
            raise ValueError('Windows Clang archive size/SHA256 differs')
        unpacked = temporary / 'unpacked'
        unpacked.mkdir()
        seen, total = set(), 0
        with tarfile.open(archive, 'r:xz') as source:
            for count, member in enumerate(source, 1):
                if count > 10000:
                    raise ValueError('Too many Windows Clang archive entries')
                name = member.name.rstrip('/') if member.isdir() else member.name
                relative = str(prebuilt._relative(name))
                if relative in seen or not (member.isdir() or member.isfile()):
                    raise ValueError('Duplicate/link/special Windows Clang archive entry')
                seen.add(relative)
                path = unpacked / relative
                if member.isdir():
                    path.mkdir(parents=True, exist_ok=True)
                    continue
                total += member.size
                if member.size < 0 or total > 1024 * 1024 * 1024:
                    raise ValueError('Windows Clang unpacked size exceeds limit')
                path.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(member) as incoming, path.open('xb') as stream:
                    shutil.copyfileobj(incoming, stream)
        if inventory_digest(file_inventory(unpacked)) != pin['filesSha256']:
            raise ValueError('Windows Clang extracted file inventory differs')
        unpacked.rename(output)
    return output


def selected_inventory(root, selections):
    root = Path(root).resolve()
    files = {}
    for relative in selections:
        relative = str(prebuilt._relative(relative))
        directory = root / relative
        if not directory.is_dir() or directory.is_symlink():
            raise ValueError('Required explicit toolchain directory missing: ' + relative)
        for path, digest in file_inventory(directory).items():
            label = relative + '/' + path
            if label in files:
                raise ValueError('Overlapping Windows toolchain selections')
            files[label] = digest
    return files


def collect(clang_root, msvc_root, sdk_root, msvc_version, sdk_version, target, output,
            image_version='', source_sha='', msvc_version_file=None):
    output = Path(output).resolve()
    if output.exists():
        raise ValueError('Receipt output must be a new directory')
    if target not in windows_sdk.MACHINES:
        raise ValueError('Unsupported Windows target')
    for version in (msvc_version, sdk_version):
        if not re.fullmatch(r'[0-9]+(?:\.[0-9]+){2,3}', version):
            raise ValueError('Explicit numeric MSVC/Windows SDK version required')
    if msvc_version_file is None:
        raise ValueError('Explicit MSVC version file required')
    msvc_version_file = Path(msvc_version_file).resolve()
    if msvc_version_file.read_text(encoding='utf-8-sig').strip() != msvc_version:
        raise ValueError('MSVC version file differs from selected version')
    # Roots are explicit arguments: do not search registry, home or environment.
    clang_root, msvc_root, sdk_root = [Path(p).resolve() for p in (clang_root, msvc_root, sdk_root)]
    clang_files = file_inventory(clang_root)
    pinned = clang_pin()
    if inventory_digest(clang_files) != pinned['clangArchive']['filesSha256']:
        raise ValueError('Compiler directory differs from fixed official Windows Clang archive')
    version = subprocess.check_output([str(clang_root / 'bin/clang-cl.exe'), '--version'], text=True).splitlines()[0]
    if version != pinned['clangVersion']:
        raise ValueError('Compiler identity differs from fixed V8 SDK compiler')
    arch = target.split('-')[1]
    msvc_dirs = ['include', 'lib/' + arch]
    sdk_dirs = ['Include/' + sdk_version, 'Lib/' + sdk_version + '/ucrt/' + arch,
                'Lib/' + sdk_version + '/um/' + arch]
    components = {'clang': clang_files, 'msvc': selected_inventory(msvc_root, msvc_dirs),
                  'windows-sdk': selected_inventory(sdk_root, sdk_dirs)}
    for relative in ('include/vcruntime.h', 'lib/' + arch + '/libcmt.lib', 'lib/' + arch + '/libvcruntime.lib'):
        if relative not in components['msvc']:
            raise ValueError('MSVC CRT payload missing: ' + relative)
    for relative in ('Include/' + sdk_version + '/ucrt/stdio.h',
                     'Include/' + sdk_version + '/um/Windows.h',
                     'Lib/' + sdk_version + '/ucrt/' + arch + '/libucrt.lib',
                     'Lib/' + sdk_version + '/um/' + arch + '/kernel32.lib'):
        # Windows header spelling differs in some SDK packaging; inventory keeps
        # actual case and compiler paths remain case-insensitive on Windows.
        if relative.lower() not in {p.lower() for p in components['windows-sdk']}:
            raise ValueError('Windows SDK payload missing: ' + relative)
    files = {prefix + '/' + relative: digest for prefix, values in components.items()
             for relative, digest in values.items()}
    lock = {'schemaVersion': 1, 'target': target, 'clangVersion': version,
            'msvcVersion': msvc_version, 'windowsSdkVersion': sdk_version,
            'compiler': 'clang/bin/clang-cl.exe', 'linker': 'clang/bin/lld-link.exe',
            'msvcRoot': 'msvc', 'windowsSdkRoot': 'windows-sdk',
            'includeDirs': ['windows-sdk/Include/' + sdk_version + '/' + name
                            for name in ('ucrt', 'shared', 'um')],
            'libraryDirs': ['msvc/lib/' + arch, 'windows-sdk/Lib/' + sdk_version + '/ucrt/' + arch,
                            'windows-sdk/Lib/' + sdk_version + '/um/' + arch],
            'files': dict(sorted(files.items()))}
    # MSVC headers supply CRT declarations. The bridge command searches the
    # SDK's pinned Chromium libc++ /I directories before these /imsvc directories.
    lock['includeDirs'].insert(0, 'msvc/include')
    lock_bytes = (json.dumps(lock, indent=2) + '\n').encode()
    receipt = {'schemaVersion': 1, 'status': 'candidate-requires-independent-review',
               'target': target, 'imageVersion': image_version, 'sourceSha': source_sha,
               'lockSha256': hashlib.sha256(lock_bytes).hexdigest(),
               'compilerArchive': pinned['clangArchive'],
               'roots': {'clang': str(clang_root), 'msvc': str(msvc_root), 'windows-sdk': str(sdk_root)},
               'fileCounts': {k: len(v) for k, v in components.items()},
               'msvcVersionFile': {'path': str(msvc_version_file), 'sha256': prebuilt.sha(msvc_version_file),
                                   'value': msvc_version},
               'validation': {'bridgeBuilt': False, 'runtimeTested': False, 'approved': False}}
    output.mkdir(parents=True)
    (output / 'candidate-lock.json').write_bytes(lock_bytes)
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def stage(receipt_dir, expected_sha, output):
    """Copy only independently reviewed files, never edit source installations."""
    receipt_dir, output = Path(receipt_dir), Path(output).resolve()
    if output.exists():
        raise ValueError('Staged toolchain output must be a new directory')
    lock_path = receipt_dir / 'candidate-lock.json'
    if prebuilt.sha(lock_path) != prebuilt._digest(expected_sha):
        raise ValueError('Reviewed candidate lock SHA256 differs')
    receipt = json.loads((receipt_dir / 'receipt.json').read_text())
    if receipt.get('lockSha256') != expected_sha:
        raise ValueError('Receipt lock identity differs')
    lock = json.loads(lock_path.read_text())
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.windows-stage-', dir=output.parent) as temporary:
        staged = Path(temporary) / 'toolchain'
        staged.mkdir()
        for relative, expected in lock['files'].items():
            relative = prebuilt._relative(relative)
            prefix, *parts = relative.parts
            if prefix not in ('clang', 'msvc', 'windows-sdk'):
                raise ValueError('Unknown toolchain component')
            root = Path(receipt['roots'][prefix]).resolve()
            source = root.joinpath(*parts)
            if source.is_symlink() or not source.resolve().is_relative_to(root) or prebuilt.sha(source) != expected:
                raise ValueError('Reviewed toolchain source file changed')
            target = staged.joinpath(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        windows_sdk.verify_toolchain(staged, lock_path, expected_sha, lock['target'])
        staged.rename(output)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    fetch = commands.add_parser('fetch-clang')
    fetch.add_argument('--output', type=Path, required=True)
    candidate = commands.add_parser('collect')
    for name in ('clang-root', 'msvc-root', 'sdk-root', 'msvc-version', 'msvc-version-file', 'sdk-version', 'output'):
        candidate.add_argument('--' + name, required=True)
    candidate.add_argument('--target', choices=sorted(windows_sdk.MACHINES), required=True)
    candidate.add_argument('--image-version', default='')
    candidate.add_argument('--source-sha', default='')
    materialize = commands.add_parser('stage')
    materialize.add_argument('--receipt-dir', type=Path, required=True)
    materialize.add_argument('--expected-lock-sha256', required=True)
    materialize.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'fetch-clang':
        print(fetch_clang(args.output))
    elif args.command == 'stage':
        print(stage(args.receipt_dir, args.expected_lock_sha256, args.output))
    else:
        print(json.dumps(collect(args.clang_root, args.msvc_root, args.sdk_root, args.msvc_version,
                                 args.sdk_version, args.target, args.output,
                                 args.image_version, args.source_sha, args.msvc_version_file), indent=2))


if __name__ == '__main__':
    main()
