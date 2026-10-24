import hashlib
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fetch_voice_benchmark as fetcher


class Response(io.BytesIO):
    def __init__(self, body, status=200, content_range=None):
        super().__init__(body)
        self.status = status
        self.headers = {"Content-Range": content_range} if content_range else {}


class VoiceFetchTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.target = Path(self.temporary.name) / "model.bin"
        self.content = b"abcdef"
        self.entry = ("https://example.invalid/model", hashlib.sha256(self.content).hexdigest(), 6, self.target)

    def test_truncated_response_resumes_and_verifies_before_installing(self):
        with patch.dict(fetcher.FILES, fixture=self.entry), patch.object(fetcher.urllib.request, "urlopen",
            side_effect=[Response(b"abc"), Response(b"def", 206, "bytes 3-5/6")]) as opened:
            self.assertEqual(fetcher.fetch("fixture"), self.target)
            self.assertEqual(self.target.read_bytes(), self.content)
            self.assertEqual(opened.call_args_list[1].args[0].get_header("Range"), "bytes=3-")
        self.assertFalse(self.target.with_suffix(".bin.part").exists())

    def test_server_ignoring_range_replaces_instead_of_appending(self):
        self.target.with_suffix(".bin.part").write_bytes(b"abc")
        with patch.dict(fetcher.FILES, fixture=self.entry), patch.object(fetcher.urllib.request, "urlopen", return_value=Response(self.content)):
            fetcher.fetch("fixture")
        self.assertEqual(self.target.read_bytes(), self.content)

    def test_wrong_digest_is_never_installed(self):
        with patch.dict(fetcher.FILES, fixture=self.entry), patch.object(fetcher.urllib.request, "urlopen",
            side_effect=[Response(b"xxxxxx") for _ in range(4)]):
            with self.assertRaises(RuntimeError):
                fetcher.fetch("fixture")
        self.assertFalse(self.target.exists())

    def test_unexpected_range_is_not_appended(self):
        self.target.with_suffix(".bin.part").write_bytes(b"abc")
        with patch.dict(fetcher.FILES, fixture=self.entry), patch.object(fetcher.urllib.request, "urlopen",
            side_effect=[Response(b"wrong", 206, "bytes 2-6/7"), Response(self.content)]):
            fetcher.fetch("fixture")
        self.assertEqual(self.target.read_bytes(), self.content)


if __name__ == "__main__":
    unittest.main()
