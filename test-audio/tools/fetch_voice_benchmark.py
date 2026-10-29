#!/usr/bin/env python3
"""Fetch pinned whisper.cpp V0 benchmark inputs into the ignored test-audio/.cache/legacy-whisper/ directory."""
from __future__ import annotations
import hashlib
import os
from pathlib import Path
import shutil
import tarfile
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / "test-audio/.cache/legacy-whisper"
DOWNLOADS = CACHE / "downloads"

WHISPER_COMMIT = "d1be6fde11ac6e0407606b4e42fe72d34add8037"  # upstream v1.9.5
WHISPER_TREE_SHA256 = "6c5e308f2a6bda094acaac548222e5c3a34f50d5f3d62e1d64689003cbd12572"
MODEL_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
FILES = {
    "source": (
        f"https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/{WHISPER_COMMIT}",
        "645445da884df6dbd52b30f82d7be7c01d1767c938714e477e786b88d529e9f4",
        9_631_456,
        DOWNLOADS / f"whisper.cpp-{WHISPER_COMMIT}.tar.gz",
    ),
    "tiny": (
        f"https://huggingface.co/ggerganov/whisper.cpp/resolve/{MODEL_REVISION}/ggml-tiny-q5_1.bin?download=true",
        "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
        32_152_673,
        CACHE / "models/ggml-tiny-q5_1.bin",
    ),
    "base": (
        f"https://huggingface.co/ggerganov/whisper.cpp/resolve/{MODEL_REVISION}/ggml-base-q8_0.bin?download=true",
        "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9",
        81_768_585,
        CACHE / "models/ggml-base-q8_0.bin",
    ),
    "base-q5": (
        f"https://huggingface.co/ggerganov/whisper.cpp/resolve/{MODEL_REVISION}/ggml-base-q5_1.bin?download=true",
        "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        59_707_625,
        CACHE / "models/ggml-base-q5_1.bin",
    ),
    "sample": (
        f"https://raw.githubusercontent.com/ggml-org/whisper.cpp/{WHISPER_COMMIT}/samples/jfk.wav",
        "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e",
        352_078,
        CACHE / "audio/jfk.wav",
    ),
}

def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

def tree_sha256(root: Path) -> str:
    """Bind the extracted files actually compiled by CMake, not just a version marker."""
    digest = hashlib.sha256()
    for path in sorted(p for p in root.rglob("*") if p.is_file() and p.name != ".mtmux-source"):
        digest.update(path.relative_to(root).as_posix().encode("utf-8"))
        digest.update(b"\0")
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        digest.update(b"\0")
    return digest.hexdigest()


def valid(path: Path, expected_hash: str, expected_size: int) -> bool:
    return path.is_file() and path.stat().st_size == expected_size and sha256(path) == expected_hash


def fetch(name: str) -> Path:
    url, expected_hash, expected_size, target = FILES[name]
    if valid(target, expected_hash, expected_size):
        print(f"OK cached {name}: {target}")
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".part")
    print(f"Downloading {name} ({expected_size / 1_000_000:.1f} MB)…", flush=True)
    for attempt in range(4):
        offset = temporary.stat().st_size if temporary.is_file() else 0
        if offset >= expected_size:
            if valid(temporary, expected_hash, expected_size):
                os.replace(temporary, target)
                return target
            temporary.unlink(missing_ok=True)
            offset = 0
        separator = "&" if "?" in url else "?"
        request = urllib.request.Request(url + f"{separator}mtmux_offset={offset}",
            headers={"User-Agent": "mtmux-voice-benchmark/1", **({"Range": f"bytes={offset}-"} if offset else {})})
        try:
            with urllib.request.urlopen(request, timeout=60) as source:
                if source.status == 206:
                    if not source.headers.get("Content-Range", "").startswith(f"bytes {offset}-"):
                        temporary.unlink(missing_ok=True)
                        raise ValueError("unexpected download range")
                elif offset:
                    offset = 0  # Server ignored Range: replace the partial file.
                count = offset
                with temporary.open("ab" if offset else "wb") as output:
                    while block := source.read(1024 * 1024):
                        count += len(block)
                        if count > expected_size:
                            raise ValueError("response exceeds pinned size")
                        output.write(block)
            if count != expected_size:
                raise ValueError("incomplete download")
            if sha256(temporary) != expected_hash:
                temporary.unlink(missing_ok=True)
                raise ValueError("checksum mismatch")
            os.replace(temporary, target)
            print(f"OK fetched {name}: {target}", flush=True)
            return target
        except (OSError, ValueError) as error:
            print(f"Retry {name} {attempt + 1}/4: {type(error).__name__}", flush=True)
            if attempt == 3:
                raise RuntimeError(f"{name}: pinned download failed; partial file kept for retry") from error
    raise AssertionError("unreachable")


def extract_source(archive: Path) -> None:
    destination = CACHE / "whisper.cpp"
    stamp = destination / ".mtmux-source"
    if (stamp.is_file() and stamp.read_text().strip() == WHISPER_COMMIT and
            tree_sha256(destination) == WHISPER_TREE_SHA256):
        print(f"OK extracted source: {destination}")
        return
    temporary = CACHE / "whisper.cpp.extracting"
    shutil.rmtree(temporary, ignore_errors=True)
    temporary.mkdir(parents=True)
    with tarfile.open(archive, "r:gz") as bundle:
        members = bundle.getmembers()
        roots = {Path(member.name).parts[0] for member in members if Path(member.name).parts}
        if len(roots) != 1:
            raise ValueError("unexpected whisper.cpp archive layout")
        root = next(iter(roots))
        for member in members:
            parts = Path(member.name).parts
            if not parts or parts[0] != root or member.issym() or member.islnk():
                continue
            relative = Path(*parts[1:])
            if not relative.parts or ".." in relative.parts:
                continue
            target = temporary / relative
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                source = bundle.extractfile(member)
                if source is None:
                    raise ValueError("cannot extract archive member")
                with source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
    actual_tree = tree_sha256(temporary)
    if actual_tree != WHISPER_TREE_SHA256:
        shutil.rmtree(temporary, ignore_errors=True)
        raise ValueError(f"whisper.cpp source tree mismatch: {actual_tree}")
    (temporary / ".mtmux-source").write_text(WHISPER_COMMIT + "\n")
    shutil.rmtree(destination, ignore_errors=True)
    os.replace(temporary, destination)
    print(f"OK extracted source: {destination}")


def main() -> None:
    command = sys.argv[1] if len(sys.argv) > 1 else "all"
    if command not in {"all", "deps", "models", "base", "base-q5", "sample", "verify", "verify-source", "verify-base", "verify-base-q5"}:
        raise SystemExit("usage: fetch_voice_benchmark.py [all|deps|models|base|base-q5|sample|verify|verify-source|verify-base|verify-base-q5]")
    CACHE.mkdir(exist_ok=True)
    if command in {"all", "deps"}:
        extract_source(fetch("source"))
    if command in {"all", "models"}:
        fetch("tiny")
        fetch("base")
        fetch("base-q5")
    if command in {"base", "base-q5"}:
        fetch(command)
    if command in {"all", "sample"}:
        fetch("sample")
    if command in {"verify", "verify-source", "verify-base", "verify-base-q5"}:
        failed = False
        names = (FILES if command == "verify" else
                 {"source": FILES["source"]} if command == "verify-source" else
                 {"base-q5": FILES["base-q5"]} if command == "verify-base-q5" else
                 {"base": FILES["base"]})
        for name, (_, digest, size, path) in names.items():
            ok = valid(path, digest, size)
            print(f"{'OK' if ok else 'MISSING/BAD'} {name}: {path}")
            failed |= not ok
        if command not in {"verify-base", "verify-base-q5"}:
            source_stamp = CACHE / "whisper.cpp/.mtmux-source"
            stamp_ok = source_stamp.is_file() and source_stamp.read_text().strip() == WHISPER_COMMIT
            tree_actual = tree_sha256(CACHE / "whisper.cpp") if stamp_ok else "missing"
            tree_ok = stamp_ok and tree_actual == WHISPER_TREE_SHA256
            print(f"{'OK' if tree_ok else 'MISSING/BAD'} source tree: {tree_actual}")
            failed |= not tree_ok
        if failed:
            raise SystemExit(1)

if __name__ == "__main__":
    main()
