#!/usr/bin/env python3
"""Reproduce the pinned runtime, model store and optional offline ZIP (Python stdlib only)."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT.parent
sys.path.insert(0, str(ROOT / "tools"))
from fetch_voice_benchmark import FILES, fetch, valid

CACHE = ROOT / ".cache"
MODELS = CACHE / "models"

def install(source: Path, dest: Path, size: int, sha: str):
    if valid(dest, sha, size): return
    dest.parent.mkdir(parents=True, exist_ok=True)
    temp = dest.with_suffix(dest.suffix + ".part")
    try:
        shutil.copyfile(source, temp)
        if not valid(temp, sha, size): raise ValueError(f"Bad file {dest}")
        temp.replace(dest)
    finally: temp.unlink(missing_ok=True)

def fetch_locked(key: str, entry: dict, dest: Path) -> Path:
    FILES[key] = (entry["url"], entry["sha256"], entry["bytes"], dest)
    return fetch(key)

def runtime():
    lock = json.loads((ROOT / "runtime.lock.json").read_text())
    archive = fetch_locked("audiolab-runtime", lock["archive"], ROOT / "vendor/sherpa-android.tar.bz2")
    with tarfile.open(archive, "r:bz2") as tar:
        for spec in lock["libraries"]:
            dest = ROOT / "vendor" / spec["path"]
            if valid(dest, spec["sha256"], spec["bytes"]): continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            member = tar.getmember("./" + spec["path"])
            if not member.isfile() or member.size != spec["bytes"]: raise ValueError("Bad runtime archive")
            with tar.extractfile(member) as src, dest.open("wb") as dst: shutil.copyfileobj(src, dst)
            if not valid(dest, spec["sha256"], spec["bytes"]): raise ValueError("Bad native library")
    for spec in lock["kotlin"]:
        fetch_locked("audiolab-" + Path(spec["path"]).name, spec, ROOT / "vendor" / spec["path"])
    subprocess.run([sys.executable, str(ROOT / "tools/fetch_voice_benchmark.py"), "deps"], check=True)

def models():
    manifest = json.loads((ROOT / "app/src/main/assets/models.json").read_text())
    for spec in manifest["sources"]:
        if all(valid(MODELS / w["path"], w["sha256"], w["bytes"]) for w in spec["files"]):
            print("OK installed", spec["id"], flush=True); continue
        # Reuse the already verified mTmux weight cache when it exists.
        if not spec["archive"]:
            w = spec["files"][0]
            for old in [CACHE / "legacy-whisper/models" / Path(w["path"]).name, CACHE / "legacy-whisper/punctuation" / Path(w["path"]).name]:
                if valid(old, w["sha256"], w["bytes"]):
                    install(old, MODELS / w["path"], w["bytes"], w["sha256"]); break
            if valid(MODELS / w["path"], w["sha256"], w["bytes"]): continue
        name = spec["url"].split("?")[0].split("/")[-1]
        archive = fetch_locked("audiolab-model-" + spec["id"], spec, CACHE / name)
        if not spec["archive"]:
            w = spec["files"][0]; install(archive, MODELS / w["path"], w["bytes"], w["sha256"]); continue
        expected = {w["member"]: w for w in spec["files"]}
        with tarfile.open(archive, "r:bz2") as tar:
            found = set()
            for member in tar:
                if member.name not in expected: continue
                w = expected[member.name]; found.add(member.name)
                if not member.isfile() or member.size != w["bytes"]: raise ValueError("Bad model archive")
                dest = MODELS / w["path"]; dest.parent.mkdir(parents=True, exist_ok=True)
                temp = dest.with_suffix(dest.suffix + ".part")
                try:
                    with tar.extractfile(member) as src, temp.open("wb") as dst: shutil.copyfileobj(src, dst)
                    if not valid(temp, w["sha256"], w["bytes"]): raise ValueError(f"Bad model {w['path']}")
                    temp.replace(dest)
                finally: temp.unlink(missing_ok=True)
            if found != set(expected): raise ValueError("Missing model files")
        print("OK deployed", spec["id"], flush=True)

def pack():
    models()
    manifest = json.loads((ROOT / "app/src/main/assets/models.json").read_text())
    out = ROOT / "outputs"; out.mkdir(exist_ok=True)
    dest = out / "audio-lab-models.zip"; temp = dest.with_suffix(".zip.part")
    try:
        with zipfile.ZipFile(temp, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as z:
            for s in manifest["sources"]:
                for w in s["files"]: z.write(MODELS / w["path"], w["path"])
            z.write(ROOT / "app/src/main/assets/models.json", "models.json")
        temp.replace(dest)
    finally: temp.unlink(missing_ok=True)
    digest = hashlib.file_digest(dest.open("rb"), "sha256").hexdigest()
    dest.with_suffix(".zip.sha256").write_text(f"{digest}  {dest.name}\n")
    print(dest, dest.stat().st_size, digest, flush=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["runtime", "models", "pack"])
    globals()[parser.parse_args().command]()
