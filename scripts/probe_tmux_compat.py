#!/usr/bin/env python3
"""Research probe: real tmux binaries, temporary sockets, mouse on only.

Usage: python3 scripts/probe_tmux_compat.py --binary /path/to/tmux [--binary ...]
Never uses the user's default server or configuration. A synthetic program tests
mouse forwarding; this is not an Android or real Agent end-to-end test.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import shlex
import struct
import subprocess
import sys
import tempfile
import termios
import time


def probe(binary):
    result = {"binary": str(Path(binary).resolve())}
    with tempfile.TemporaryDirectory(prefix="mtd-") as temporary:
        root = Path(temporary)
        config = root / "tmux.conf"
        config.write_text("set -g mouse on\n")
        env = {**os.environ, "TMUX": "", "TERM": "xterm-256color", "LC_ALL": "C.UTF-8"}
        base = [result["binary"], "-S", str(root / "socket"), "-f", str(config)]
        client = None
        master = None

        def command(*args):
            return subprocess.check_output(base + list(args), env=env, stderr=subprocess.PIPE,
                                           text=True, timeout=5).strip()

        def drain():
            if master is not None:
                while select.select([master], [], [], 0)[0]:
                    try:
                        if not os.read(master, 65536):
                            break
                    except OSError:
                        break

        def wait(predicate):
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                drain()
                if predicate():
                    return
                time.sleep(.03)
            raise RuntimeError("Probe did not converge within five seconds")

        try:
            result["version"] = command("-V")
            command("new-session", "-d", "-s", "doctor", "-x", "80", "-y", "24",
                    "i=0; while [ $i -lt 150 ]; do printf 'fixture line %s\\n' \"$i\"; i=$((i+1)); done; sleep 90")
            wait(lambda: int(command("display-message", "-p", "-t", "doctor:0.0", "#{history_size}")) > 50)
            result["mouse"] = command("show-options", "-A", "-v", "-t", "doctor", "mouse")
            command("rename-window", "-t", "doctor:0", "中文")
            result["length_modifier"] = command("display-message", "-p", "-t", "doctor:0.0", "#{n:#{window_name}}:#{window_name}")
            result["mtmux_length_modifier_ok"] = result["length_modifier"] == "6:中文"
            result["identity"] = command("display-message", "-p", "-t", "doctor:0.0",
                                         "#{pid}:#{start_time}:#{session_id}:#{window_id}:#{pane_id}:#{pane_pid}:#{pane_dead}")
            result["wheel_bindings"] = [line for line in command("list-keys").splitlines()
                                         if "WheelUpPane" in line or "WheelDownPane" in line]
            master, slave = pty.openpty()
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
            client = subprocess.Popen(base + ["attach-session", "-t", "doctor"], env=env,
                                      stdin=slave, stdout=slave, stderr=slave)
            os.close(slave)
            wait(lambda: bool(command("list-clients")))
            os.write(master, b"\x1b[<64;10;10M")
            wait(lambda: command("display-message", "-p", "-t", "doctor:0.0", "#{pane_in_mode}") == "1")
            result["wheel_up_enters_history"] = True
            result["history_mode"] = command("display-message", "-p", "-t", "doctor:0.0", "#{pane_mode}")
            os.write(master, b"\x1b[<65;10;10M" * 20)
            wait(lambda: command("display-message", "-p", "-t", "doctor:0.0", "#{pane_in_mode}") == "0")
            result["wheel_down_exits_history"] = True

            received = root / "received"
            ready = root / "ready"
            fixture = root / "mouse_fixture.py"
            fixture.write_text(
                "import os,sys,tty\nfrom pathlib import Path\n"
                "tty.setraw(0)\nsys.stdout.write('\\x1b[?1000h\\x1b[?1006h');sys.stdout.flush()\n"
                f"Path({str(ready)!r}).touch()\n"
                f"with open({str(received)!r},'wb',buffering=0) as output:\n"
                " while True:\n  data=os.read(0,1024)\n  if not data:break\n  output.write(data)\n")
            command("new-window", "-t", "doctor", "-n", "mouse-fixture",
                    shlex.join([sys.executable, str(fixture)]))
            wait(lambda: ready.exists() and command("display-message", "-p", "-t", "doctor:1.0", "#{mouse_any_flag}") == "1")
            os.write(master, b"\x1b[<64;10;10M\x1b[<65;10;10M")
            wait(lambda: received.exists() and b"[<64;" in received.read_bytes() and b"[<65;" in received.read_bytes())
            result["wheel_up_down_forwarded_to_mouse_app"] = True
        except Exception as error:
            result["probe_error"] = str(error)
            if isinstance(error, subprocess.CalledProcessError):
                result["stderr"] = error.stderr
        finally:
            if client is not None:
                if client.poll() is None:
                    client.terminate()
                client.wait(timeout=5)
            if master is not None:
                os.close(master)
            subprocess.run(base + ["kill-server"], env=env, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=5)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", action="append", required=True)
    args = parser.parse_args()
    results = [probe(binary) for binary in args.binary]
    print(json.dumps(results, ensure_ascii=False, indent=2))
    sys.exit(1 if any("probe_error" in row for row in results) else 0)
