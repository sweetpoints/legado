"""Offline consumer fixtures: no GitHub access or native build execution."""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('v8_prebuilt', Path(__file__).with_name('prebuilt.py'))
consumer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(consumer)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(data):
    return (json.dumps(data, sort_keys=True) + '\n').encode()


class Fixture:
    def __init__(self, targets=('android-arm64',)):
        self.local = {
            'schemaVersion': 1,
            'v8': {'repository': 'https://chromium.googlesource.com/v8/v8.git', 'revision': 'a' * 40, 'version': '15.4.80.24'},
            'depotTools': {'repository': 'https://chromium.googlesource.com/chromium/tools/depot_tools.git', 'revision': 'b' * 40},
            'targets': {t: {'cpu': 'arm64' if t.endswith('arm64') else 'x64', 'abi': 'arm64-v8a' if t.endswith('arm64') else 'x86_64', 'minApi': 26} for t in targets},
        }
        self.pin = {'schemaVersion': 1, 'repository': 'owner/v8-prebuilt', 'tag': 'v8-15.4.80.24',
                    'v8': self.local['v8'], 'depotTools': self.local['depotTools'],
                    'assets': []}
        self.manifests = {}
        self.payloads = {}
        for target in targets:
            files = {target + '/libv8_monolith.a': b'!<arch>\nfixed V8 archive ' + target.encode(),
                     target + '/args.gn': b'target_cpu = "arm64"\n',
                     target + '/dependencies.txt': b'fixed deps\n', target + '/defines.json': b'[]\n',
                     target + '/include/v8.h': b'// Fixed public headers\n',
                     target + '/linking.json': encoded({'schemaVersion': 1, 'target': target, 'compileFlags': ['-std=c++20'], 'includeDirectories': [target + '/include', target + '/stdlib/include'], 'libraries': [target + '/libv8_monolith.a', target + '/stdlib/libc++.a']}),
                     target + '/stdlib/include/__config': b'// matching libc++ configuration\n',
                     target + '/stdlib/libc++.a': b'!<arch>\nstandard library',
                     target + '/runtime-smoke.json': b'{"passed":true}\n',
                     'licenses/LICENSE': b'Official BSD license\n', 'licenses/AUTHORS': b'Authors\n',
                     'licenses/third_party/example/NOTICE': b'Notice\n'}
            entry = {'artifactKind': 'v8-static-sdk', 'binary': target + '/libv8_monolith.a', 'sha256': digest(files[target + '/libv8_monolith.a']),
                     'size': len(files[target + '/libv8_monolith.a']), 'abi': self.local['targets'][target]['abi'],
                     'minApi': 26, 'gnArgs': files[target + '/args.gn'].decode(),
                     'gnArgsSha256': digest(files[target + '/args.gn']),
                     'dependencyInventorySha256': digest(files[target + '/dependencies.txt']),
                     'definesSha256': digest(files[target + '/defines.json']),
                     'validation': {'built': True, 'runtimeTested': False, 'sourceCompatibilityTested': False}}
            entry['files'] = [{'path': p, 'sha256': digest(data), 'size': len(data)} for p, data in files.items() if p.startswith(target + '/')]
            entry['targetFiles'] = copy.deepcopy(entry['files'])
            manifest = {'schemaVersion': 1, 'v8': self.pin['v8'], 'depotTools': self.pin['depotTools'],
                        'targets': {target: entry},
                        'licenses': [{'path': p, 'sha256': digest(data)} for p, data in files.items() if p.startswith('licenses/')]}
            self.manifests[target] = manifest
            files['manifest.json'] = encoded(manifest)
            files['pins.json'] = encoded(self.local)
            payload = self.archive(files)
            name = 'v8-' + self.pin['v8']['version'] + '-' + target + '.tar.gz'
            self.pin['assets'].append({'name': name, 'target': target, 'sha256': digest(payload), 'size': len(payload)})
            self.payloads[name] = payload
        self.refresh_release()

    @staticmethod
    def archive(files, extra=None):
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode='w:gz') as tar:
            for label, data in files.items():
                member = tarfile.TarInfo(label); member.size = len(data)
                tar.addfile(member, io.BytesIO(data))
            if extra:
                tar.addfile(extra)
        return output.getvalue()

    def refresh_release(self):
        release_manifest = {k: self.pin[k] for k in ('schemaVersion', 'v8', 'depotTools', 'assets')}
        release_manifest['targets'] = {t: m['targets'][t] for t, m in self.manifests.items()}
        release_manifest['licenses'] = {t: m['licenses'] for t, m in self.manifests.items()}
        payload = encoded(release_manifest)
        self.pin['releaseManifestSha256'] = digest(payload)
        self.payloads[consumer.RELEASE_MANIFEST] = payload
        self.release = {'tag_name': self.pin['tag'], 'draft': False, 'prerelease': False, 'assets': []}
        for name, data in self.payloads.items():
            self.release['assets'].append({'name': name, 'state': 'uploaded', 'size': len(data),
                'digest': 'sha256:' + digest(data), 'browser_download_url':
                'https://github.com/' + self.pin['repository'] + '/releases/download/' + self.pin['tag'] + '/' + name})
        self.urls = []

    def fetch(self, url, path, limit):
        self.urls.append(url)
        if '/releases/tags/' in url:
            data = encoded(self.release)
        else:
            data = self.payloads[url.rsplit('/', 1)[1]]
        if len(data) > limit:
            raise ValueError('fixture download limit')
        Path(path).write_bytes(data)

    def install(self, directory, target='android-arm64'):
        return consumer.install(self.pin, target, local_pins=self.local,
                                cache_root=directory, downloader=self.fetch)

    def mutate_archive(self, mutation):
        asset = self.pin['assets'][0]
        files = {}
        with tarfile.open(fileobj=io.BytesIO(self.payloads[asset['name']]), mode='r:gz') as tar:
            for member in tar:
                files[member.name] = tar.extractfile(member).read()
        mutation(files)
        payload = self.archive(files)
        self.payloads[asset['name']] = payload
        asset.update(sha256=digest(payload), size=len(payload))
        self.refresh_release()


class PrebuiltContractTests(unittest.TestCase):
    def test_full_target_config_is_checked_without_redundant_flat_platform_fields(self):
        for mismatch in (False, True):
            f = Fixture()
            def mutate(files):
                manifest = json.loads(files['manifest.json'])
                entry = manifest['targets']['android-arm64']
                entry.pop('abi'); entry.pop('minApi')
                entry['targetConfig'] = copy.deepcopy(f.local['targets']['android-arm64'])
                if mismatch:
                    entry['targetConfig']['minApi'] = 27
                files['manifest.json'] = encoded(manifest)
                f.manifests['android-arm64'] = manifest
            f.mutate_archive(mutate)
            with tempfile.TemporaryDirectory() as directory:
                if mismatch:
                    with self.assertRaises(ValueError):
                        f.install(directory)
                else:
                    self.assertTrue((f.install(directory) / 'manifest.json').is_file())

    def test_exact_tag_verified_download_installs_pure_sdk_manifest_contract(self):
        f = Fixture()
        with tempfile.TemporaryDirectory() as directory:
            root = f.install(directory)
            manifest = json.loads((root / 'manifest.json').read_text())
            self.assertEqual(manifest['v8'], f.local['v8'])
            self.assertNotIn('bridge', manifest)
            self.assertEqual(manifest['targets']['android-arm64']['artifactKind'], 'v8-static-sdk')
            self.assertFalse(manifest['targets']['android-arm64']['validation']['runtimeTested'])
            self.assertEqual(consumer.sha(root / 'android-arm64/libv8_monolith.a'), manifest['targets']['android-arm64']['sha256'])
            self.assertEqual(len(list(root.glob('licenses/**/*NOTICE'))), 1)
            self.assertTrue(f.urls[0].endswith('/releases/tags/v8-15.4.80.24'))
            self.assertFalse(any('/latest' in url for url in f.urls))
            self.assertEqual(json.loads((root / 'pins.json').read_text()), f.local)

    def test_downloaded_metadata_never_overrides_reviewed_archive_or_manifest_hash(self):
        for which in ('archive', 'manifest'):
            f = Fixture()
            name = f.pin['assets'][0]['name'] if which == 'archive' else consumer.RELEASE_MANIFEST
            f.payloads[name] += b'changed'
            # Preserve metadata digest to prove downloaded bytes independently checked.
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError):
                    f.install(directory)
                self.assertFalse((Path(directory) / ('a' * 40) / 'android-arm64').exists())
                self.assertFalse(list(Path(directory).glob('*.lock')))

    def test_wrong_tag_draft_prerelease_asset_origin_or_digest_is_rejected(self):
        mutations = [lambda r: r.update(tag_name='v8-other'), lambda r: r.update(draft=True),
                     lambda r: r.update(prerelease=True),
                     lambda r: r['assets'][0].update(browser_download_url='https://untrusted.invalid/file'),
                     lambda r: r['assets'][0].update(digest='sha256:' + 'd' * 64)]
        for mutate in mutations:
            f = Fixture(); mutate(f.release)
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError): f.install(directory)

    def test_wrong_source_revision_combined_bridge_and_moving_selector_fail_before_network(self):
        for key, value in [('tag', 'latest'), ('bridge', {'abi': 2, 'sourceSha256': 'c' * 64}),
                           ('bridge', {'abi': 1, 'sourceSha256': 'd' * 64}),
                           ('v8', {'version': 'new', 'revision': 'f' * 40}),
                           ('releaseManifestSha256', 'unfixed')]:
            f = Fixture(); f.pin[key] = value
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError): f.install(directory)
            self.assertEqual(f.urls, [])

    def test_corrupt_binary_license_build_metadata_and_unindexed_payload_fail_before_publish(self):
        changes = [lambda files: files.update({'android-arm64/libv8_monolith.a': b'corrupt'}),
                   lambda files: files.update({'licenses/LICENSE': b'corrupt'}),
                   lambda files: files.update({'android-arm64/args.gn': b'corrupt'}),
                   lambda files: files.update({'execute-me': b'not indexed'}),
                   lambda files: files.pop('licenses/AUTHORS')]
        for change in changes:
            f = Fixture(); f.mutate_archive(change)
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError): f.install(directory)
                self.assertFalse((Path(directory) / ('a' * 40) / 'android-arm64').exists())

    def test_archive_rejects_traversal_absolute_links_and_duplicate_names(self):
        with tempfile.TemporaryDirectory() as directory:
            for name, kind in [('../escape', tarfile.REGTYPE), ('/absolute', tarfile.REGTYPE),
                               ('a\\b', tarfile.REGTYPE), ('target/link', tarfile.SYMTYPE),
                               ('target/hard', tarfile.LNKTYPE), ('target/device', tarfile.CHRTYPE), ('target/NUL.txt', tarfile.REGTYPE), ('target/name.', tarfile.REGTYPE)]:
                member = tarfile.TarInfo(name); member.type = kind; member.linkname = '../escape'
                archive = Path(directory) / 'bad.tar.gz'
                archive.write_bytes(Fixture.archive({}, member))
                root = Path(directory) / 'out'; root.mkdir(exist_ok=True)
                with self.assertRaises(ValueError): consumer._extract(archive, root)
            archive.write_bytes(Fixture.archive({'same': b'first'}, tarfile.TarInfo('same')))
            with self.assertRaisesRegex(ValueError, 'Duplicate'): consumer._extract(archive, root)

    def test_sdk_targets_are_isolated_and_leave_existing_app_bridge_cache_unchanged(self):
        f = Fixture(('android-arm64', 'android-x64'))
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / 'source_sdk'
            bridge = Path(directory) / 'self-built' / ('a' * 40)
            bridge.mkdir(parents=True)
            (bridge / 'libsource_v8.so').write_bytes(b'previous App bridge')
            root = f.install(cache)
            old = (root / 'android-arm64/libv8_monolith.a').read_bytes()
            other = f.install(cache, 'android-x64')
            self.assertNotEqual(root, other)
            self.assertEqual((root / 'android-arm64/libv8_monolith.a').read_bytes(), old)
            self.assertEqual(set(json.loads((root / 'manifest.json').read_text())['targets']), {'android-arm64'})
            self.assertEqual(set(json.loads((other / 'manifest.json').read_text())['targets']), {'android-x64'})
            self.assertEqual((bridge / 'libsource_v8.so').read_bytes(), b'previous App bridge')

    def test_sdk_rejects_combined_bridge_headers_artifact_kind_and_thin_archive(self):
        def bridge_header(files):
            files['android-arm64/include/source_v8.h'] = b'App bridge header'
        def combined_kind(files):
            manifest = json.loads(files['manifest.json'])
            manifest['targets']['android-arm64']['artifactKind'] = 'source-v8-bridge'
            files['manifest.json'] = encoded(manifest)
        def bridge_provenance(files):
            manifest = json.loads(files['manifest.json'])
            manifest['bridge'] = {'abi': 1, 'sourceSha256': 'c' * 64}
            files['manifest.json'] = encoded(manifest)
        def thin_archive(files):
            files['android-arm64/libv8_monolith.a'] = b'!<thin>\nexternal objects'
            manifest = json.loads(files['manifest.json'])
            entry = manifest['targets']['android-arm64']
            entry['sha256'] = digest(files[entry['binary']])
            entry['size'] = len(files[entry['binary']])
            files['manifest.json'] = encoded(manifest)
        for change in (bridge_header, combined_kind, bridge_provenance, thin_archive):
            f = Fixture(); f.mutate_archive(change)
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError): f.install(directory)
                self.assertFalse((Path(directory) / ('a' * 40) / 'android-arm64').exists())

    def test_inventory_requires_all_files_and_cannot_disagree_with_source_index(self):
        def without_header(files):
            manifest = json.loads(files['manifest.json'])
            entry = manifest['targets']['android-arm64']
            entry['files'] = [item for item in entry['files'] if not item['path'].endswith('/v8.h')]
            entry['targetFiles'] = copy.deepcopy(entry['files'])
            files['manifest.json'] = encoded(manifest)
        def wrong_alias(files):
            manifest = json.loads(files['manifest.json'])
            manifest['targets']['android-arm64']['targetFiles'] = []
            files['manifest.json'] = encoded(manifest)
        for change in (without_header, wrong_alias):
            f = Fixture(); f.mutate_archive(change)
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError): f.install(directory)
                self.assertFalse((Path(directory) / ('a' * 40) / 'android-arm64').exists())

    def test_failed_new_download_preserves_installed_cache(self):
        f = Fixture(('android-arm64', 'android-x64'))
        with tempfile.TemporaryDirectory() as directory:
            root = f.install(directory)
            old = (root / 'manifest.json').read_bytes()
            f.payloads[f.pin['assets'][1]['name']] = b'corrupt'
            with self.assertRaises(ValueError): f.install(directory, 'android-x64')
            self.assertEqual((root / 'manifest.json').read_bytes(), old)

    def test_existing_conflicting_license_or_symlink_cache_is_never_overwritten(self):
        for symlink in (False, True):
            f = Fixture(('android-arm64', 'android-x64'))
            with tempfile.TemporaryDirectory() as directory:
                root = f.install(directory)
                if symlink:
                    binary = root / 'android-arm64/libv8_monolith.a'; binary.unlink()
                    binary.symlink_to(root / 'licenses/LICENSE')
                else:
                    (root / 'licenses/LICENSE').write_bytes(b'conflict')
                old = (root / 'manifest.json').read_bytes()
                with self.assertRaises(ValueError): f.install(directory)
                self.assertEqual((root / 'manifest.json').read_bytes(), old)

    def test_sdk_refuses_bridge_cache_root_and_symlink_revision(self):
        f = Fixture()
        with tempfile.TemporaryDirectory() as directory:
            bridge = Path(directory) / 'self-built'
            with self.assertRaisesRegex(ValueError, 'separate'): f.install(bridge)
            cache = Path(directory) / 'source_sdk'; cache.mkdir()
            other = Path(directory) / 'other'; other.mkdir()
            (cache / ('a' * 40)).symlink_to(other, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'symlink'): f.install(cache)
            self.assertFalse(list(other.iterdir()))

    def test_lock_refuses_concurrent_install_and_never_removes_other_lock(self):
        f = Fixture()
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / ('.sdk-' + 'a' * 40 + '-android-arm64.lock'); lock.mkdir()
            with self.assertRaisesRegex(ValueError, 'locked'): f.install(directory)
            self.assertTrue(lock.exists())
            self.assertEqual(f.urls, [])


if __name__ == '__main__':
    unittest.main()
