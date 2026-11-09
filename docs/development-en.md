# Development guide

[English](development-en.md) · [简体中文](development-zh.md)

The detailed testing and release references linked below are currently in Chinese.

## Environment and modules

Use JDK 17, Android SDK Platform 36, Build Tools 35.0.0, and the repository's Gradle Wrapper. Open the project in Android Studio and configure the SDK. For command-line builds, set `ANDROID_HOME` and run from the repository root:

```bash
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:assembleStandardDebug
```

`local.properties` contains local machine settings and is ignored by Git. The first build needs to download Gradle and dependencies from the Maven and Google repositories.

| Directory | Responsibility |
| --- | --- |
| `app/` | Compose screens, input, terminal WebView, previews, and server configuration |
| `core/` | SSH, tmux commands and parsing, host trust, connection reuse, and SFTP |
| `web/` | Source for the xterm.js bridge, document rendering, and browser tests |
| `voice/` | X-ASR model installation, recording, and inference integration |
| `scripts/` | Pinned asset downloads, test fixtures, APK validation, and release note generation |
| `test-audio/` | Independent speech model comparisons, outside the main app's build and release |

Connections use JSch, terminal rendering uses xterm.js, and Markdown and static HTML previews use marked and DOMPurify. See [third-party notices](../THIRD_PARTY_NOTICES.md) for dependencies, pinned versions, and licenses.

## Building both variants

Settings displays the package's distribution channel and reads the version from `BuildConfig.VERSION_NAME`. The default channel is GitHub. Google Play builds use `-Pmtmux.distributionChannel=play`; this applies to both standard and voice.

Build the standard debug APK:

```bash
./gradlew :app:assembleStandardDebug
```

The output is `app/build/outputs/apk/standard/debug/app-standard-debug.apk`.

For voice, fetch the pinned X-ASR model and Android runtime before building:

```bash
python3 scripts/fetch_voice.py
./gradlew :app:assembleVoiceDebug
```

The output is `app/build/outputs/apk/voice/debug/app-voice-debug.apk`. Download sources, member sizes, and SHA-256 hashes are pinned in `voice/model.lock.json` and `voice/runtime.lock.json`. The runtime uses prebuilt packages, so building the main app does not require compiling the speech C++ code locally.

Local debug builds and official releases use different signing keys. See the [release procedure](testing/webview-and-release-runbook.md#固定签名与-github-发布) for signing, upgrade compatibility, and certificate configuration.

## Changing the terminal or preview pages

Use Node.js 22 or later. Bundled terminal and preview assets are committed with the source. After changing `web/`, rebuild from the repository root:

```bash
npm --prefix web ci --ignore-scripts
npm --prefix web test
npm --prefix web run bundle
```

The generated directories are `app/src/main/assets/terminal/` and `app/src/main/assets/preview/`. Commit them together with the web source changes. Browser regression checks require desktop Chromium or Chrome; set `CHROME_BIN` to specify the executable:

```bash
npm --prefix web run test:browser
```

Run this command from the repository root. Record desktop browser and Android WebView results separately.

## Testing

```bash
./gradlew :core:test :app:testStandardDebugUnitTest :app:lintStandardDebug
python3 -m unittest discover -s scripts/tests
```

For voice changes, also run `:voice:testDebugUnitTest`, `:app:testVoiceDebugUnitTest`, and `:app:lintVoiceDebug`. Fetch the assets first when testing voice assets.

Real SSH, SFTP, and PTY integration tests use isolated fixtures:

```bash
python3 scripts/test_tmux.py
python3 scripts/test_ssh.py --probe-pty
```

These require local tmux, an OpenSSH server, and its key tools. Tests that depend on fixtures may be skipped in a plain `:core:test` run. Check the actual execution counts and XML results rather than relying only on a successful Gradle exit.

Use a dedicated emulator or device for Android tests. Set `ANDROID_SERIAL`, then follow the [WebView test procedure](testing/webview-and-release-runbook.md) and [change validation procedure](testing/ui-redesign-change-and-release-routine.md). Run the documentation screenshot collector separately; it does not count as functional regression testing. See [viewport and cache testing](testing/preview-viewport-cache.md) for the latest focused file preview checks.

## Commits and releases

English UI strings are in `app/src/main/res/values/strings.xml`, and Chinese strings are in `values-zh/strings.xml`. Keep both updated. The core module emits structured errors and progress for the UI layer to translate; diagnostic fields remain in English.

Separate commits by feature, fix, and documentation changes, and include relevant validation results. Put behavior designs in `docs/design/`, implementation and delivery records in `docs/releases/`, and test records in `docs/testing/`. See [AGENT.md](../AGENT.md) for project editing conventions.

Collect user-facing changes under Unreleased in CHANGELOG, then prepare the matching version entry before release. The release body is extracted by a script; see the [release note policy](release-notes.md). Historical speech experiments, old build commands, and earlier results remain in their corresponding version records rather than serving as current build instructions.
