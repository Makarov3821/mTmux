#!/usr/bin/env python3
"""Install the pinned upstream INT8 punctuation model into the ignored voice cache."""
from __future__ import annotations
import os
import argparse
from pathlib import Path
import sys
import tarfile

from fetch_voice_benchmark import CACHE, DOWNLOADS, FILES, fetch, valid

MODEL_NAME = "ct-punct-int8.onnx"
MODEL_BYTES = 75_519_198
MODEL_SHA256 = "65a3fb9f5ad7bfb96bf69e0dc4481df97f6ee60513c1d94ce981ba6effd524b1"
MODEL = CACHE / "punctuation" / MODEL_NAME
FILES["punctuation"] = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/"
    "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2",
    "c0d5aa5f8eeb686032345e180bedf39319dc2e0556781c6264bcadba8328a6e1",
    64_717_756,
    DOWNLOADS / "ct-punct-int8.tar.bz2",
)
SRF_REVISION = "055df91f8f14696f93bb4eb4b56245ba51a6fcde"
SRF_SOURCE_REVISION = "e4814e794e807965fe79e15d55c6d699c87c0569"
FILES["srf"] = (
    f"https://huggingface.co/rkingzhong/srf-punctuation/resolve/{SRF_REVISION}/punctuation_int8.onnx?download=true",
    "650eb96762e2b6cfe12a3f1b44ddcab0a3f790e11a98afab3895f863d5eaec62", 2_243_714,
    CACHE / "punctuation/srf-punct-int8.onnx",
)
FILES["srf-vocab"] = (
    f"https://raw.githubusercontent.com/ximeiorg/srf-punctuation/{SRF_SOURCE_REVISION}/web/public/vocab.json",
    "76684d3d381bed15057f19e6b22972a00ec79e72fa4bfd5682afde4fdcc5a3a2", 119_104,
    CACHE / "punctuation/srf-vocab.json",
)


def model_specs(kind: str) -> dict[str, tuple[int, str]]:
    if kind == "ct":
        return {MODEL_NAME: (MODEL_BYTES, MODEL_SHA256)}
    return {FILES[key][3].name: (FILES[key][2], FILES[key][1]) for key in ("srf", "srf-vocab")}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", nargs="?", choices=["fetch", "verify"], default="fetch")
    parser.add_argument("--model", choices=["ct", "srf"], default="ct")
    args = parser.parse_args()
    command = args.command
    if args.model == "srf":
        for key in ("srf", "srf-vocab"):
            _, digest, size, path = FILES[key]
            if command == "fetch":
                fetch(key)
            elif not valid(path, digest, size):
                raise SystemExit(f"MISSING/BAD {key}; run test-audio/tools/fetch_voice_punctuation.py --model srf")
        print("OK pinned SRF model and vocabulary")
        return
    if valid(MODEL, MODEL_SHA256, MODEL_BYTES):
        print(f"OK punctuation: {MODEL}")
        return
    if command == "verify":
        raise SystemExit("MISSING/BAD punctuation model; run test-audio/tools/fetch_voice_punctuation.py")
    archive = fetch("punctuation")
    MODEL.parent.mkdir(parents=True, exist_ok=True)
    temporary = MODEL.with_suffix(".onnx.part")
    try:
        with tarfile.open(archive, "r:bz2") as bundle:
            members = [m for m in bundle.getmembers() if m.isfile() and Path(m.name).name == "model.int8.onnx"]
            if len(members) != 1 or members[0].size != MODEL_BYTES:
                raise ValueError("unexpected punctuation archive layout")
            source = bundle.extractfile(members[0])
            if source is None:
                raise ValueError("punctuation model missing")
            with source, temporary.open("wb") as output:
                while block := source.read(1024 * 1024):
                    output.write(block)
        if not valid(temporary, MODEL_SHA256, MODEL_BYTES):
            raise ValueError("punctuation checksum mismatch")
        os.replace(temporary, MODEL)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"OK installed punctuation: {MODEL}")


if __name__ == "__main__":
    main()
