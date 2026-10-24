#!/usr/bin/env python3
"""Check the actual bundled models and voice-only runtime, including A/B builds."""
import argparse
import hashlib
from pathlib import Path
import zipfile

from fetch_voice_benchmark import FILES
from fetch_voice_punctuation import model_specs


def verify(standard: Path, voice: Path, quantization: str, max_bytes: int, punctuation: str = "ct") -> None:
    key = "base" if quantization == "q8_0" else "base-q5"
    _, whisper_hash, whisper_bytes, whisper_path = FILES[key]
    expected = {
        f"assets/models/{whisper_path.name}": (whisper_bytes, whisper_hash),
    }
    expected.update({f"assets/models/{name}": spec for name, spec in model_specs(punctuation).items()})
    with zipfile.ZipFile(standard) as archive:
        assert not any("libmtmux_voice" in n or "onnxruntime" in n or n.startswith("assets/models/") for n in archive.namelist()), "standard contains voice assets/runtime"
    with zipfile.ZipFile(voice) as archive:
        names = set(archive.namelist())
        assert {n for n in names if n.startswith("assets/models/") and not n.endswith("/")} == set(expected), "unexpected bundled models"
        for name, (size, digest) in expected.items():
            assert archive.getinfo(name).file_size == size, f"model size: {name}"
            actual = hashlib.sha256()
            with archive.open(name) as source:
                while block := source.read(1024 * 1024):
                    actual.update(block)
            assert actual.hexdigest() == digest, f"model checksum: {name}"
        for abi in ["arm64-v8a", "x86_64"]:
            for library in ["libmtmux_voice.so", "libonnxruntime.so", "libonnxruntime4j_jni.so"]:
                assert f"lib/{abi}/{library}" in names, f"missing {abi}/{library}"
    assert voice.stat().st_size <= max_bytes, "voice APK exceeds size guard"
    print(f"OK standard={standard.stat().st_size} bytes, voice={voice.stat().st_size} bytes; Whisper {quantization} + pinned {punctuation} INT8 punctuation; separation verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("standard", type=Path)
    parser.add_argument("voice", type=Path)
    parser.add_argument("--quantization", choices=["q5_1", "q8_0"], default="q8_0")
    parser.add_argument("--max-bytes", type=int, default=200_000_000)
    parser.add_argument("--punctuation-model", choices=["ct", "srf"], default="ct")
    args = parser.parse_args()
    verify(args.standard, args.voice, args.quantization, args.max_bytes, args.punctuation_model)
