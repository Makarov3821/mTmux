#!/usr/bin/env python3
"""Verify pinned X-ASR/runtime payloads and reject obsolete or extra voice files."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile
from fetch_voice import MODEL, RUNTIME


def digest(archive, name):
    result = hashlib.sha256()
    with archive.open(name) as source:
        while block := source.read(1024 * 1024):
            result.update(block)
    return result.hexdigest()


def verify(standard: Path, voice: Path, max_bytes: int = 220_000_000):
    with zipfile.ZipFile(standard) as archive:
        common_native = {n for n in archive.namelist() if n.startswith("lib/") and n.endswith(".so")}
        assert not any("sherpa" in n or "libmtmux_voice" in n or "onnxruntime" in n or n.startswith("assets/models/") or n == "assets/voice-model.json" for n in archive.namelist()), "standard contains voice assets/runtime"
    with zipfile.ZipFile(voice) as archive:
        names = set(archive.namelist())
        assert json.loads(archive.read("assets/voice-model.json")) == {"id": MODEL["id"], "files": MODEL["files"]}, "model install manifest differs from lock"
        expected = {"assets/models/" + s["path"]: s for s in MODEL["files"]}
        assert {n for n in names if n.startswith("assets/models/") and not n.endswith("/")} == set(expected), "unexpected bundled models"
        assert not any(any(old in n.lower() for old in ["whisper", "ggml", "ct-punct", "srf-", "libmtmux_voice", "onnxruntime4j"]) for n in names), "obsolete voice payload"
        obsolete_classes = [b"Ldev/mtmux/voice/WhisperTranscriber;", b"Ldev/mtmux/voice/NativeVoice;",
                            b"Ldev/mtmux/voice/PunctuationRestorer;", b"Lai/onnxruntime/OrtEnvironment;"]
        for name in names:
            if name.endswith(".dex"):
                dex = archive.read(name)
                assert not any(old in dex for old in obsolete_classes), "obsolete voice class"
        expected.update({"lib/" + s["path"].removeprefix("jniLibs/"): s for s in RUNTIME["libraries"]})
        assert {n for n in names if n.startswith("lib/") and n.endswith(".so")} - common_native == {n for n in expected if n.startswith("lib/")}, "unexpected native runtime"
        for name, spec in expected.items():
            assert archive.getinfo(name).file_size == spec["bytes"], f"size: {name}"
            assert digest(archive, name) == spec["sha256"], f"checksum: {name}"
    assert voice.stat().st_size <= max_bytes, "voice APK exceeds size guard"
    print(f"OK standard={standard.stat().st_size} bytes, voice={voice.stat().st_size} bytes; pinned X-ASR INT8 and sherpa runtime, both ABIs, no legacy payload")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("standard", type=Path)
    parser.add_argument("voice", type=Path)
    parser.add_argument("--max-bytes", type=int, default=220_000_000)
    args = parser.parse_args()
    verify(args.standard, args.voice, args.max_bytes)
