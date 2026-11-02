import contextlib
import io
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from release_notes import ReleaseNotesError, extract_notes, main


class ReleaseNotesTest(unittest.TestCase):
    def test_stable_selects_only_its_changes_without_header_or_neighbor_versions(self):
        source = """# Changelog
## [Unreleased]
- Not released yet.
## [0.9.3] — 2026-11-02 · versionCode 44

- 支持查看文档。
- Fix a connection error.

## [0.9.2]
Legacy internal record with paragraphs.
"""
        self.assertEqual(extract_notes(source, "0.9.3"), "- 支持查看文档。\n- Fix a connection error.\n")

    def test_prerelease_and_v_prefix_use_exact_version(self):
        source = "## [0.9.3]\n- Stable.\n## [0.9.3-rc.1]\n- Candidate.\n## [0.9.3-rc.10]\n- Another candidate.\n"
        self.assertEqual(extract_notes(source, "v0.9.3-rc.1"), "- Candidate.\n")

    def test_missing_version_never_falls_back_to_unreleased_or_latest(self):
        with self.assertRaisesRegex(ReleaseNotesError, "No CHANGELOG entry"):
            extract_notes("## [Unreleased]\n- Pending.\n## [0.9.3]\n- Existing.\n", "0.9.4")

    def test_empty_entry_and_empty_bullet_fail(self):
        for body in ["", "\n\n", "- "]:
            with self.subTest(body=body), self.assertRaises(ReleaseNotesError):
                extract_notes("## [0.9.3]\n" + body + "\n## [0.9.2]\n- Older.\n", "0.9.3")

    def test_duplicate_version_or_change_fails(self):
        for source in ["## [0.9.3]\n- First.\n## [0.9.3]\n- Second.\n", "## [0.9.3]\n- Same.\n- Same.\n"]:
            with self.subTest(source=source), self.assertRaisesRegex(ReleaseNotesError, "[Dd]uplicate"):
                extract_notes(source, "0.9.3")

    def test_paragraphs_subheadings_nested_lists_and_code_blocks_fail(self):
        for extra in ["Build passed.", "### Verification", "  - Nested.", "```\nexample\n```"]:
            with self.subTest(extra=extra), self.assertRaisesRegex(ReleaseNotesError, "single-line"):
                extract_notes("## [0.9.3]\n- A change.\n" + extra + "\n", "0.9.3")

    def test_fenced_version_examples_are_not_real_entries(self):
        for fence in ["```", "~~~~"]:
            source = fence + "markdown\n## [0.9.3]\n- Example.\n" + fence + "\n"
            with self.subTest(fence=fence):
                with self.assertRaisesRegex(ReleaseNotesError, "No CHANGELOG entry"):
                    extract_notes(source, "0.9.3")
                self.assertEqual(extract_notes(source + "## [0.9.3]\n- Real change.\n", "0.9.3"), "- Real change.\n")

    def test_unreleased_and_invalid_version_arguments_fail(self):
        for version in ["Unreleased", "", "vv0.9.3", "0.9.3\n", "../0.9.3"]:
            with self.subTest(version=version), self.assertRaises(ReleaseNotesError):
                extract_notes("## [0.9.3]\n- Change.\n", version)

    def test_cli_writes_notes_and_preserves_existing_output_when_validation_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            changelog = Path(directory, "CHANGELOG.md")
            output = Path(directory, "notes.md")
            changelog.write_text("## [0.9.3]\n- Change.\n", encoding="utf-8")
            argv = ["--changelog", str(changelog), "--output", str(output)]
            main(["--version", "0.9.3", *argv])
            self.assertEqual(output.read_text(encoding="utf-8"), "- Change.\n")
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
                main(["--version", "0.9.4", *argv])
            self.assertEqual(failure.exception.code, 2)
            self.assertEqual(output.read_text(encoding="utf-8"), "- Change.\n")

    def test_cli_rejects_overwriting_changelog(self):
        with tempfile.TemporaryDirectory() as directory:
            changelog = Path(directory, "CHANGELOG.md")
            source = "## [0.9.3]\n- Change.\n"
            changelog.write_text(source, encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                main(["--version", "0.9.3", "--changelog", str(changelog), "--output", str(changelog)])
            self.assertEqual(changelog.read_text(encoding="utf-8"), source)
