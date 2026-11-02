# mTmux

[English](README.md) · [简体中文](README_zh.md)

Keep working in your remote terminal from your phone.

mTmux is an Android client for SSH and tmux workflows. When you are away from your computer, connect to remote sessions, check task output, reply to terminal agents, and view the documents and images they generate.

[Download APK](https://github.com/Makarov3821/mTmux/releases/latest) · [User guide](docs/usage-en.md) · [Changelog](CHANGELOG.md)

## Who it is for

- Developers who use tmux on a remote Linux server or development machine and want to continue their terminal work on the go.
- Users of terminal agents such as Codex and OpenCode who need to check progress, send follow-up instructions, or read results from their phone.
- Anyone managing multiple servers who wants their regular sessions together in one mobile interface.

## Features

- **Server and session management**: Group and search servers, view tmux sessions and task status, and join an existing session or create a new terminal.
- **Mobile terminal controls**: Direct keyboard input, touch scrolling, Ctrl / Shift / Alt combinations, function keys, and quick replies.
- **Document and image previews**: Tap Markdown, HTML, and common image paths in the terminal to open them. Images support zooming and panning.
- **Offline speech input**: The voice build transcribes Chinese and English speech. Review and edit the result, then send it to the terminal.
- **Connection and environment setup**: Password and private-key authentication, multiple jump hosts, and tmux Doctor to check the server environment.

## Installation and getting started

Download an APK from [GitHub Releases](https://github.com/Makarov3821/mTmux/releases/latest) and choose a build:

| Build | Use case |
| --- | --- |
| standard | Keyboard input and terminal tools |
| voice | Everything in standard, plus offline speech input |

Your phone needs Android 8.0 or later. The server must be accessible over SSH; tmux 3.2a or later is recommended.

1. Add a server with its address, username, and authentication details.
2. Verify the host fingerprint when saving, and optionally run tmux Doctor.
3. Select a tmux session on the home screen, or create a new terminal.

See the [user guide](docs/usage-en.md) for initial setup, jump hosts, speech input, and file previews.

## Development

The project uses Kotlin, Jetpack Compose, and xterm.js. SSH and tmux logic lives in the separate core module.

Install JDK 17, Android SDK Platform 36, and Build Tools 35.0.0. Once the SDK is configured, open the project in Android Studio or build the standard debug APK from the repository root:

```bash
./gradlew :app:assembleStandardDebug
```

See the [development guide](docs/development-en.md) for the module layout, voice builds, web assets, and testing.

## Documentation and feedback

Other reference documents and the changelog are currently in Chinese.

- [User guide](docs/usage-en.md): Connecting to servers, terminal controls, speech input, and file previews.
- [Documentation index](docs/README.md): Development, maintenance, and historical records.
- [Privacy and security](docs/privacy-and-security.md): Credentials, host fingerprints, caches, and screenshot settings.
- [Report an issue](https://github.com/Makarov3821/mTmux/issues): Include the app version, steps to reproduce, and actual behavior. The user guide explains how to export logs.

## License

The project's own code is licensed under **GPL-3.0-only**. See [LICENSE](LICENSE) for the full text and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party copyrights and licenses.
