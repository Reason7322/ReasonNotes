"""Title/body projection regression tests; immutable Markdown remains authoritative."""

import unittest

from reasonnotes_app.note_document import change_content, change_title, split_note


class NoteDocumentTests(unittest.TestCase):
    def test_source_is_not_rewritten_by_reading_fields(self):
        cases = [
            ("# Sleep log\n\nBed Time:\nWake up:", "Sleep log", "Bed Time:\nWake up:"),
            ("Sleep log\nBed Time:\nWake up:", "Sleep log", "Bed Time:\nWake up:"),
            ("\n## Existing heading\r\n\r\nHello\r\n", "Existing heading", "Hello\r\n"),
            ("", "", ""),
        ]
        for source, title, body in cases:
            with self.subTest(source=source):
                parts = split_note(source)
                self.assertEqual(title, parts.title)
                self.assertEqual(body, parts.content)

    def test_renaming_keeps_body_verbatim(self):
        source = "Sleep log\nBed Time:\nWake up:\n"
        updated = change_title(source, "New sleep log")
        self.assertEqual("# New sleep log\nBed Time:\nWake up:\n", updated)
        self.assertEqual(split_note(source).content, split_note(updated).content)

    def test_body_edits_preserve_original_first_line_and_layout(self):
        source = "# Sleep log\n\nBed Time:\nWake up:"
        self.assertEqual(
            "# Sleep log\n\nBed Time: 22:00\nWake up: 07:00",
            change_content(source, "Bed Time: 22:00\nWake up: 07:00"),
        )
        legacy = "Plain first line\nSecond line"
        self.assertEqual("Plain first line\nNew body", change_content(legacy, "New body"))

    def test_body_before_title_remains_body(self):
        updated = change_content("", "Bed Time:\nWake up:")
        self.assertEqual("# Untitled\n\nBed Time:\nWake up:", updated)
        self.assertEqual(
            "# Sleep log\n\nBed Time:\nWake up:",
            change_title(updated, "Sleep log"),
        )

    def test_title_rename_handles_no_newline_and_whitespace(self):
        self.assertEqual("# New\n", change_title("# Old\n", "New"))
        self.assertEqual("# New", change_title("# Old", "New"))
        self.assertEqual("# Untitled\n\n", change_title("", "   "))
        self.assertEqual("# A B\n\n", change_title("", "A\nB"))
        self.assertEqual("\n\n# First", change_title("\n\ncontent", "First"))

    def test_saved_title_and_body_survive_reopen_and_preserve_legacy_note(self):
        from tempfile import TemporaryDirectory
        from reasonnotes_app.service import NoteService
        from reasonnotes_sync.repository import Repository

        with TemporaryDirectory() as folder:
            root = __import__("pathlib").Path(folder)
            repository = Repository.open(root, local_device="linux", target_device="android")
            service = NoteService(repository)
            note_id = service.create_note("Sleep log\nBed Time:\nWake up:")
            old = service.open_note(note_id)
            self.assertEqual("Sleep log\nBed Time:\nWake up:", old)
            self.assertEqual(old, service.save_note(note_id, old).body)
            edited = change_title(change_content(old, "Bed Time: 23:00\nWake up: 07:00"), "Sleep log")
            service.save_note(note_id, edited)

            restarted = NoteService(Repository.open(root, local_device="linux", target_device="android"))
            self.assertEqual("# Sleep log\nBed Time: 23:00\nWake up: 07:00", restarted.open_note(note_id))
            self.assertEqual("Sleep log", restarted.list_notes()[0].title)

    def test_title_only_new_body_gets_separator(self):
        self.assertEqual("# Title\n\nText", change_content("# Title", "Text"))


if __name__ == "__main__":
    unittest.main()
