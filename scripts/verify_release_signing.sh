#!/usr/bin/env bash
# Build a signed release APK with your keystore and print the certificate fingerprint.
#
#   scripts/verify_release_signing.sh path/to/mtmux-release.jks [alias]
#
# Passwords are read from the terminal without echo and are never written to disk or
# printed. The only output is the public certificate SHA-256, which is safe to share and
# is what CI compares against (repository variable MTMUX_CERT_SHA256).
set -euo pipefail
cd "$(dirname "$0")/.."

keystore=${1:?usage: $0 path/to/release.jks [alias]}
alias=${2:-mtmux}
[ -f "$keystore" ] || { echo "Keystore not found: $keystore" >&2; exit 1; }

# Gradle needs JDK 17. Fall back to the workspace toolchain in .tools/ when JAVA_HOME is unset.
if [ -z "${JAVA_HOME:-}" ]; then
  jdk=$(ls -d "$PWD"/.tools/jdk-17* 2>/dev/null | sort -V | tail -1)
  [ -n "$jdk" ] && export JAVA_HOME="$jdk"
fi
if [ -z "${GRADLE_USER_HOME:-}" ] && [ -d .tools/gradle-home ]; then export GRADLE_USER_HOME="$PWD/.tools/gradle-home"; fi
if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] && [ -d .tools/android-sdk ]; then export ANDROID_HOME="$PWD/.tools/android-sdk"; fi
[ -n "${JAVA_HOME:-}" ] || { echo "JDK 17 not found; set JAVA_HOME" >&2; exit 1; }

keytool=$JAVA_HOME/bin/keytool
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)}}
apksigner=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
[ -x "$apksigner" ] || { echo "apksigner not found; set ANDROID_HOME" >&2; exit 1; }

if [ -z "${MTMUX_KEYSTORE_PASSWORD:-}" ]; then
  read -rsp 'Keystore password: ' MTMUX_KEYSTORE_PASSWORD; echo
fi
if [ -z "${MTMUX_KEY_PASSWORD:-}" ]; then
  read -rsp 'Key password (Enter = same as keystore): ' MTMUX_KEY_PASSWORD; echo
  MTMUX_KEY_PASSWORD=${MTMUX_KEY_PASSWORD:-$MTMUX_KEYSTORE_PASSWORD}
fi
MTMUX_KEYSTORE=$(realpath "$keystore")
MTMUX_KEY_ALIAS=$alias
export MTMUX_KEYSTORE MTMUX_KEY_ALIAS MTMUX_KEYSTORE_PASSWORD MTMUX_KEY_PASSWORD

# Fail fast with a clear message; keytool reads the password from the environment.
if ! "$keytool" -list -keystore "$MTMUX_KEYSTORE" -storepass:env MTMUX_KEYSTORE_PASSWORD -alias "$MTMUX_KEY_ALIAS" >/dev/null 2>&1; then
  echo "FAIL: keystore password is wrong or alias '$MTMUX_KEY_ALIAS' does not exist" >&2
  exit 1
fi

# A wrong key password makes the signing task fail here.
./gradlew :app:assembleRelease --console=plain -q

apk=app/build/outputs/apk/release/app-release.apk
fingerprint=$("$apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ -n "$fingerprint" ] || { echo "FAIL: APK is not signed" >&2; exit 1; }
echo "OK: signed release APK at $apk"
echo "Certificate SHA-256: $fingerprint"
echo "Register it for CI:  gh variable set MTMUX_CERT_SHA256 --body $fingerprint"
