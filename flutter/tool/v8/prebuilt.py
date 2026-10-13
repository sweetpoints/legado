#!/usr/bin/env python3
"""Install reviewed, SHA-pinned pure V8 SDK Release archives into an SDK cache.

This command never discovers a latest version or updates application pins.
The release pin is an explicit reviewed input, independent of downloaded data.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tarfile
import tempfile
import urllib.parse
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PACKAGE = ROOT / 'flutter/packages/source_v8'
RELEASE_MANIFEST = 'release-manifest.json'
MAX_METADATA_BYTES = 8 * 1024 * 1024
MAX_UNPACKED_BYTES = 2 * 1024 * 1024 * 1024
MAX_MEMBERS = 10000


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def _digest(value):
    if not isinstance(value, str) or not re.fullmatch('[0-9a-f]{64}', value):
        raise ValueError('Expected a fixed lowercase SHA256 digest')
    return value


def _positive_size(value):
    if type(value) is not int or value <= 0:
        raise ValueError('Artifact size must be a positive integer')
    return value


def _relative(value):
    if not isinstance(value, str) or not value or '\\' in value or '\x00' in value:
        raise ValueError('Artifact path must be a safe relative POSIX path')
    parts = value.split('/')
    reserved = {'CON', 'PRN', 'AUX', 'NUL'} | {f'{prefix}{n}' for prefix in ('COM', 'LPT') for n in range(1, 10)}
    if any(p in ('', '.', '..') or ':' in p or p.rstrip(' .') != p or
           p.split('.')[0].upper() in reserved or any(ord(c) < 32 for c in p)
           for p in parts) or value.startswith('/'):
        raise ValueError('Artifact path must be a safe relative POSIX path')
    return PurePosixPath(value)


def _object(value, label):
    if not isinstance(value, dict):
        raise ValueError(label + ' must be a JSON object')
    return value


def validate_pin(pin, local_pins):
    _object(pin, 'Release pin')
    if type(pin.get('schemaVersion')) is not int or pin['schemaVersion'] != 1:
        raise ValueError('Unsupported release pin schema')
    repository = pin.get('repository')
    if not isinstance(repository, str) or not re.fullmatch(
            r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Release repository must be owner/repository')
    tag = pin.get('tag')
    if not isinstance(tag, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.+-]*', tag):
        raise ValueError('Exact release tag required')
    if tag.lower() in ('latest', 'stable', 'main', 'master'):
        raise ValueError('Moving release selectors are forbidden')
    _digest(pin.get('releaseManifestSha256'))
    official = {'v8': 'https://chromium.googlesource.com/v8/v8.git',
                'depotTools': 'https://chromium.googlesource.com/chromium/tools/depot_tools.git'}
    for key in ('v8', 'depotTools'):
        source = _object(pin.get(key), key)
        if source != local_pins.get(key):
            raise ValueError('Release ' + key + ' differs from application pins')
        if source.get('repository') != official[key] or not re.fullmatch(
                r'[0-9a-f]{40}', source.get('revision', '')):
            raise ValueError('Official source repository and complete fixed revision required')
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+){2,3}', pin['v8'].get('version', '')):
        raise ValueError('Fixed V8 source version required')
    if 'bridge' in pin:
        raise ValueError('Pure SDK release pins must not include a Legado bridge')
    assets = pin.get('assets')
    if not isinstance(assets, list) or not assets:
        raise ValueError('Reviewed release assets required')
    names, targets = set(), set()
    for asset in assets:
        _object(asset, 'Asset pin')
        name = asset.get('name')
        _relative(name)
        if '/' in name or not name.endswith('.tar.gz'):
            raise ValueError('Expected a root tar.gz release asset')
        target = asset.get('target')
        if not isinstance(target, str) or not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)+', target):
            raise ValueError('SDK target must be a fixed platform identifier')
        if target not in local_pins.get('targets', {}):
            raise ValueError('Asset target is not supported by application pins')
        if name in names or target in targets:
            raise ValueError('Duplicate pinned asset/target')
        names.add(name)
        targets.add(target)
        _digest(asset.get('sha256'))
        _positive_size(asset.get('size'))
    return pin


RELEASE_HOSTS = {'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'}


def validate_download_url(url, *, initial=False):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != 'https' or parsed.hostname not in RELEASE_HOSTS
            or parsed.username is not None or parsed.password is not None
            or parsed.port not in (None, 443) or parsed.fragment):
        raise ValueError('Only trusted HTTPS public GitHub release downloads permitted')
    if initial and (parsed.hostname != 'github.com' or parsed.query or
                    not re.fullmatch(r'/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[^/]+/[^/]+', parsed.path)):
        raise ValueError('Initial SDK URL must identify an exact public release asset')


class PublicReleaseRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        validate_download_url(new_url)
        redirected = super().redirect_request(request, response, code, message, headers, new_url)
        if redirected is not None:
            redirected.remove_header('Authorization')
            redirected.remove_header('Cookie')
        return redirected


def download(url, destination, limit):
    """Bounded public assets only; no API request, token lookup or credentials."""
    validate_download_url(url, initial=True)
    request = urllib.request.Request(url, headers={
        'Accept': 'application/octet-stream',
        'User-Agent': 'legado-v8-pinned-consumer',
    })
    with urllib.request.build_opener(PublicReleaseRedirect()).open(request, timeout=60) as response:
        validate_download_url(response.geturl())
        size = 0
        with Path(destination).open('wb') as output:
            while True:
                data = response.read(min(1024 * 1024, limit - size + 1))
                if not data:
                    break
                size += len(data)
                if size > limit:
                    raise ValueError('Download exceeds pinned/metadata size limit')
                output.write(data)


def _download_checked(downloader, url, path, digest, size=None):
    downloader(url, path, size if size is not None else MAX_METADATA_BYTES)
    if size is not None and path.stat().st_size != size:
        raise ValueError('Release asset size mismatch')
    if sha(path) != _digest(digest):
        raise ValueError('Release asset SHA256 mismatch')


def release_asset_url(pin, name):
    _relative(name)
    if '/' in name:
        raise ValueError('Release asset name must not contain a directory')
    url = ('https://github.com/' + pin['repository'] + '/releases/download/' +
           urllib.parse.quote(pin['tag'], safe='') + '/' + urllib.parse.quote(name, safe=''))
    validate_download_url(url, initial=True)
    return url


def _extract(archive, root):
    """Never use extractall: reject traversal, links, duplicates and special files."""
    total, seen = 0, set()
    with tarfile.open(archive, 'r:gz') as source:
        for count, member in enumerate(source, 1):
            if count > MAX_MEMBERS:
                raise ValueError('Too many archive members')
            label = member.name.rstrip('/') if member.isdir() else member.name
            path = _relative(label)
            if label in seen:
                raise ValueError('Duplicate archive path')
            seen.add(label)
            if not member.isfile() and not member.isdir():
                raise ValueError('Archive links and special files are forbidden')
            target = root.joinpath(*path.parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            total += member.size
            if member.size < 0 or total > MAX_UNPACKED_BYTES:
                raise ValueError('Archive unpacked size exceeds limit')
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.extractfile(member) as incoming, target.open('xb') as output:
                shutil.copyfileobj(incoming, output)
            target.chmod(0o644)


def _provenance(manifest, pin):
    if type(manifest.get('schemaVersion')) is not int or manifest['schemaVersion'] != 1:
        raise ValueError('Unsupported native manifest schema')
    for key in ('v8', 'depotTools'):
        if manifest.get(key) != pin[key]:
            raise ValueError('SDK manifest ' + key + ' provenance mismatch')
    if 'bridge' in manifest:
        raise ValueError('Pure SDK manifests must not include a Legado bridge')


def validate_sdk(root, manifest, pin, local_pins, only_target=None):
    _provenance(manifest, pin)
    targets = _object(manifest.get('targets'), 'SDK targets')
    if not only_target or set(targets) != {only_target}:
        raise ValueError('Archive must contain exactly its pinned target')
    expected_files = {'manifest.json', 'pins.json'}
    for name, entry in targets.items():
        _object(entry, 'SDK target')
        if entry.get('artifactKind') != 'v8-static-sdk':
            raise ValueError('Pure V8 static SDK artifact required')
        config = local_pins.get('targets', {}).get(name)
        if config is None:
            raise ValueError('SDK target unsupported by application pins')
        binary = str(_relative(entry.get('binary')))
        if PurePosixPath(binary).parts[0] != name or PurePosixPath(binary).suffix not in ('.a', '.lib'):
            raise ValueError('SDK monolith archive must reside in its target directory')
        if 'targetConfig' in entry and entry['targetConfig'] != config:
            raise ValueError('SDK target configuration differs from application pins')
        for field in ('abi', 'minApi', 'minMacOS', 'minIOS'):
            if field in config and (field in entry or 'targetConfig' not in entry) and entry.get(field) != config[field]:
                raise ValueError('SDK target platform contract differs from pins')
        size = _positive_size(entry.get('size'))
        path = root / binary
        if not path.is_file() or path.is_symlink() or path.stat().st_size != size or sha(path) != _digest(entry.get('sha256')):
            raise ValueError('SDK monolith checksum/size mismatch')
        with path.open('rb') as library:
            if library.read(8) != b'!<arch>\n':
                raise ValueError('SDK library must be a self-contained static archive')
        if entry.get('validation', {}).get('built') is not True:
            raise ValueError('SDK artifact is not marked built')
        expected_files.add(binary)
        for basename, field in (('args.gn', 'gnArgsSha256'), ('dependencies.txt', 'dependencyInventorySha256'), ('defines.json', 'definesSha256')):
            label = name + '/' + basename
            metadata = root / label
            if not metadata.is_file() or sha(metadata) != _digest(entry.get(field)):
                raise ValueError('SDK build metadata checksum mismatch')
            expected_files.add(label)
        if (root / name / 'args.gn').read_text() != entry.get('gnArgs'):
            raise ValueError('SDK GN arguments mismatch')
        inventory = entry.get('files', entry.get('targetFiles'))
        if inventory is None:
            raise ValueError('Release target file checksum inventory required')
        if 'files' in entry and 'targetFiles' in entry and entry['files'] != entry['targetFiles']:
            raise ValueError('Release/source target inventory mismatch')
        if inventory is not None:
            if not isinstance(inventory, list) or not inventory:
                raise ValueError('Target file inventory must be non-empty')
            indexed_target = set()
            for item in inventory:
                _object(item, 'Target file')
                label = str(_relative(item.get('path')))
                if not label.startswith(name + '/') or label in indexed_target:
                    raise ValueError('Invalid/duplicate target inventory path')
                indexed_target.add(label)
                file = root / label
                if not file.is_file() or file.is_symlink() or file.stat().st_size != _positive_size(item.get('size')) or sha(file) != _digest(item.get('sha256')):
                    raise ValueError('Target file inventory checksum/size mismatch')
            if not {binary, name + '/args.gn', name + '/dependencies.txt', name + '/defines.json',
                    name + '/include/v8.h', name + '/linking.json'}.issubset(indexed_target):
                raise ValueError('SDK inventory must include monolith, V8 headers, linking and build metadata')
            if any(PurePosixPath(label).name in ('source_v8.h', 'source_v8.cpp', 'android_exports.map') for label in indexed_target):
                raise ValueError('Legado bridge files are not part of a pure V8 SDK')
            _object(json.loads((root / name / 'linking.json').read_text()), 'SDK linking metadata')
            expected_files.update(indexed_target)
    licenses = manifest.get('licenses')
    if not isinstance(licenses, list) or not licenses:
        raise ValueError('SDK licenses index required')
    indexed = set()
    for entry in licenses:
        _object(entry, 'License')
        label = str(_relative(entry.get('path')))
        if not label.startswith('licenses/') or label in indexed:
            raise ValueError('Invalid/duplicate license path')
        indexed.add(label)
        if not (root / label).is_file() or sha(root / label) != _digest(entry.get('sha256')):
            raise ValueError('License checksum mismatch')
    if not {'licenses/LICENSE', 'licenses/AUTHORS'}.issubset(indexed):
        raise ValueError('Official V8 LICENSE/AUTHORS required')
    expected_files.update(indexed)
    actual = set()
    for path in root.rglob('*'):
        if path.is_symlink():
            raise ValueError('SDK cache symlinks forbidden')
        if path.is_file():
            actual.add(path.relative_to(root).as_posix())
    if actual != expected_files:
        raise ValueError('SDK archive/cache contains unindexed or missing files')
    return targets


def install(pin, target, *, local_pins=None,
            cache_root=None, downloader=download):
    local_pins = local_pins or json.loads((HERE / 'pins.json').read_text())
    validate_pin(pin, local_pins)
    matches = [a for a in pin['assets'] if a['target'] == target]
    if len(matches) != 1:
        raise ValueError('Target absent from reviewed release pin')
    asset = matches[0]
    cache_root = Path(cache_root) if cache_root else PACKAGE / '.cache/source_sdk'
    if 'self-built' in cache_root.parts:
        raise ValueError('SDK cache must be separate from the App bridge cache')
    if cache_root.is_symlink():
        raise ValueError('Cache root symlinks forbidden')
    cache_root.mkdir(parents=True, exist_ok=True)
    # One immutable Release manifest identifies one SDK build, independently of
    # its V8 source revision. Leave previous SDK generations untouched.
    identity = _digest(pin['releaseManifestSha256'])
    revision_root = cache_root / pin['v8']['revision']
    target_root = revision_root / target
    if revision_root.is_symlink() or target_root.is_symlink():
        raise ValueError('SDK revision/target directory symlink forbidden')
    target_root.mkdir(parents=True, exist_ok=True)
    destination = target_root / identity
    expected_manifest = pin.get('sdkManifestSha256', {}).get(target)
    if expected_manifest is not None:
        _digest(expected_manifest)
        if destination.exists():
            if destination.is_symlink() or sha(destination / 'manifest.json') != expected_manifest:
                raise ValueError('Cached SDK manifest differs from reviewed release')
            if pin.get('sdkPinsSha256') is not None and sha(destination / 'pins.json') != _digest(pin['sdkPinsSha256']):
                raise ValueError('Cached SDK pins differ from reviewed release')
            cached = json.loads((destination / 'manifest.json').read_text())
            validate_sdk(destination, cached, pin, local_pins, target)
            return destination
    lock = cache_root / ('.sdk-' + pin['v8']['revision'] + '-' + target + '-' + identity + '.lock')
    try:
        lock.mkdir()
    except FileExistsError as error:
        raise ValueError('SDK cache installation already locked') from error
    try:
        with tempfile.TemporaryDirectory(prefix='.sdk-', dir=cache_root) as directory:
            work = Path(directory)
            release_manifest_path = work / RELEASE_MANIFEST
            url = release_asset_url(pin, RELEASE_MANIFEST)
            _download_checked(downloader, url, release_manifest_path, pin['releaseManifestSha256'])
            release_manifest = _object(json.loads(release_manifest_path.read_text()), 'Release manifest')
            _provenance(release_manifest, pin)
            advertised = [a for a in release_manifest.get('assets', []) if a.get('target') == target]
            if advertised != [asset]:
                raise ValueError('Release manifest asset differs from reviewed pin')
            url = release_asset_url(pin, asset['name'])
            archive = work / 'artifact.tar.gz'
            _download_checked(downloader, url, archive, asset['sha256'], asset['size'])
            incoming = work / 'incoming'; incoming.mkdir()
            _extract(archive, incoming)
            if expected_manifest is not None and sha(incoming / 'manifest.json') != expected_manifest:
                raise ValueError('SDK manifest differs from reviewed release digest')
            manifest = _object(json.loads((incoming / 'manifest.json').read_text()), 'SDK manifest')
            if pin.get('sdkPinsSha256') is not None and sha(incoming / 'pins.json') != _digest(pin['sdkPinsSha256']):
                raise ValueError('SDK pins differ from reviewed release digest')
            archive_pins = _object(json.loads((incoming / 'pins.json').read_text()), 'Archive pins')
            for key in ('v8', 'depotTools'):
                if archive_pins.get(key) != local_pins[key]:
                    raise ValueError('Archive source pins mismatch')
            if archive_pins.get('targets', {}).get(target) != local_pins['targets'][target]:
                raise ValueError('Archive target pins mismatch')
            validate_sdk(incoming, manifest, pin, local_pins, target)
            if release_manifest.get('targets', {}).get(target) != manifest['targets'][target]:
                raise ValueError('Release/SDK target provenance mismatch')
            if _object(release_manifest.get('licenses'), 'Release license index').get(target) != manifest['licenses']:
                raise ValueError('Release/SDK license index mismatch')
            # SDK targets are isolated: platform-specific standard libraries and
            # licenses must never overwrite another SDK or a built App bridge.
            if destination.exists():
                if destination.is_symlink():
                    raise ValueError('SDK cache destination symlink forbidden')
                previous = _object(json.loads((destination / 'manifest.json').read_text()), 'Existing SDK manifest')
                validate_sdk(destination, previous, pin, local_pins, target)
            merged = incoming
            backup = work / 'previous'
            if destination.exists():
                os.replace(destination, backup)
            try:
                os.replace(merged, destination)
            except OSError:
                if backup.exists():
                    os.replace(backup, destination)
                raise
            return destination
    finally:
        lock.rmdir()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pin', required=True, type=Path, help='Reviewed immutable pure V8 SDK Release pin JSON')
    parser.add_argument('--target', required=True)
    parser.add_argument('--cache-root', type=Path)
    args = parser.parse_args()
    installed = install(json.loads(args.pin.read_text()), args.target, cache_root=args.cache_root)
    print(json.dumps({'sdkRoot': str(installed), 'target': args.target, 'verifiedChecksums': True}))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, tarfile.TarError) as error:
        raise SystemExit('Pinned V8 SDK installation failed: ' + str(error))
