#!/usr/bin/env python3
"""Real tmux regression tests; only an isolated temporary socket is modified."""
import fcntl
import os
from pathlib import Path
import pty
import select
import signal
import struct
import subprocess
import tempfile
import termios
import time
import unittest


class TmuxBehaviorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mtmux-p0-")
        self.socket = str(Path(self.temp.name) / "tmux.sock")
        self.clients = []
        self.tmux("new-session", "-d", "-s", "p0", "-x", "120", "-y", "40", "sleep 300")
        self.tmux("set-option", "-g", "status", "off")

    def tmux(self, *args):
        return subprocess.check_output(
            ["tmux", "-S", self.socket, "-f", "/dev/null", *args],
            env={**os.environ, "TMUX": ""}, stderr=subprocess.PIPE, text=True).strip()

    def attach(self, cols=120, rows=40):
        master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        process = subprocess.Popen(["tmux", "-S", self.socket, "attach-session", "-t", "p0"],
                                   stdin=slave, stdout=slave, stderr=slave,
                                   env={**os.environ, "TERM": "xterm-256color", "TMUX": ""})
        os.close(slave)
        self.clients.append((process, master))
        self.wait_for(lambda: len(self.tmux("list-clients").splitlines()) == len(self.clients))
        return process, master

    def wait_for(self, predicate):
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            for _, fd in self.clients:
                if select.select([fd], [], [], 0)[0]:
                    try:
                        os.read(fd, 65536)
                    except OSError:
                        pass
            if predicate():
                return
            time.sleep(0.02)
        self.fail("tmux behavior did not converge within 4 seconds")

    def test_window_and_pane_focus_are_shared(self):
        self.tmux("new-window", "-t", "p0", "-n", "second", "sleep 300")
        self.tmux("split-window", "-t", "p0:1", "sleep 300")
        self.attach()
        self.attach(50, 20)
        self.tmux("select-window", "-t", "p0:1")
        self.tmux("select-pane", "-t", "p0:1.1")
        views = self.tmux("list-clients", "-F", "#{session_id}\t#{window_id}\t#{pane_id}").splitlines()
        self.assertEqual(len(views), 2)
        self.assertEqual(views[0], views[1], "baseline attach does not isolate focus")

    def test_smallest_size_policy_affects_both_clients(self):
        self.tmux("set-window-option", "-t", "p0:0", "window-size", "smallest")
        self.attach()
        mobile_process, mobile = self.attach(50, 20)
        self.wait_for(lambda: self.tmux("display-message", "-p", "-t", "p0:0", "#{window_width}x#{window_height}") == "50x20")
        fcntl.ioctl(mobile, termios.TIOCSWINSZ, struct.pack("HHHH", 12, 42, 0, 0))
        # subprocess has no controlling tty; emulate the SSH server's WINCH delivery.
        mobile_process.send_signal(signal.SIGWINCH)
        self.wait_for(lambda: self.tmux("display-message", "-p", "-t", "p0:0", "#{window_width}x#{window_height}") == "42x12")

    def test_disconnect_keeps_remote_pane_and_other_client_alive(self):
        desktop, _ = self.attach()
        mobile, _ = self.attach(50, 20)
        before = self.tmux("list-panes", "-t", "p0", "-F", "#{pane_id}:#{pane_pid}")
        mobile.terminate()
        mobile.wait(timeout=3)
        self.assertIsNone(desktop.poll())
        self.assertEqual(before, self.tmux("list-panes", "-t", "p0", "-F", "#{pane_id}:#{pane_pid}"))

    def test_discovery_uses_ids_even_with_unusual_names(self):
        self.tmux("rename-window", "-t", "p0:0", "中文\t'$(echo injected)")
        output = self.tmux("list-panes", "-a", "-F", "#{session_id}\t#{window_id}\t#{pane_id}\t#{pane_active}")
        self.assertRegex(output, r"^\$\d+\t@\d+\t%\d+\t[01]$")

    def tearDown(self):
        for process, fd in self.clients:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=3)
            os.close(fd)
        try:
            self.tmux("kill-server")
        finally:
            self.temp.cleanup()


if __name__ == "__main__":
    print(subprocess.check_output(["tmux", "-V"], text=True).strip(), flush=True)
    unittest.main(verbosity=2)
