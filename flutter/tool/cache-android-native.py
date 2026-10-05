#!/usr/bin/env python3
"""Stage independent verified ABI caches and merge their manifests safely."""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil

spec = importlib.util.spec_from_file_location('aar_verifier', Path(__file__).with_name('verify-android-aar.py'))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def checked_file(root, relative, digest):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or verifier.builder.sha(path) != digest:
        raise ValueError('Native cache file path/checksum mismatch')
    return path


def transfer(source, destination, target):
    manifest, targets = verifier.native_provenance([target], source)
    entry = targets[target]
    if not entry['binary'].startswith(target + '/'):
        raise ValueError('Native cache binary must belong to its target directory')
    old_path = destination / 'manifest.json'
    old = json.loads(old_path.read_text()) if old_path.exists() else None
    if old is not None and any(old.get(field) != manifest[field] for field in ['schemaVersion', 'v8', 'depotTools', 'bridge']):
        raise ValueError('Cannot combine native caches with different source provenance')
    licenses = {entry['path']: entry for entry in (old or {}).get('licenses', [])}
    for item in manifest.get('licenses', []):
        checked_file(source, item['path'], item['sha256'])
        if item['path'] in licenses and licenses[item['path']]['sha256'] != item['sha256']:
            raise ValueError('Native cache license checksum conflict')
        licenses[item['path']] = item
    # Preflight conflicts before copying. Preserve all other target entries.
    combined = {field: manifest[field] for field in ['schemaVersion', 'v8', 'depotTools', 'bridge']}
    combined['targets'] = dict((old or {}).get('targets', {})) | {target: entry}
    combined['licenses'] = [licenses[path] for path in sorted(licenses)]
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source / target, destination / target, dirs_exist_ok=True)
    for item in manifest.get('licenses', []):
        path = checked_file(source, item['path'], item['sha256'])
        output = destination / item['path']
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, output)
    temporary = destination / 'manifest.json.publishing'
    temporary.write_text(json.dumps(combined, indent=2) + '\n')
    temporary.replace(old_path)
    verifier.native_provenance([target], destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['export', 'import'])
    parser.add_argument('--target', choices=list(verifier.ANDROID_ABIS), required=True)
    parser.add_argument('--cache', type=Path, required=True)
    args = parser.parse_args()
    artifact = verifier.builder.PACKAGE / '.cache/self-built' / verifier.builder.read_pins()['v8']['revision']
    source, destination = (artifact, args.cache) if args.action == 'export' else (args.cache, artifact)
    transfer(source, destination, args.target)


if __name__ == '__main__':
    main()
