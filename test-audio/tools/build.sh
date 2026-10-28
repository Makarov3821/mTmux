#!/usr/bin/env bash
set -euo pipefail
LAB_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(dirname -- "$LAB_ROOT")"
if [[ ! -f "$LAB_ROOT/local.properties" && -f "$PROJECT_ROOT/local.properties" ]]; then
  cp "$PROJECT_ROOT/local.properties" "$LAB_ROOT/local.properties"
fi
python3 "$LAB_ROOT/tools/prepare.py" runtime
if [[ -d "$PROJECT_ROOT/.tools/jdk-17.0.20.1+1" ]]; then
  export JAVA_HOME="$PROJECT_ROOT/.tools/jdk-17.0.20.1+1"
  export GRADLE_USER_HOME="$PROJECT_ROOT/.tools/gradle-home"
  export ANDROID_USER_HOME="$PROJECT_ROOT/.tools/android-user"
fi
# Force fresh ZIP layout when switching ABI sets.
rm -f "$LAB_ROOT/app/build/outputs/apk/release/app-release.apk"
"$PROJECT_ROOT/gradlew" -p "$LAB_ROOT" --no-daemon -Pkotlin.compiler.execution.strategy=in-process :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --console=plain --max-workers=2
mkdir -p "$LAB_ROOT/outputs"
cp "$LAB_ROOT/app/build/outputs/apk/release/app-release.apk" "$LAB_ROOT/outputs/audio-lab-0.2.0-arm64.apk"
cd "$LAB_ROOT/outputs"
sha256sum audio-lab-0.2.0-arm64.apk > audio-lab-0.2.0-arm64.apk.sha256
