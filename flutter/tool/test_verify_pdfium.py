"""Fail-closed tests for native PDFium prefetch, with no network or native execution."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import struct
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("verify_pdfium", Path(__file__).with_name("verify-pdfium.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PdfiumPrefetchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.binary = b"original-native-fixture-not-executed"
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
            member = tarfile.TarInfo("lib/libpdfium.so")
            member.size = len(self.binary)
            archive.addfile(member, io.BytesIO(self.binary))
        self.archive = buffer.getvalue()
        self.pin = {
            "archive": "https://example.invalid/pinned.tgz",
            "archiveSha256": hashlib.sha256(self.archive).hexdigest(),
            "binarySha256": hashlib.sha256(self.binary).hexdigest(),
            "member": "lib/libpdfium.so",
        }
        self.patches = [patch.object(module, "ROOT", self.root),
                        patch.object(module, "PINS", {"targets": {"linux-x64": self.pin}})]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)

    def fetch(self, data):
        return patch.object(module.urllib.request, "urlopen", return_value=io.BytesIO(data))

    def test_archive_digest_rejection_does_not_write_a_cache(self):
        with self.fetch(self.archive + b"tampered"):
            with self.assertRaisesRegex(ValueError, "archive SHA256"):
                module.prepare("linux-x64")
        self.assertFalse((self.root / ".dart_tool").exists())

    def test_binary_digest_rejection_does_not_write_a_cache(self):
        self.pin["binarySha256"] = "0" * 64
        with self.fetch(self.archive):
            with self.assertRaisesRegex(ValueError, "binary SHA256"):
                module.prepare("linux-x64")
        self.assertFalse((self.root / ".dart_tool").exists())

    def test_valid_archive_is_written_once_and_existing_cache_is_revalidated(self):
        with self.fetch(self.archive) as fetch:
            self.assertEqual(module.prepare("linux-x64"), "linux-x64")
            self.assertEqual(module.prepare("linux-x64"), "linux-x64")
            self.assertEqual(fetch.call_count, 1)
        destination = next(self.root.glob("**/libpdfium.so"))
        self.assertEqual(destination.read_bytes(), self.binary)
        destination.write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "Unverified PDFium"):
            module.prepare("linux-x64")
        self.assertEqual(destination.read_bytes(), b"tampered")


class AndroidAlignmentTests(unittest.TestCase):
    def fixture(self, alignment=16384, virtual_address=16384):
        data = bytearray(120)
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, virtual_address, 0, 0, 0, alignment)
        return data

    def test_accepts_16k_load_segments(self):
        module.check_android_alignment(self.fixture())

    def test_rejects_4k_only_or_misaligned_segments(self):
        for data in [self.fixture(alignment=4096), self.fixture(virtual_address=4096)]:
            with self.assertRaisesRegex(ValueError, "16 KiB"):
                module.check_android_alignment(data)

    def test_rejects_truncated_program_headers(self):
        with self.assertRaisesRegex(ValueError, "program header"):
            module.check_android_alignment(self.fixture()[:-1])


if __name__ == "__main__":
    unittest.main()
