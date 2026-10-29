#!/usr/bin/env python3
"""Prepare the same pinned X-ASR INT8 and sherpa runtime used by Audio Lab."""
import argparse
import json
from pathlib import Path
import shutil
import tarfile

from pinned_download import FILES, fetch, valid

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".voice-models"
MODEL = json.loads((ROOT / "voice/model.lock.json").read_text())
RUNTIME = json.loads((ROOT / "voice/runtime.lock.json").read_text())


def download(key, spec, target, reuse=None):
    if reuse and valid(reuse, spec["sha256"], spec["bytes"]) and not valid(target, spec["sha256"], spec["bytes"]):
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(reuse, target)
    FILES[key] = spec["url"], spec["sha256"], spec["bytes"], target
    return fetch(key)


def install_members(archive, specs, directory, member_name):
    """Extract only pinned regular files, never archive-controlled paths or links."""
    with tarfile.open(archive, "r:bz2") as bundle:
        for spec in specs:
            target = directory / spec["path"]
            if valid(target, spec["sha256"], spec["bytes"]):
                continue
            member = bundle.getmember(member_name(spec))
            if not member.isfile() or member.size != spec["bytes"]:
                raise ValueError(f"Unexpected archive member: {member.name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            temporary = target.with_suffix(target.suffix + ".part")
            try:
                with bundle.extractfile(member) as source, temporary.open("wb") as output:
                    shutil.copyfileobj(source, output)
                if not valid(temporary, spec["sha256"], spec["bytes"]):
                    raise ValueError(f"Bad checksum: {spec['path']}")
                temporary.replace(target)
            finally:
                temporary.unlink(missing_ok=True)


def verify(specs, directory):
    for spec in specs:
        if not valid(directory / spec["path"], spec["sha256"], spec["bytes"]):
            raise SystemExit(f"Missing/damaged {spec['path']}; run python3 scripts/fetch_voice.py")


def prepare():
    lab = ROOT / "test-audio"
    runtime = download("sherpa-runtime", RUNTIME["archive"], CACHE / "downloads/sherpa-android.tar.bz2", lab / "vendor/sherpa-android.tar.bz2")
    install_members(runtime, RUNTIME["libraries"], CACHE / "runtime", lambda s: "./" + s["path"])
    for spec in RUNTIME["kotlin"]:
        download(spec["path"], spec, CACHE / "runtime" / spec["path"], lab / "vendor" / spec["path"])
    name = MODEL["url"].split("/")[-1]
    model = download("x-asr", MODEL, CACHE / "downloads" / name, lab / ".cache" / name)
    install_members(model, MODEL["files"], CACHE / "models", lambda s: s["member"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs="?", choices=["prepare", "verify", "verify-runtime", "verify-model"], default="prepare")
    command = parser.parse_args().command
    if command == "prepare":
        prepare()
    if command != "verify-model":
        verify(RUNTIME["libraries"] + RUNTIME["kotlin"], CACHE / "runtime")
    if command != "verify-runtime":
        verify(MODEL["files"], CACHE / "models")
    print("OK pinned X-ASR / sherpa voice assets")
