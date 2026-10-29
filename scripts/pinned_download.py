"""Hash-verified, resumable downloads for pinned build dependencies."""
import hashlib
import os
from pathlib import Path
import urllib.request

FILES = {}

def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
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
            headers={"User-Agent": "mtmux-voice/1", **({"Range": f"bytes={offset}-"} if offset else {})})
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
