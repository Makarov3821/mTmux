#!/usr/bin/env python3
"""Disposable OpenSSH fixture on loopback; no user authorized_keys/config is modified."""
import os
import argparse
import base64
from pathlib import Path
import pwd
import shutil
import socket
import shlex
import sys
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android", action="store_true", help="Run Android UI + shell tests on an attached test device/emulator")
    parser.add_argument("--test-class", help="Optional Android instrumentation class or class#method filter")
    options = parser.parse_args()
    sshd = shutil.which("sshd") or "/usr/sbin/sshd"
    if not Path(sshd).is_file():
        raise SystemExit("Install openssh-server to run this integration test")
    with tempfile.TemporaryDirectory(prefix="mtmux-ssh-") as temporary:
        folder = Path(temporary)
        for name, phrase in [("host", ""), ("client", ""), ("encrypted", "p0-test-only")]:
            subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", phrase, "-f", str(folder / name)], check=True)
        (folder / "authorized_keys").write_text((folder / "client.pub").read_text() + (folder / "encrypted.pub").read_text())
        with socket.socket() as candidate:
            candidate.bind(("127.0.0.1", 0))
            port = candidate.getsockname()[1]
        user = pwd.getpwuid(os.getuid()).pw_name
        config = folder / "sshd_config"
        config.write_text(f"""ListenAddress 127.0.0.1
Port {port}
HostKey {folder / 'host'}
PidFile {folder / 'sshd.pid'}
AuthorizedKeysFile {folder / 'authorized_keys'}
StrictModes no
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
PermitRootLogin prohibit-password
AllowUsers {user}
AllowTcpForwarding yes
X11Forwarding no
PermitTunnel no
LogLevel ERROR
""")
        # Dedicated tmux socket: tests never touch the user's default server.
        # Mirror the user's desktop wheel bindings in an isolated server only.
        tmux_config = folder / "tmux.conf"
        tmux_config.write_text("""set -g mouse on
set -g history-limit 500000
bind -n WheelUpPane {
    if -F '#{||:#{pane_in_mode},#{mouse_any_flag}}' {
        send -M
    } {
        copy-mode -e
    }
}
bind -n WheelDownPane {
    if -F '#{||:#{pane_in_mode},#{mouse_any_flag}}' {
        send -M
    }
}
""")
        wrapper = folder / "tmux-fixture"
        wrapper.write_text("#!/bin/sh\nexec tmux -S " + shlex.quote(str(folder / "tmux.sock")) + ' -f ' + shlex.quote(str(tmux_config)) + ' "$@"\n')
        wrapper.chmod(0o700)
        subprocess.run([str(wrapper), "new-session", "-d", "-s", "测试会话", "-n", "工作窗口", "-x", "100", "-y", "24",
                        "printf 'BEFORE_ATTACH_HISTORY\\n'; i=0; while [ $i -lt 160 ]; do printf 'history-%03d\\n' \"$i\"; i=$((i+1)); done; exec sleep 300"], check=True)
        if options.android:
            shell = folder / "fixture-shell"
            shell.write_text('#!/bin/sh\nif [ -n "$SSH_ORIGINAL_COMMAND" ]; then exec /bin/sh -c "$SSH_ORIGINAL_COMMAND"; fi\n' + "cd /tmp\nprintf '\\033[32mMTMUX_SSH_WELCOME\\033[0m\\n'\nexport PS1='mtmux-test$ '\nexec /bin/sh -i\n")
            shell.chmod(0o700)
            with config.open("a") as configuration:
                configuration.write(f"ForceCommand {shell}\n")
        with (folder / "server.log").open("w+") as log:
            server = subprocess.Popen([sshd, "-D", "-e", "-f", str(config)], stdout=log, stderr=log)
            try:
                for _ in range(100):
                    if server.poll() is not None:
                        log.seek(0)
                        raise RuntimeError("Fixture sshd failed: " + log.read())
                    try:
                        with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                            break
                    except OSError:
                        time.sleep(0.05)
                else:
                    raise RuntimeError("Fixture did not become ready")
                env = {**os.environ,
                       "MTMUX_TEST_TMUX": str(wrapper), "MTMUX_TEST_PORT": str(port), "MTMUX_TEST_USER": user,
                       "MTMUX_TEST_KEY": str(folder / "client"),
                       "MTMUX_TEST_ENCRYPTED_KEY": str(folder / "encrypted"),
                       "MTMUX_TEST_HOST_KEY": (folder / "host.pub").read_text().split()[1]}
                if options.android:
                    adb = os.environ.get("ADB", "adb")
                    subprocess.run([adb, "reverse", f"tcp:{port}", f"tcp:{port}"], check=True)
                    try:
                        # All credentials here are generated, temporary fixture keys, never user keys.
                        command = ["./gradlew", ":app:connectedDebugAndroidTest", "--console=plain",
                                   f"-Pandroid.testInstrumentationRunnerArguments.fixturePort={port}",
                                   f"-Pandroid.testInstrumentationRunnerArguments.fixtureTmux={wrapper}",
                                   "-Pandroid.testInstrumentationRunnerArguments.fixtureMouseCommand=" + shlex.join([sys.executable, str(Path(__file__).resolve().with_name("mouse_tui_fixture.py"))]),
                                   f"-Pandroid.testInstrumentationRunnerArguments.fixtureUser={user}",
                                   "-Pandroid.testInstrumentationRunnerArguments.fixtureKey=" + base64.b64encode((folder / "client").read_bytes()).decode(),
                                   "-Pandroid.testInstrumentationRunnerArguments.fixtureEncryptedKey=" + base64.b64encode((folder / "encrypted").read_bytes()).decode(),
                                   "-Pandroid.testInstrumentationRunnerArguments.fixtureHostKey=" + env["MTMUX_TEST_HOST_KEY"]]
                        if options.test_class:
                            command.append("-Pandroid.testInstrumentationRunnerArguments.class=" + options.test_class)
                        result = subprocess.run(command, env=env)
                        if result.returncode:
                            raise RuntimeError("Android fixture tests failed; inspect app/build/reports/androidTests")
                    finally:
                        subprocess.run([adb, "reverse", "--remove", f"tcp:{port}"], check=False)
                else:
                    subprocess.run(["./gradlew", ":core:test", "--rerun-tasks", "--console=plain"], env=env, check=True)
                print("PASS: disposable OpenSSH integration tests", flush=True)
            finally:
                subprocess.run([str(wrapper), "kill-server"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                server.terminate()
                server.wait(timeout=5)


if __name__ == "__main__":
    main()
