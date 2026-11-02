"""Extract a version's user-facing change list for GitHub Releases."""

import argparse
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
HEADER = re.compile(r"^##[ \t]+\[([^\]\r\n]+)\](?:[ \t]+.*)?$")
VERSION = re.compile(r"\d+\.\d+\.\d+(?:[-+][A-Za-z0-9][A-Za-z0-9.+-]*)?")


class ReleaseNotesError(ValueError):
    pass


def version_headers(lines):
    """Ignore headings inside fenced examples rather than publishing an example."""
    fence = None
    for index, line in enumerate(lines):
        if fence:
            character, length = fence
            if re.fullmatch(r" {0,3}" + re.escape(character) + "{" + str(length) + r",}[ \t]*", line):
                fence = None
            continue
        marker = re.match(r"^ {0,3}(`{3,}|~{3,})", line)
        if marker:
            fence = marker[1][0], len(marker[1])
            continue
        header = HEADER.fullmatch(line)
        if header:
            yield index, header[1]


def extract_notes(changelog: str, version: str) -> str:
    version = version.removeprefix("v")
    if not VERSION.fullmatch(version):
        raise ReleaseNotesError("Expected a release version such as 0.9.3 or 0.9.3-rc.1")
    lines = changelog.splitlines()
    headers = list(version_headers(lines))
    matches = [index for index, (_, label) in enumerate(headers) if label == version]
    if not matches:
        raise ReleaseNotesError(f"No CHANGELOG entry for {version}")
    if len(matches) != 1:
        raise ReleaseNotesError(f"Duplicate CHANGELOG entries for {version}")
    position = matches[0]
    start = headers[position][0] + 1
    end = headers[position + 1][0] if position + 1 < len(headers) else len(lines)
    items = []
    seen = set()
    for number in range(start, end):
        line = lines[number]
        if not line.strip():
            continue
        if not line.startswith("- ") or not line[2:].strip():
            raise ReleaseNotesError(f"CHANGELOG line {number + 1}: expected a non-empty, single-line '- ' change")
        item = line[2:].strip()
        if item in seen:
            raise ReleaseNotesError(f"CHANGELOG line {number + 1}: duplicate change")
        seen.add(item)
        items.append("- " + item)
    if not items:
        raise ReleaseNotesError(f"CHANGELOG entry for {version} has no changes")
    return "\n".join(items) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--changelog", type=Path, default=ROOT / "CHANGELOG.md")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.output.resolve() == args.changelog.resolve():
            raise ReleaseNotesError("Output must differ from CHANGELOG")
        notes = extract_notes(args.changelog.read_text(encoding="utf-8"), args.version)
        args.output.write_text(notes, encoding="utf-8")
    except (OSError, UnicodeError, ReleaseNotesError) as error:
        parser.exit(2, f"release notes: {error}\n")


if __name__ == "__main__":
    main()
