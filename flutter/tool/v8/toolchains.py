#!/usr/bin/env python3
"""Download only SHA-pinned official Clang binaries; never bootstrap V8 sources."""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent
PACKAGE = HERE.parents[2] / 'flutter/packages/source_v8'


def host_key():
    system, cpu = platform.system(), platform.machine().lower()
    if system == 'Darwin' and cpu in ('arm64', 'aarch64'):
        return 'macos-arm64'
    if system == 'Linux' and cpu in ('x86_64', 'amd64'):
        return 'linux-x64'
    raise ValueError('Bridge compiler requires macOS ARM64 or Linux x64 host')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def inventory(root):
    return {str(p.relative_to(root)): digest(p) for p in sorted(root.rglob('*'))
            if p.is_file() and p.name != 'toolchain-receipt.json'}


def verify(root, item):
    if root.is_symlink() or (root / 'toolchain-receipt.json').is_symlink():
        raise ValueError('Toolchain root/receipt must not be symbolic links')
    for p in root.rglob('*'):
        if p.is_symlink() and not p.resolve().is_relative_to(root.resolve()):
            raise ValueError('Clang symlink escapes verified toolchain')
    receipt = json.loads((root / 'toolchain-receipt.json').read_text())
    files = inventory(root)
    archive_keys = ('url', 'sha256', 'size')
    if ({k: receipt['archive'][k] for k in archive_keys} !=
            {k: item[k] for k in archive_keys} or receipt['files'] != files):
        raise ValueError('Official Clang cache differs from pinned archive')
    file_hash = hashlib.sha256(json.dumps(files, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    if file_hash != item['filesSha256']:
        raise ValueError('Official Clang file inventory differs from reviewed pin')
    if not (root / 'bin/clang++').is_file() or not (root / 'bin/ld.lld').is_file():
        raise ValueError('Official Clang package missing required tools')
    return root


def install(cache_root=None):
    pins = json.loads((HERE / 'toolchain-pins.json').read_text())
    key = host_key(); item = pins['hosts'][key]
    cache = Path(cache_root) if cache_root else PACKAGE / '.cache/toolchains'
    cache.mkdir(parents=True, exist_ok=True)
    root = cache / (key + '-' + item['sha256'])
    if root.exists():
        return verify(root, item)
    if not item['url'].startswith('https://commondatastorage.googleapis.com/chromium-browser-clang/'):
        raise ValueError('Only fixed official Chromium Clang downloads permitted')
    with tempfile.TemporaryDirectory(prefix='.clang-', dir=cache) as temp:
        work = Path(temp); archive = work / 'clang.tar.xz'
        h = hashlib.sha256(); size = 0
        with urllib.request.urlopen(item['url'], timeout=60) as response, archive.open('wb') as output:
            if not response.geturl().startswith('https://commondatastorage.googleapis.com/chromium-browser-clang/'):
                raise ValueError('Unexpected official Clang download redirect')
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > item['size']:
                    raise ValueError('Official Clang archive exceeds pinned size')
                h.update(chunk); output.write(chunk)
        if size != item['size'] or h.hexdigest() != item['sha256']:
            raise ValueError('Official Clang archive SHA/size mismatch')
        incoming = work / 'incoming'; incoming.mkdir()
        with tarfile.open(archive, 'r:xz') as tar:
            members = tar.getmembers()
            if len(members) > 20000 or sum(m.size for m in members) > 2 * 1024**3:
                raise ValueError('Official Clang unpacked size exceeds limit')
            tar.extractall(incoming, filter='data')
        receipt = {'archive': item, 'files': inventory(incoming)}
        (incoming / 'toolchain-receipt.json').write_text(json.dumps(receipt, sort_keys=True))
        verify(incoming, item)
        os.replace(incoming, root)
    return root


def sysroot(target):
    if target == 'macos-arm64':
        return Path(subprocess.check_output(['xcrun', '--sdk', 'macosx', '--show-sdk-path'], text=True).strip())
    pins = json.loads((HERE / 'toolchain-pins.json').read_text())
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    if not sdk:
        default = Path.home() / 'Library/Android/sdk'
        if default.is_dir(): sdk = str(default)
    if not sdk:
        raise ValueError('ANDROID_SDK_ROOT/ANDROID_HOME with pinned NDK required')
    ndk = Path(sdk) / 'ndk' / pins['androidNdkVersion']
    properties = (ndk / 'source.properties').read_text()
    if 'Pkg.Revision = ' + pins['androidNdkVersion'] not in properties:
        raise ValueError('Pinned Android NDK revision differs')
    prebuilt = 'darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64'
    result = ndk / 'toolchains/llvm/prebuilt' / prebuilt / 'sysroot'
    if not result.is_dir(): raise ValueError('Pinned NDK sysroot missing')
    return result


def symbol_inspector(target, root):
    if target == 'macos-arm64':
        return Path(subprocess.check_output(['xcrun', '--find', 'nm'], text=True).strip())
    candidate = root.parent / 'bin/llvm-nm'
    if not candidate.is_file(): raise ValueError('Pinned NDK symbol inspector missing')
    return candidate
