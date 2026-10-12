from datetime import datetime, timezone
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import UUID

from reasonnotes_sync.cli import main
from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.repository import Repository


NOTE_ID = UUID("dddddddd-4444-4444-8444-dddddddddddd")


def time_at(second):
    return datetime(2026, 10, 12, 13, 0, second, tzinfo=timezone.utc)


class CliTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = Repository.open(self.root)
        root = self.repository.create_revision(NOTE_ID, "base", "linux", time_at(0))
        self.linux = self.repository.create_revision(
            NOTE_ID, "Linux preview\nline two", "linux", time_at(1)
        )
        self.android = Revision.create(
            NOTE_ID,
            (root.revision_id,),
            "android",
            time_at(2),
            False,
            "Android preview\nline two",
        )
        self.repository.import_bytes(self.android.serialize(), "android.md")

    def run_cli(self, *arguments):
        output = StringIO()
        result = main(["--root", str(self.root), *arguments], stdout=output)
        self.assertEqual(result, 0)
        return output.getvalue()

    def test_status_reports_exact_heads_and_conflict_state(self):
        output = self.run_cli("status", "--note-id", str(NOTE_ID))

        self.assertIn(f"note={NOTE_ID}", output)
        self.assertIn("conflicted=true", output)
        self.assertIn(f"heads={','.join(sorted((self.linux.revision_id, self.android.revision_id)))}", output)
        self.assertIn("pending=-", output)

    def test_conflicts_reports_device_labels_and_readable_content_previews(self):
        output = self.run_cli("conflicts", "--note-id", str(NOTE_ID))

        self.assertIn(f"revision={self.linux.revision_id}", output)
        self.assertIn("device=linux", output)
        self.assertIn('preview="Linux preview\\nline two"', output)
        self.assertIn(f"revision={self.android.revision_id}", output)
        self.assertIn("device=android", output)
        self.assertIn('preview="Android preview\\nline two"', output)

    def test_resolve_reports_selected_revision_and_all_parent_ids(self):
        output = self.run_cli(
            "resolve",
            "--note-id",
            str(NOTE_ID),
            "--choice",
            "keep-android",
            "--device-id",
            "linux",
            "--modified-utc",
            "2026-10-12T13:00:03Z",
        )

        head = Repository.open(self.root).graph().heads(NOTE_ID)[0]
        self.assertIn("resolution=created", output)
        self.assertIn(f"revision={head.revision_id}", output)
        self.assertIn(f"selected={self.android.revision_id}", output)
        self.assertIn(
            f"parents={','.join(sorted((self.linux.revision_id, self.android.revision_id)))}",
            output,
        )
        self.assertEqual(head.body, self.android.body)

    def test_resolve_skip_reports_no_change(self):
        history_before = {
            path.name
            for path in (self.root / "history" / str(NOTE_ID)).glob("*.md")
        }

        output = self.run_cli(
            "resolve",
            "--note-id",
            str(NOTE_ID),
            "--choice",
            "skip",
            "--device-id",
            "linux",
            "--modified-utc",
            "2026-10-12T13:00:03Z",
        )

        self.assertEqual(output, f"resolution=skipped note={NOTE_ID}\n")
        self.assertEqual(
            {
                path.name
                for path in (self.root / "history" / str(NOTE_ID)).glob("*.md")
            },
            history_before,
        )
        self.assertTrue(Repository.open(self.root).graph().status(NOTE_ID).conflicted)

    def test_exact_revision_id_disambiguates_same_device_heads(self):
        with TemporaryDirectory() as directory:
            root_path = Path(directory)
            repository = Repository.open(root_path)
            root = repository.create_revision(NOTE_ID, "base", "linux", time_at(0))
            first = repository.create_revision(NOTE_ID, "first", "linux", time_at(1))
            second = Revision.create(
                NOTE_ID, (root.revision_id,), "linux", time_at(2), False, "second"
            )
            repository.import_bytes(second.serialize(), "second.md")
            output = StringIO()

            result = main(
                [
                    "--root",
                    str(root_path),
                    "resolve",
                    "--note-id",
                    str(NOTE_ID),
                    "--choice",
                    "keep-linux",
                    "--revision-id",
                    second.revision_id,
                    "--device-id",
                    "linux",
                    "--modified-utc",
                    "2026-10-12T13:00:03Z",
                ],
                stdout=output,
            )

            self.assertEqual(result, 0)
            self.assertIn(f"selected={second.revision_id}", output.getvalue())
            resolution = Repository.open(root_path).graph().heads(NOTE_ID)[0]
            self.assertEqual(resolution.body, "second")
            self.assertEqual(
                resolution.parents, tuple(sorted((first.revision_id, second.revision_id)))
            )


if __name__ == "__main__":
    unittest.main()
