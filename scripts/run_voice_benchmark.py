#!/usr/bin/env python3
"""Build/install the V0 APK, push pinned inputs, and collect JSON benchmark results."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

from fetch_voice_benchmark import FILES, sha256, valid

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".voice-benchmark"
PACKAGE = "dev.mtmux.voicebenchmark"
ACTIVITY = f"{PACKAGE}/.BenchmarkActivity"
APK = ROOT / "voice-benchmark/build/outputs/apk/release/voice-benchmark-release.apk"
MODELS = {
    "tiny": CACHE / "models/ggml-tiny-q5_1.bin",
    "base": CACHE / "models/ggml-base-q8_0.bin",
    "base-q5": CACHE / "models/ggml-base-q5_1.bin",
}

def run(command: list[str], *, capture: bool = False, check: bool = True) -> str:
    print("+", " ".join(command), file=sys.stderr)
    result = subprocess.run(command, cwd=ROOT, check=check, text=True,
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.STDOUT if capture else None)
    return result.stdout.strip() if capture else ""


def adb(serial: str | None, *arguments: str, capture: bool = False, check: bool = True) -> str:
    executable = shutil.which("adb") or str(ROOT / ".tools/android-sdk/platform-tools/adb")
    command = [executable]
    if serial:
        command += ["-s", serial]
    command += list(arguments)
    return run(command, capture=capture, check=check)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", default="base-q5,base", help="comma-separated: tiny,base-q5,base (q8)")
    parser.add_argument("--audio", type=Path, default=CACHE / "audio/jfk.wav")
    parser.add_argument("--language", choices=["auto", "en", "zh", "ja", "es"], default="auto")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--timeout", type=int, default=900, help="seconds per run")
    parser.add_argument("--serial")
    parser.add_argument("--no-build", action="store_true")
    args = parser.parse_args()
    names = [name.strip() for name in args.models.split(",") if name.strip()]
    if not names or any(name not in MODELS for name in names):
        parser.error("--models must contain tiny, base-q5 and/or base (q8)")
    if args.threads not in range(1, 17) or args.repeat not in range(1, 11):
        parser.error("threads must be 1..16 and repeat 1..10")
    if not args.audio.is_file():
        raise SystemExit(f"audio not found: {args.audio}; run scripts/fetch_voice_benchmark.py sample")
    missing = [name for name in names if not valid(MODELS[name], FILES[name][1], FILES[name][2])]
    if missing:
        raise SystemExit(f"models missing or invalid: {missing}; run scripts/fetch_voice_benchmark.py models")

    if not args.no_build:
        run(["./gradlew", ":voice-benchmark:assembleRelease", "--console=plain"])
    adb(args.serial, "install", "-r", "-t", str(APK))
    abi = adb(args.serial, "shell", "getprop", "ro.product.cpu.abi", capture=True)
    if abi not in {"arm64-v8a", "x86_64"}:
        raise SystemExit(f"benchmark APK does not include device ABI: {abi}")
    data_root = adb(args.serial, "shell", "run-as", PACKAGE, "pwd", capture=True).splitlines()[-1]
    adb(args.serial, "shell", "run-as", PACKAGE, "mkdir", "-p", "files/models")
    # Device audio/results can contain user speech/transcripts. The host is authoritative; never accumulate them.
    adb(args.serial, "shell", "run-as", PACKAGE, "rm", "-rf", "files/audio", "files/results")
    adb(args.serial, "shell", "run-as", PACKAGE, "mkdir", "files/audio", "files/results")

    audio_name = f"input-{uuid.uuid4().hex[:8]}.wav"
    remote_tmp_audio = f"/data/local/tmp/{PACKAGE}-{audio_name}"
    remote_private_audio = f"files/audio/{audio_name}"
    remote_audio = f"{data_root}/{remote_private_audio}"
    results_dir = CACHE / "results" / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6])
    results_dir.mkdir(parents=True, exist_ok=False)
    collected: list[dict] = []
    staged_temporary = [remote_tmp_audio]
    try:
        # Protect cleanup before any user-provided audio reaches world-readable adb staging.
        adb(args.serial, "push", str(args.audio.resolve()), remote_tmp_audio)
        adb(args.serial, "shell", "run-as", PACKAGE, "cp", remote_tmp_audio, remote_private_audio)
        adb(args.serial, "shell", "rm", "-f", remote_tmp_audio)
        remote_audio_hash = adb(args.serial, "shell", "run-as", PACKAGE, "sha256sum",
                                remote_private_audio, capture=True).split()
        if not remote_audio_hash or remote_audio_hash[0] != sha256(args.audio):
            raise RuntimeError("device audio checksum mismatch")
        for name in names:
            model = MODELS[name]
            remote_model_name = model.name
            remote_tmp_model = f"/data/local/tmp/{PACKAGE}-{remote_model_name}"
            remote_model = f"{data_root}/files/models/{remote_model_name}"
            # Reuse only the exact pinned model; equal size alone is not sufficient.
            expected_hash = FILES[name][1]
            remote_digest = adb(args.serial, "shell", "run-as", PACKAGE, "sha256sum",
                                f"files/models/{remote_model_name}", capture=True, check=False).split()
            if not remote_digest or remote_digest[0] != expected_hash:
                staged_temporary.append(remote_tmp_model)
                adb(args.serial, "push", str(model), remote_tmp_model)
                adb(args.serial, "shell", "run-as", PACKAGE, "cp", remote_tmp_model, f"files/models/{remote_model_name}")
                adb(args.serial, "shell", "rm", "-f", remote_tmp_model)
                remote_digest = adb(args.serial, "shell", "run-as", PACKAGE, "sha256sum",
                                    f"files/models/{remote_model_name}", capture=True).split()
                if not remote_digest or remote_digest[0] != expected_hash:
                    adb(args.serial, "shell", "run-as", PACKAGE, "rm", "-f", f"files/models/{remote_model_name}", check=False)
                    raise RuntimeError(f"device model checksum mismatch: {name}")
            for iteration in range(1, args.repeat + 1):
                run_id = f"{name}-{iteration}-{uuid.uuid4().hex[:8]}"
                adb(args.serial, "shell", "run-as", PACKAGE, "rm", "-f", f"files/results/{run_id}.json")
                adb(args.serial, "shell", "am", "force-stop", PACKAGE)
                adb(args.serial, "shell", "am", "start", "-W", "-n", ACTIVITY,
                    "--es", "model", remote_model, "--es", "audio", remote_audio,
                    "--es", "language", args.language, "--ei", "threads", str(args.threads),
                    "--es", "runId", run_id)
                deadline = time.monotonic() + args.timeout
                payload = ""
                while time.monotonic() < deadline:
                    payload = adb(args.serial, "shell", "run-as", PACKAGE, "cat",
                                  f"files/results/{run_id}.json", capture=True, check=False)
                    if payload.startswith("{"):
                        break
                    time.sleep(1)
                if not payload.startswith("{"):
                    raise TimeoutError(f"benchmark timed out: {run_id}")
                result = json.loads(payload)
                result["runnerModel"] = name
                result["runnerIteration"] = iteration
                target = results_dir / f"{run_id}.json"
                target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
                collected.append(result)
                print(json.dumps({key: result.get(key) for key in
                    ("runnerModel", "modelType", "audioMs", "loadMs", "inferenceMs", "realTimeFactor",
                     "peakRssAfterKb", "languageDetected", "text", "error")}, ensure_ascii=False, indent=2))
    finally:
        # Stop native inference even on timeout/error so KEEP_SCREEN_ON, CPU and model mappings end.
        active_error = sys.exc_info()[0] is not None
        adb(args.serial, "shell", "am", "force-stop", PACKAGE, check=False)
        for temporary in staged_temporary:
            adb(args.serial, "shell", "rm", "-f", temporary, check=False)
        adb(args.serial, "shell", "run-as", PACKAGE, "rm", "-f", remote_private_audio, check=False)
        adb(args.serial, "shell", "run-as", PACKAGE, "rm", "-rf", "files/results", check=False)
        adb(args.serial, "shell", "run-as", PACKAGE, "mkdir", "files/results", check=False)
        pid = adb(args.serial, "shell", "pidof", PACKAGE, capture=True, check=False)
        remaining = adb(args.serial, "shell", "run-as", PACKAGE, "find", "files/audio", "files/results",
                        "-type", "f", capture=True, check=False)
        if (pid or remaining) and not active_error:
            raise RuntimeError(f"device cleanup incomplete (pid={pid!r}, files={remaining!r})")
    summary = {"schema": 1, "deviceAbi": abi, "audioFile": args.audio.name,
               "audioSha256": sha256(args.audio), "language": args.language,
               "threads": args.threads, "results": collected}
    (results_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(f"Results: {results_dir}")
    if any("error" in result for result in collected):
        raise SystemExit(1)

if __name__ == "__main__":
    main()
