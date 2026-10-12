from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import UUID

from reasonnotes_sync.conflicts import (
    ConflictSelectionError,
    list_conflict,
    resolve_conflict,
)
from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.repository import Repository


NOTE_ID = UUID("cccccccc-3333-4333-8333-cccccccccccc")


def moment(second):
    return datetime(2026, 10, 12, 12, 0, second, tzinfo=timezone.utc)


class ConflictResolutionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = Repository.open(self.root)

    def make_conflict(self, *, android_deleted=False, both_linux=False):
        root = self.repository.create_revision(NOTE_ID, "base", "linux", moment(0))
        linux = self.repository.create_revision(
            NOTE_ID, "Linux version\nsecond line", "linux", moment(1)
        )
        android = Revision.create(
            NOTE_ID,
            (root.revision_id,),
            "linux" if both_linux else "android",
            moment(2),
            android_deleted,
            "" if android_deleted else "Android version\nsecond line",
        )
        self.repository.import_bytes(android.serialize(), "android.md")
        return root, linux, android

    def history_ids(self):
        return {
            path.stem
            for path in (self.root / "history" / str(NOTE_ID)).glob("*.md")
        }

    def test_lists_readable_device_labeled_previews(self):
        _, linux, android = self.make_conflict()

        candidates = list_conflict(self.repository, NOTE_ID)

        by_id = {candidate.revision_id: candidate for candidate in candidates}
        self.assertEqual(by_id[linux.revision_id].origin_device, "linux")
        self.assertEqual(by_id[linux.revision_id].preview, "Linux version\nsecond line")
        self.assertEqual(by_id[android.revision_id].origin_device, "android")
        self.assertEqual(by_id[android.revision_id].preview, "Android version\nsecond line")

    def test_keep_linux_creates_multi_parent_resolution_and_preserves_history(self):
        root, linux, android = self.make_conflict()

        resolution = resolve_conflict(
            self.repository, NOTE_ID, "keep-linux", "linux", moment(3)
        )

        self.assertEqual(resolution.body, linux.body)
        self.assertFalse(resolution.deleted)
        self.assertEqual(
            resolution.parents, tuple(sorted((linux.revision_id, android.revision_id)))
        )
        self.assertEqual(self.repository.graph().heads(NOTE_ID), (resolution,))
        self.assertEqual(
            (self.root / "notes" / f"{NOTE_ID}.md").read_text(), linux.body
        )
        self.assertEqual(
            self.history_ids(),
            {root.revision_id, linux.revision_id, android.revision_id, resolution.revision_id},
        )

    def test_keep_android_creates_authoritative_android_content(self):
        _, linux, android = self.make_conflict()

        resolution = resolve_conflict(
            self.repository, NOTE_ID, "keep-android", "linux", moment(3)
        )

        self.assertEqual(resolution.body, android.body)
        self.assertEqual(
            resolution.parents, tuple(sorted((linux.revision_id, android.revision_id)))
        )
        self.assertEqual(self.repository.graph().heads(NOTE_ID), (resolution,))

    def test_skip_performs_no_writes_and_preserves_conflict(self):
        _, linux, android = self.make_conflict()
        history_before = self.history_ids()
        index_before = (self.root / "state" / "index.json").read_bytes()
        conflict_before = (self.root / "conflicts" / f"{NOTE_ID}.json").read_bytes()
        view_before = (self.root / "notes" / f"{NOTE_ID}.md").read_bytes()

        result = resolve_conflict(
            self.repository, NOTE_ID, "skip", "linux", moment(3)
        )

        self.assertIsNone(result)
        self.assertEqual(self.history_ids(), history_before)
        self.assertEqual((self.root / "state" / "index.json").read_bytes(), index_before)
        self.assertEqual(
            (self.root / "conflicts" / f"{NOTE_ID}.json").read_bytes(), conflict_before
        )
        self.assertEqual((self.root / "notes" / f"{NOTE_ID}.md").read_bytes(), view_before)
        self.assertEqual(
            self.repository.graph().status(NOTE_ID).head_ids,
            tuple(sorted((linux.revision_id, android.revision_id))),
        )

    def test_edit_versus_delete_can_select_either_direction(self):
        _, linux, tombstone = self.make_conflict(android_deleted=True)
        keep_edit = resolve_conflict(
            self.repository, NOTE_ID, "keep-linux", "linux", moment(3)
        )
        self.assertFalse(keep_edit.deleted)
        self.assertEqual(keep_edit.body, linux.body)

        with TemporaryDirectory() as directory:
            repository = Repository.open(Path(directory))
            root = repository.create_revision(NOTE_ID, "base", "linux", moment(0))
            repository.create_revision(NOTE_ID, "Linux version", "linux", moment(1))
            tombstone = Revision.create(
                NOTE_ID, (root.revision_id,), "android", moment(2), True, ""
            )
            repository.import_bytes(tombstone.serialize(), "delete.md")

            keep_delete = resolve_conflict(
                repository, NOTE_ID, "keep-android", "android", moment(3)
            )

            self.assertTrue(keep_delete.deleted)
            self.assertEqual(keep_delete.body, "")
            self.assertFalse((Path(directory) / "notes" / f"{NOTE_ID}.md").exists())

    def test_ambiguous_same_device_heads_fail_closed_unless_exact_head_selected(self):
        _, first, second = self.make_conflict(both_linux=True)

        with self.assertRaisesRegex(ConflictSelectionError, "exactly one"):
            resolve_conflict(
                self.repository, NOTE_ID, "keep-linux", "linux", moment(3)
            )

        resolution = resolve_conflict(
            self.repository,
            NOTE_ID,
            "keep-linux",
            "linux",
            moment(3),
            revision_id=second.revision_id,
        )
        self.assertEqual(resolution.body, second.body)
        self.assertEqual(
            resolution.parents, tuple(sorted((first.revision_id, second.revision_id)))
        )

    def test_stale_resolution_does_not_acknowledge_concurrent_third_head(self):
        root, _, _ = self.make_conflict()
        resolution = resolve_conflict(
            self.repository, NOTE_ID, "keep-linux", "linux", moment(3)
        )
        third = Revision.create(
            NOTE_ID, (root.revision_id,), "android-2", moment(4), False, "third edit"
        )

        self.repository.import_bytes(third.serialize(), "third.md")

        status = self.repository.graph().status(NOTE_ID)
        self.assertEqual(status.head_ids, tuple(sorted((resolution.revision_id, third.revision_id))))
        self.assertTrue(status.conflicted)
        self.assertIn(resolution.revision_id, self.history_ids())
        self.assertIn(third.revision_id, self.history_ids())


if __name__ == "__main__":
    unittest.main()
