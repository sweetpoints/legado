import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('platform_readiness', Path(__file__).with_name('platform_readiness.py'))
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class ReadinessTests(unittest.TestCase):
    def fixture(self):
        identity = {'revision': 'a' * 40, 'version': '15.4.80.25'}
        manifest = {'v8': identity, 'depotTools': {'revision': 'b' * 40},
                    'assets': [{'name': 'pins.json', 'sha256': 'e' * 64, 'size': 1}], 'targets': {}}
        for target in module.REQUIRED_TARGETS:
            manifest['assets'].append({'target': target, 'name': target + '.tar.gz', 'sha256': 'c' * 64, 'size': 1})
            manifest['targets'][target] = {'targetConfig': {'cpu': target.split('-')[-1]},
                                          'validation': {'runtimeTested': True}}
            if target.startswith('ios-'):
                manifest['targets'][target]['gnArgs'] = '\n'.join((
                    'v8_jitless = true', 'v8_enable_webassembly = false',
                    'v8_enable_turbofan = false', 'v8_enable_maglev = false',
                    'v8_enable_sparkplug = false')) + '\n'
        pin = {'v8': identity, 'depotTools': manifest['depotTools'],
               'assets': [dict(manifest['assets'][1])],
               'sdkManifestSha256': {'android-arm64': 'd' * 64}}
        local = {'v8': identity, 'depotTools': manifest['depotTools'],
                 'targets': {'android-arm64': {'cpu': 'arm64'}}}
        return manifest, pin, local

    def run_audit(self, manifest, pin, local):
        data = json.dumps(manifest).encode()
        pin = dict(pin, releaseManifestSha256=hashlib.sha256(data).hexdigest())
        return module.audit(data, pin, local)

    def test_sdk_runtime_proof_is_not_application_support(self):
        result = self.run_audit(*self.fixture())
        self.assertEqual(len(result['targets']), 10)
        self.assertTrue(all(row['sdkPublished'] for row in result['targets']))
        self.assertTrue(all(row['sdkRuntimeTested'] for row in result['targets']))
        self.assertFalse(any(row['applicationRuntimeAccepted'] for row in result['targets']))
        self.assertFalse(result['allConsumersConfigured'])
        self.assertFalse(result['allApplicationPlatformsAccepted'])

    def test_changed_manifest_fails_before_reading_targets(self):
        manifest, pin, local = self.fixture()
        pin['releaseManifestSha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'SHA256'):
            module.audit(json.dumps(manifest).encode(), pin, local)

    def test_asset_source_and_platform_pin_disagreement_fail(self):
        for mutate in (
            lambda m, p, l: p['assets'][0].update(size=2),
            lambda m, p, l: l['targets']['android-arm64'].update(cpu='x64'),
            lambda m, p, l: l.update(v8={'revision': 'wrong'}),
            lambda m, p, l: p.update(sdkManifestSha256={}),
        ):
            m, p, local = copy.deepcopy(self.fixture())
            mutate(m, p, local)
            with self.assertRaises(ValueError):
                self.run_audit(m, p, local)

    def test_ios_feature_regression_does_not_report_available(self):
        m, p, local = self.fixture()
        m['targets']['ios-arm64']['gnArgs'] = 'v8_jitless = false\n'
        with self.assertRaisesRegex(ValueError, 'jitless'):
            self.run_audit(m, p, local)

    def test_duplicate_sdk_target_is_rejected(self):
        m, p, local = self.fixture()
        m['assets'].append(dict(m['assets'][1]))
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.run_audit(m, p, local)
