#!/usr/bin/env bash
# Run Android instrumentation one test class at a time through the disposable SSH/tmux fixture,
# keeping each class's raw results. One class per run so a crash cannot hide later classes.
#
# usage: scripts/run_android_matrix.sh <standard|voice> <label> [repo-dir] [Class ...]
#   label     output folder under .tools/diagnostics/matrix/
#   repo-dir  defaults to this checkout; pass a `git worktree` path to run an older commit
#   Class     defaults to every *Test.kt in app/src/androidTest plus component/voice classes
# Summarize afterwards: python3 scripts/summarize_android_results.py .tools/diagnostics/matrix/<label>
set -u
here=$(cd "$(dirname "$0")/.." && pwd)
variant=${1:?standard or voice}; label=${2:?label}; repo=${3:-$here}; shift $(( $# < 3 ? $# : 3 ))
tools=${MTMUX_TOOLS:-$here/.tools}
export JAVA_HOME=${JAVA_HOME:-$tools/jdk-17.0.20.1+1} GRADLE_USER_HOME=${GRADLE_USER_HOME:-$tools/gradle-home}
sdk=${ANDROID_HOME:-$tools/android-sdk}
export ANDROID_HOME=$sdk ANDROID_SDK_ROOT=$sdk PATH=$sdk/platform-tools:$PATH
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the dedicated test emulator/device}"
export ANDROID_SERIAL
out=$here/.tools/diagnostics/matrix/$label; mkdir -p "$out"
cd "$repo"
if [ $# -gt 0 ]; then classes="$*"; else
  classes=$(ls app/src/androidTest/java/dev/mtmux/*Test.kt | xargs -n1 basename | sed 's/\.kt$//')
  grep -q "class UiRedesignComponentTest" app/src/androidTest/java/dev/mtmux/*.kt 2>/dev/null && classes="$classes UiRedesignComponentTest"
  [ "$variant" = voice ] && classes="$classes VoiceProductTest"
fi
{ date -u '+UTC %FT%TZ'; git -C "$repo" rev-parse HEAD; git -C "$repo" status --short | head -20; echo "serial=$ANDROID_SERIAL variant=$variant"; } > "$out/context.txt"
for c in $classes; do
  adb shell am force-stop dev.mtmux >/dev/null 2>&1
  timeout 2400 python3 scripts/test_ssh.py --android --android-variant "$variant" --test-class "dev.mtmux.$c" > "$out/$c.console.log" 2>&1
  echo "rc=$?" >> "$out/$c.console.log"
  rm -rf "$out/$c"; cp -r "app/build/outputs/androidTest-results/connected/debug/flavors/$variant" "$out/$c" 2>/dev/null
done
echo DONE > "$out/DONE"
echo "Results: $out"
