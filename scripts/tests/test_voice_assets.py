import hashlib
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fetch_voice import MODEL, ROOT, install_members


class VoiceAssetsTest(unittest.TestCase):
    def test_android_install_manifest_matches_locked_deployment(self):
        installed = json.loads((ROOT / 'voice/src/main/assets/voice-model.json').read_text())
        self.assertEqual(installed, {'id': MODEL['id'], 'files': MODEL['files']})
        self.assertEqual({Path(f['path']).name for f in MODEL['files']},
                         {'encoder.int8.onnx', 'decoder.onnx', 'joiner.int8.onnx', 'tokens.txt'})
        self.assertEqual(sum(f['bytes'] for f in MODEL['files']), 169_227_953)

    def archive(self, directory, content, link=False):
        path = directory / 'archive.tar.bz2'
        with tarfile.open(path, 'w:bz2') as archive:
            member = tarfile.TarInfo('model')
            if link:
                member.type = tarfile.SYMTYPE
                member.linkname = '/etc/passwd'
                archive.addfile(member)
            else:
                member.size = len(content)
                archive.addfile(member, io.BytesIO(content))
        return path

    def test_corrupted_member_never_replaces_model(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            spec = dict(path='model.onnx', bytes=3, sha256=hashlib.sha256(b'abc').hexdigest())
            archive = self.archive(root, b'bad')
            with self.assertRaises(ValueError):
                install_members(archive, [spec], root / 'models', lambda s: 'model')
            self.assertFalse((root / 'models/model.onnx').exists())
            self.assertFalse((root / 'models/model.onnx.part').exists())

    def test_archive_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = self.archive(root, b'', link=True)
            spec = dict(path='model.onnx', bytes=0, sha256=hashlib.sha256(b'').hexdigest())
            with self.assertRaises(ValueError):
                install_members(archive, [spec], root / 'models', lambda s: 'model')
            self.assertFalse((root / 'models/model.onnx').exists())
