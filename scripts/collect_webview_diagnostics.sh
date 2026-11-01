#!/usr/bin/env bash
# Read-only device diagnostics. Does not install, clear logs/data, or change WebView flags.
set -euo pipefail
umask 077
root=$(cd "$(dirname "$0")/.." && pwd)
if [[ ${1:-} == --help || $# -gt 2 ]]; then
  echo "Usage: bash scripts/collect_webview_diagnostics.sh [serial] [output-directory]"
  exit 0
fi
adb_bin=${ADB:-}
if [[ -z $adb_bin ]]; then
  sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$root/.tools/android-sdk}}
  if [[ -x $sdk/platform-tools/adb ]]; then adb_bin=$sdk/platform-tools/adb
  else adb_bin=$(command -v adb || true); fi
fi
[[ -n $adb_bin && -x $adb_bin ]] || { echo 'adb not found; set ADB to its executable path.' >&2; exit 1; }
serial=${1:-${ANDROID_SERIAL:-}}
if [[ -z $serial ]]; then
  mapfile -t devices < <("$adb_bin" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  [[ ${#devices[@]} == 1 ]] || { echo 'Select exactly one online test device using the serial argument or ANDROID_SERIAL.' >&2; exit 1; }
  serial=${devices[0]}
fi
[[ $("$adb_bin" -s "$serial" get-state) == device ]] || { echo 'Selected device is not online.' >&2; exit 1; }
out=${2:-$root/.tools/diagnostics/webview-$(date -u +%Y%m%dT%H%M%SZ)-$$}
mkdir -p "$out"
capture() {
  local name=$1
  shift
  if timeout 20 "$adb_bin" -s "$serial" "$@" >"$out/$name" 2>&1; then
    echo "Saved $name"
  else
    echo "Warning: $name collection failed; inspect the saved output." >&2
  fi
}
{
  date -u '+UTC %Y-%m-%dT%H:%M:%SZ'
  git -C "$root" rev-parse HEAD
  echo "serial=$serial"
} >"$out/context.txt"
capture webview-provider.txt shell dumpsys webviewupdate
capture android-api.txt shell getprop ro.build.version.sdk
capture android-abi.txt shell getprop ro.product.cpu.abi
capture build-fingerprint.txt shell getprop ro.build.fingerprint
capture hwui-drawing.txt shell getprop debug.hwui.drawing_enabled
# Exclude crashpad's encoded minidump spam; retain renderer/app native crash summaries.
capture crash.txt logcat -d -b crash -v threadtime 'DEBUG:F' 'libc:F' 'AndroidRuntime:E' '*:S'
echo "Diagnostics: $out"
echo 'Collection is not a WebView test result. Review files before sharing; crash buffers can contain older crashes.'
