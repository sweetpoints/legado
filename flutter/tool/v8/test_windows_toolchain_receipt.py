import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prebuilt
import windows_toolchain_receipt as receipt


class ReceiptTests(unittest.TestCase):
    def fixture(self, base):
        roots = {key: Path(base) / key for key in ('clang', 'msvc', 'sdk')}
        payloads = {'clang': ('bin/clang-cl.exe', 'bin/lld-link.exe', 'lib/clang/24/include/stddef.h'),
                    'msvc': ('include/vcruntime.h', 'lib/x64/libcmt.lib', 'lib/x64/libvcruntime.lib'),
                    'sdk': ('Include/10.0.28000.0/ucrt/stdio.h', 'Include/10.0.28000.0/um/Windows.h',
                            'Include/10.0.28000.0/shared/shared.h',
                            'Lib/10.0.28000.0/ucrt/x64/libucrt.lib', 'Lib/10.0.28000.0/um/x64/kernel32.lib')}
        for key, files in payloads.items():
            for name in files:
                path = roots[key] / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((key + ':' + name).encode())
        (roots['msvc'] / 'version.txt').write_text('14.51.36231\n')
        pin = {'clangVersion': 'clang version 24-test',
               'clangArchive': {'filesSha256': receipt.inventory_digest(receipt.file_inventory(roots['clang']))}}
        return roots, pin

    def collect(self, base, roots, pin):
        destination = Path(base) / 'candidate'
        with patch.object(receipt, 'clang_pin', return_value=pin), \
                patch.object(receipt.subprocess, 'check_output', return_value=pin['clangVersion'] + '\n'):
            result = receipt.collect(roots['clang'], roots['msvc'], roots['sdk'], '14.51.36231',
                                     '10.0.28000.0', 'windows-x64', destination, 'explicit-image', 'reviewed-sha', roots['msvc'] / 'version.txt')
        return destination, result

    def test_collect_is_candidate_only_and_does_not_mutate_system_roots(self):
        with tempfile.TemporaryDirectory() as base:
            roots, pin = self.fixture(base)
            before = {key: receipt.file_inventory(root) for key, root in roots.items()}
            directory, result = self.collect(base, roots, pin)
            after = {key: receipt.file_inventory(root) for key, root in roots.items()}
            self.assertEqual(before, after)
            self.assertFalse(result['validation']['approved'])
            self.assertFalse(result['validation']['bridgeBuilt'])
            self.assertEqual(result['lockSha256'], prebuilt.sha(directory / 'candidate-lock.json'))
            lock = json.loads((directory / 'candidate-lock.json').read_text())
            self.assertEqual(lock['windowsSdkVersion'], '10.0.28000.0')
            self.assertIn('windows-sdk/Lib/10.0.28000.0/um/x64/kernel32.lib', lock['files'])

    def test_stage_requires_independent_exact_lock_and_retains_sources(self):
        with tempfile.TemporaryDirectory() as base:
            roots, pin = self.fixture(base)
            directory, result = self.collect(base, roots, pin)
            output = Path(base) / 'staged'
            with self.assertRaisesRegex(ValueError, 'candidate lock SHA256'):
                receipt.stage(directory, '0' * 64, output)
            self.assertFalse(output.exists())
            receipt.stage(directory, result['lockSha256'], output)
            self.assertTrue((output / 'clang/bin/clang-cl.exe').is_file())
            self.assertTrue((roots['clang'] / 'bin/clang-cl.exe').is_file())
            lock = json.loads((directory / 'candidate-lock.json').read_text())
            self.assertEqual(receipt.file_inventory(output), lock['files'])
            with self.assertRaisesRegex(ValueError, 'new directory'):
                receipt.stage(directory, result['lockSha256'], output)

    def test_changed_source_file_blocks_stage_without_publishing_partial_output(self):
        with tempfile.TemporaryDirectory() as base:
            roots, pin = self.fixture(base)
            directory, result = self.collect(base, roots, pin)
            (roots['sdk'] / 'Lib/10.0.28000.0/um/x64/kernel32.lib').write_text('changed')
            output = Path(base) / 'staged'
            with self.assertRaisesRegex(ValueError, 'source file changed'):
                receipt.stage(directory, result['lockSha256'], output)
            self.assertFalse(output.exists())

    def test_compiler_identity_or_payload_change_is_rejected_before_collection(self):
        with tempfile.TemporaryDirectory() as base:
            roots, pin = self.fixture(base)
            with patch.object(receipt, 'clang_pin', return_value=pin), \
                    patch.object(receipt.subprocess, 'check_output', return_value='clang version wrong'):
                with self.assertRaisesRegex(ValueError, 'Compiler identity'):
                    receipt.collect(roots['clang'], roots['msvc'], roots['sdk'], '14.51.36231',
                                    '10.0.28000.0', 'windows-x64', Path(base) / 'candidate', msvc_version_file=roots['msvc'] / 'version.txt')
            (roots['clang'] / 'bin/clang-cl.exe').write_text('changed')
            with patch.object(receipt, 'clang_pin', return_value=pin), \
                    patch.object(receipt.subprocess, 'check_output') as execute:
                with self.assertRaisesRegex(ValueError, 'Compiler directory'):
                    receipt.collect(roots['clang'], roots['msvc'], roots['sdk'], '14.51.36231',
                                    '10.0.28000.0', 'windows-x64', Path(base) / 'candidate', msvc_version_file=roots['msvc'] / 'version.txt')
                execute.assert_not_called()

    def test_missing_sdk_does_not_choose_different_installed_version(self):
        with tempfile.TemporaryDirectory() as base:
            roots, pin = self.fixture(base)
            with patch.object(receipt, 'clang_pin', return_value=pin), \
                    patch.object(receipt.subprocess, 'check_output', return_value=pin['clangVersion']):
                with self.assertRaisesRegex(ValueError, 'directory missing'):
                    receipt.collect(roots['clang'], roots['msvc'], roots['sdk'], '14.51.36231',
                                    '10.0.26100.0', 'windows-x64', Path(base) / 'candidate', msvc_version_file=roots['msvc'] / 'version.txt')

    def test_missing_required_crt_library_is_rejected(self):
        with tempfile.TemporaryDirectory() as base:
            roots, pin = self.fixture(base)
            (roots['msvc'] / 'lib/x64/libcmt.lib').unlink()
            with patch.object(receipt, 'clang_pin', return_value=pin), \
                    patch.object(receipt.subprocess, 'check_output', return_value=pin['clangVersion']):
                with self.assertRaisesRegex(ValueError, 'CRT payload missing'):
                    receipt.collect(roots['clang'], roots['msvc'], roots['sdk'], '14.51.36231',
                                    '10.0.28000.0', 'windows-x64', Path(base) / 'candidate', msvc_version_file=roots['msvc'] / 'version.txt')

    def test_msvc_version_file_must_match_explicit_version(self):
        with tempfile.TemporaryDirectory() as base:
            roots, pin = self.fixture(base)
            (roots['msvc'] / 'version.txt').write_text('14.44.12345')
            with self.assertRaisesRegex(ValueError, 'version file differs'):
                self.collect(base, roots, pin)

    def test_collector_never_overwrites_prior_receipt(self):
        with tempfile.TemporaryDirectory() as base:
            roots, pin = self.fixture(base)
            directory, _ = self.collect(base, roots, pin)
            before = (directory / 'receipt.json').read_bytes()
            with self.assertRaisesRegex(ValueError, 'new directory'):
                self.collect(base, roots, pin)
            self.assertEqual(before, (directory / 'receipt.json').read_bytes())


if __name__ == '__main__':
    unittest.main()
