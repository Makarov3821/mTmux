# User guide

[English](usage-en.md) · [简体中文](usage-zh.md)

This guide describes the current implementation on the main branch. See the [changelog](../CHANGELOG.md) for released versions. Additional reference documents linked below are currently in Chinese.

## Installation and server setup

Choose the standard or voice APK from [Releases](https://github.com/Makarov3821/mTmux/releases/latest). Both use the same application ID, so install one build. The voice build adds offline speech input and has a larger download size.

Your phone needs Android 8.0 or later. The server needs an SSH service, a login account, and tmux. File previews also require SFTP. tmux 3.2a or later is recommended; Doctor displays an upgrade warning for older versions.

For an initial tmux setup, start with:

```tmux
set -g mouse on
set -g history-limit 10000
```

Merge these settings into your existing configuration as needed. `mouse on` enables touch scrolling and clicks. Changes to the history limit generally apply to windows created afterward. See [tmux Doctor](testing/tmux-doctor.md) for custom wheel binding checks and the version baseline.

## Adding a server

1. Add a server from the home screen and enter its alias, address, port, and username.
2. Choose password or private key authentication. Import a key from a file or paste it; enter its passphrase separately if it is encrypted.
3. Select Save. The app connects, displays any untrusted host fingerprint, and checks authentication. Verify the fingerprint before continuing. Jump hosts are checked one at a time.
4. Once verification succeeds, the configuration is saved and you return to the home screen. You can then choose to run tmux Doctor.

If verification fails, edit the configuration, retry, or select **Save anyway**. After saving without verification, use **Verify fingerprint** in the server menu or complete verification during a later connection. See [privacy and security](privacy-and-security.md) for changed host keys.

When pasting a private key, include the complete BEGIN and END lines. Enter the passphrase in the authentication section. OpenSSH and PEM text private keys are supported; passing the format check does not replace SSH authentication.

For jump hosts, enable Jump Host in the advanced options and configure the route from phone to jump hosts to target server, or copy a saved server. Its existing jump host chain is copied as well. Later edits to the original server do not update the copy. Servers must allow SSH TCP forwarding. The advanced options also let you change the tmux executable path.

**Enable mouse when connecting** is on by default. It enables mouse support for the selected session, affecting desktop clients attached to that session too. It does not rewrite `.tmux.conf`, and disconnecting does not restore the previous session setting.

## Home screen, refresh, and sessions

Select an existing tmux entry to enter that terminal. Expand windows with multiple panes to choose a pane. The server's new terminal button asks for a separate session name, checks for duplicates, then creates and opens the session. You can also open a plain SSH shell directly.

Create folders, move servers, search, and change the sort order. Pulling down to refresh updates ungrouped servers and those in expanded folders. Each server also has its own refresh action.

On startup or when returning to the home screen from the background, automatic refresh uses a five-minute interval per server. Returning from a terminal bypasses the interval only for the server just used, while still respecting collapsed folders. Other servers follow the normal interval.

Task colors reflect the most recent refresh: red may need attention, yellow indicates activity, green indicates completion or readiness, and gray means unknown, expired, or a refresh failure. These are estimates based on terminal titles and output, rather than live updates. Refresh manually to get a new snapshot.

## tmux Doctor

Run Doctor after saving and verifying a server, or rerun it from the server's three-dot menu. The report lists checks for SSH, the tmux version, session access, mouse support, history, and other environment settings.

When tmux is confirmed unavailable, creating a new tmux terminal is temporarily disabled; a plain SSH shell remains available. Editing the server configuration or rerunning the check updates this assessment. The unavailable record expires after five minutes, allowing another check.

For a first-time tmux setup that meets the initialization conditions, Doctor offers a configuration preview, a history limit, and a first-time setup confirmation before creating a minimal configuration. Servers with an existing configuration can use the report to make adjustments. See the [Doctor reference](testing/tmux-doctor.md) for creation conditions, backups, and undo behavior.

## Terminal input and scrolling

Tap the terminal or the keyboard button at the top to type directly. Chinese text is sent after IME composition is confirmed. The current remote program handles Enter.

- Tap Ctrl, Shift, or Alt before entering a character. Multiple modifiers can be combined and are released after the next keystroke.
- The bottom shortcut bar cycles through pages of common keys, arrow keys, and function keys.
- Quick replies open in the editor card. If a draft already exists, choose whether to append or replace it.
- Use the top edit button or **Tools → Edit text** for longer text. **Insert into terminal** writes to the current target without pressing Enter automatically.
- **Tools → Select & copy** opens a copy of the loaded terminal text. Use the system selection handles to copy it. Scroll in the terminal first to load earlier content.

With remote touch enabled, vertical swipes send wheel events, taps send left clicks, and a long press followed by release sends a right click. Scroll speed ranges from 1× to 10×. Higher settings send more wheel events; tmux or the remote program determines the number of lines scrolled. A plain shell without mouse support scrolls the phone's local terminal history.

A tmux attachment shares display focus and size with desktop clients. If the reply target changes, you are reading history, or synchronized input is enabled, the app may refuse input and retain the draft. Check the current pane before continuing. See [terminal input](testing/terminal-direct-input.md) and [keyboard validation](testing/terminal-keyboard.md) for details.

## Speech input

The voice build requests microphone permission on first use. Hold the speech button, wait until preparation finishes and the recording halo appears, then speak. The recording indicator shows the remaining time and changes color near the limit.

Release to transcribe, or slide upward to cancel. Each recording can last up to 60 seconds. Review, edit, or append to the transcription in the editor card before inserting it into the terminal. Recognition supports Chinese, English, and mixed speech, with punctuation and capitalization.

## Document and image previews

Tap a Markdown, HTML, or image path in the terminal to open a preview, such as `docs/readme.md`, `/home/me/project/report.html`, or `images/result.svg`. External web links open in the system browser.

If the path is wrong, edit the complete path at the top of the preview and retry. Relative paths use the current pane directory or login directory as candidates; absolute paths specify a file in another directory directly. Relative and absolute image paths within documents are supported too. PNG, JPEG, WebP, and SVG images can be viewed, zoomed, and panned.

Document text is read in full. Embedded images load first in the visible area and the adjacent areas above and below it. Fast scrolling pauses outdated downloads; loading resumes at the new position once scrolling settles. Previously downloaded images can be reused from the current preview's cache.

Under **Settings → File previews**, adjust the single file and cache limits, which default to 10 MiB and 50 MiB. The cache limit cannot be lower than the single file limit. Changes take effect the next time you open a preview. SVG uses the same single file limit as other images. HTML previews are static; see [privacy and security](privacy-and-security.md) for the supported scope.

## Connection retention and feedback

Under **Settings → SSH connection retention**, None, Few, Medium, Many, and Very many retain 0, 1, 2, 3, and 5 idle SSH chains respectively. The default is Medium. Idle chains are retained for one minute and closed when the app goes into the background. Connections in use are counted separately. Idle chains do not poll task status.

Reopen a session from the home screen, or use reconnect after a disconnection. Whether a tmux session survives disconnection depends on the server configuration; a plain shell behaves differently.

When reporting a problem, include the app version, phone model and Android version, steps to reproduce, and expected and actual behavior. After reproducing it, export a log from **Settings → Debug log → Generate log** and include the approximate time of the problem. The snapshot is taken when the log is generated, so export again to include later actions. See [connection performance](testing/connection-performance.md) for log fields and collection details.
