from datetime import datetime, timezone
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
from uuid import UUID

from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.repository import ConflictRequired, Repository


NOTE_ID = UUID("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb")
T0 = datetime(2026, 10, 12, 11, 0, 0, tzinfo=timezone.utc)
T1 = datetime(2026, 10, 12, 11, 0, 1, tzinfo=timezone.utc)
T2 = datetime(2026, 10, 12, 11, 0, 2, tzinfo=timezone.utc)


class RepositoryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = Repository.open(self.root)

    def create_root(self, body="root"):
        return self.repository.create_revision(NOTE_ID, body, "linux", T0)

    def history_path(self, revision):
        return self.root / "history" / str(revision.note_id) / f"{revision.revision_id}.md"

    def index(self):
        return json.loads((self.root / "state" / "index.json").read_text())

    def test_permanent_purge_wipes_full_revision_history_and_rejects_old_reimports(self):
        first = self.create_root("original secret")
        updated = self.repository.create_revision(NOTE_ID, "new secret", "linux", T1)
        self.assertTrue(self.history_path(first).exists())
        self.assertTrue(self.history_path(updated).exists())
        self.repository.purge_note(NOTE_ID)
        self.assertTrue(self.repository.is_purged(NOTE_ID))
        self.assertEqual(self.repository.revisions(), ())
        self.assertFalse(self.history_path(first).exists())
        self.assertFalse(self.history_path(updated).exists())
        self.assertFalse((self.root / "notes" / f"{NOTE_ID}.md").exists())
        self.assertFalse((self.root / "history" / str(NOTE_ID)).exists())
        self.assertEqual("purged", self.repository.import_bytes(first.serialize(), "old-transfer.md").kind)
        self.assertEqual(self.repository.revisions(), ())
        with self.assertRaises(ConflictRequired):
            self.repository.create_revision(NOTE_ID, "attempt", "linux", T2)

    def test_purge_fence_survives_crash_before_history_file_cleanup(self):
        first = self.create_root("sensitive")
        marker = self.root / "purged" / f"{NOTE_ID}.id"
        self.repository._atomic_write(marker, (str(NOTE_ID) + "\n").encode("ascii"))
        self.assertEqual(self.repository.revisions(), ())
        reopened = Repository.open(self.root)
        self.assertTrue(reopened.is_purged(NOTE_ID))
        self.assertFalse(self.history_path(first).exists())
        self.assertFalse((self.root / "notes" / f"{NOTE_ID}.md").exists())

    def test_purge_is_idempotent_and_conflict_history_is_purged(self):
        parent = self.create_root("base secret")
        self.repository.create_revision(NOTE_ID, "linux conflict", "linux", T1)
        remote = Revision.create(NOTE_ID, (parent.revision_id,), "android", T2,
                                 False, "android conflict")
        self.assertEqual("imported", self.repository.import_bytes(remote.serialize(), "conflict.md").kind)
        self.assertTrue(self.repository.graph().status(NOTE_ID).conflicted)
        self.repository.purge_note(NOTE_ID)
        self.repository.purge_note(NOTE_ID)
        self.assertEqual(self.repository.revisions(), ())
        self.assertFalse((self.root / "conflicts" / f"{NOTE_ID}.json").exists())
        self.assertTrue((self.root / "purged" / f"{NOTE_ID}.id").is_file())

    def test_import_writes_history_before_index_and_working_view(self):
        revision = Revision.create(NOTE_ID, (), "android", T0, False, "incoming")
        events = []
        original_history = self.repository._write_history
        original_index = self.repository._write_index
        original_view = self.repository._write_working_view

        def write_history(item):
            events.append("history")
            return original_history(item)

        def write_index(index):
            events.append("index")
            return original_index(index)

        def write_view(note_id, selected):
            events.append("view")
            return original_view(note_id, selected)

        with (
            patch.object(self.repository, "_write_history", side_effect=write_history),
            patch.object(self.repository, "_write_index", side_effect=write_index),
            patch.object(self.repository, "_write_working_view", side_effect=write_view),
        ):
            outcome = self.repository.import_bytes(revision.serialize(), "received-copy.md")

        self.assertEqual(outcome.kind, "imported")
        self.assertLess(events.index("history"), events.index("index"))
        self.assertLess(events.index("index"), events.index("view"))
        self.assertEqual(self.history_path(revision).read_bytes(), revision.serialize())

    def test_reconcile_repairs_stale_index_after_history_only_interruption(self):
        root = self.create_root()
        child = Revision.create(NOTE_ID, (root.revision_id,), "linux", T1, False, "child")
        child_path = self.history_path(child)
        child_path.write_bytes(child.serialize())

        reopened = Repository.open(self.root)

        note_state = json.loads((self.root / "state" / "index.json").read_text())["notes"][
            str(NOTE_ID)
        ]
        self.assertEqual(note_state["heads"], [child.revision_id])
        self.assertEqual((self.root / "notes" / f"{NOTE_ID}.md").read_text(), "child")
        self.assertEqual(reopened.graph().heads(NOTE_ID), (child,))

    def test_reconcile_repairs_missing_working_view_from_history(self):
        revision = self.create_root("recover me")
        view = self.root / "notes" / f"{NOTE_ID}.md"
        view.unlink()

        report = self.repository.reconcile()

        self.assertEqual(view.read_text(), "recover me")
        self.assertEqual(report.revision_count, 1)
        self.assertEqual(self.repository.graph().heads(NOTE_ID), (revision,))

    def test_hash_mismatch_is_quarantined_without_state_change(self):
        root = self.create_root("safe")
        index_before = (self.root / "state" / "index.json").read_bytes()
        view_before = (self.root / "notes" / f"{NOTE_ID}.md").read_bytes()
        corrupted = root.serialize().replace(b"safe", b"evil", 1)

        outcome = self.repository.import_bytes(corrupted, "../../partial.md")

        self.assertEqual(outcome.kind, "quarantined")
        self.assertIsNone(outcome.revision_id)
        self.assertEqual((self.root / "state" / "index.json").read_bytes(), index_before)
        self.assertEqual((self.root / "notes" / f"{NOTE_ID}.md").read_bytes(), view_before)
        quarantined = tuple((self.root / "quarantine").iterdir())
        self.assertEqual(len(quarantined), 1)
        self.assertEqual(quarantined[0].read_bytes(), corrupted)

    def test_duplicate_under_renamed_transport_file_is_idempotent(self):
        revision = Revision.create(NOTE_ID, (), "android", T0, False, "same")

        first = self.repository.import_bytes(revision.serialize(), revision.transport_filename())
        second = self.repository.import_bytes(revision.serialize(), "renamed (1).md")

        self.assertEqual(first.kind, "imported")
        self.assertEqual(second.kind, "duplicate")
        self.assertEqual(second.revision_id, revision.revision_id)
        self.assertEqual(tuple((self.root / "history" / str(NOTE_ID)).glob("*.md")), (self.history_path(revision),))

    def test_verified_duplicate_does_not_write_or_reconcile(self):
        revision = Revision.create(NOTE_ID, (), "android", T0, False, "unchanged")
        self.assertEqual(
            "imported", self.repository.import_bytes(revision.serialize(), "first.md").kind
        )
        path = self.history_path(revision)
        index_before = (self.root / "state" / "index.json").read_bytes()
        note_before = (self.root / "notes" / f"{NOTE_ID}.md").read_bytes()

        with (
            patch.object(self.repository, "reconcile", side_effect=AssertionError("duplicate reconciled")),
            patch("reasonnotes_sync.repository.tempfile.mkstemp", side_effect=AssertionError("duplicate wrote temp file")),
        ):
            result = self.repository.import_bytes(revision.serialize(), "repeat.md")

        self.assertEqual("duplicate", result.kind)
        self.assertEqual(revision.serialize(), path.read_bytes())
        self.assertEqual(index_before, (self.root / "state" / "index.json").read_bytes())
        self.assertEqual(note_before, (self.root / "notes" / f"{NOTE_ID}.md").read_bytes())

    def test_existing_history_disagreement_is_not_silently_accepted(self):
        revision = Revision.create(NOTE_ID, (), "android", T0, False, "expected")
        self.assertEqual(
            "imported", self.repository.import_bytes(revision.serialize(), "first.md").kind
        )
        path = self.history_path(revision)
        # Deliberately corrupt only a disposable fixture, not user data.
        path.write_bytes(b"corrupt preexisting history")
        with self.assertRaisesRegex(RuntimeError, "disagrees with revision ID"):
            self.repository.import_bytes(revision.serialize(), "second.md")
        self.assertEqual(b"corrupt preexisting history", path.read_bytes())

    def test_tombstone_removes_working_view_but_preserves_history(self):
        root = self.create_root("delete later")

        tombstone = self.repository.create_revision(NOTE_ID, "", "linux", T1, deleted=True)

        self.assertFalse((self.root / "notes" / f"{NOTE_ID}.md").exists())
        self.assertTrue(self.history_path(root).exists())
        self.assertTrue(self.history_path(tombstone).exists())
        self.assertEqual(self.repository.graph().heads(NOTE_ID), (tombstone,))

    def test_conflict_preserves_previous_working_view_and_all_heads(self):
        root = self.create_root("base")
        linux = self.repository.create_revision(NOTE_ID, "linux edit", "linux", T1)
        android = Revision.create(
            NOTE_ID, (root.revision_id,), "android", T2, False, "android edit"
        )

        outcome = self.repository.import_bytes(android.serialize(), "android.md")

        self.assertEqual(outcome.kind, "imported")
        self.assertEqual((self.root / "notes" / f"{NOTE_ID}.md").read_text(), "linux edit")
        self.assertEqual(
            self.repository.graph().status(NOTE_ID).head_ids,
            tuple(sorted((linux.revision_id, android.revision_id))),
        )
        conflict = json.loads((self.root / "conflicts" / f"{NOTE_ID}.json").read_text())
        self.assertEqual(
            [head["revision_id"] for head in conflict["heads"]],
            list(sorted((linux.revision_id, android.revision_id))),
        )
        self.assertTrue(self.history_path(root).exists())
        self.assertTrue(self.history_path(linux).exists())
        self.assertTrue(self.history_path(android).exists())

    def test_ordinary_edit_fails_closed_while_note_is_conflicted(self):
        root = self.create_root()
        self.repository.create_revision(NOTE_ID, "linux", "linux", T1)
        android = Revision.create(NOTE_ID, (root.revision_id,), "android", T2, False, "android")
        self.repository.import_bytes(android.serialize(), "android.md")
        history_before = tuple((self.root / "history" / str(NOTE_ID)).glob("*.md"))

        with self.assertRaises(ConflictRequired):
            self.repository.create_revision(NOTE_ID, "accidental winner", "linux", T2)

        self.assertEqual(
            set((self.root / "history" / str(NOTE_ID)).glob("*.md")), set(history_before)
        )


if __name__ == "__main__":
    unittest.main()
