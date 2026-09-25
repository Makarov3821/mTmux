#!/usr/bin/env python3
"""Synthetic terminal app for end-to-end SGR mouse forwarding; no user data."""
import os
import re
import sys
import termios
import tty

previous = termios.tcgetattr(0)
counts = {"UP": 0, "DOWN": 0, "CLICK": 0}
pending = b""
pattern = re.compile(rb"\x1b\[<(\d+);(\d+);(\d+)([Mm])")


def paint():
    sys.stdout.write("\x1b[H\x1b[32mSYNTHETIC_MOUSE_TUI\x1b[0m\r\n" +
                     "\r\n".join(f"{key}={value}" for key, value in counts.items()) + "\x1b[J")
    sys.stdout.flush()


try:
    tty.setraw(0)
    sys.stdout.write("\x1b[?1049h\x1b[?1000h\x1b[?1006h\x1b[2J")
    paint()
    while True:
        data = os.read(0, 4096)
        if not data:
            break
        pending += data
        for match in pattern.finditer(pending):
            button = int(match[1])
            if match[4] == b"M":
                if button == 64:
                    counts["UP"] += 1
                elif button == 65:
                    counts["DOWN"] += 1
                elif button == 0:
                    counts["CLICK"] += 1
            paint()
        matches = list(pattern.finditer(pending))
        if matches:
            pending = pending[matches[-1].end():]
        pending = pending[-128:]
finally:
    sys.stdout.write("\x1b[?1000l\x1b[?1006l\x1b[?1049l")
    sys.stdout.flush()
    termios.tcsetattr(0, termios.TCSANOW, previous)
