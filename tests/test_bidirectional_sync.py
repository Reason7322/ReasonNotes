from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import UUID

from reasonnotes_sync.conflicts import resolve_conflict
from reasonnotes_sync.delivery import DeliveryLedger, DeliveryState
from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.repository import Repository


NOTE = UUID("33333333-8888-4888-8888-333333333333")
OTHER = UUID("44444444-9999-4999-8999-444444444444")


def at(second):
    return datetime(2026, 10, 12, 16, 0, second, tzinfo=timezone.utc)


def exchange(source, target, revisions=None):
    items = source.revisions() if revisions is None else revisions
    return tuple(target.import_bytes(item.serialize(), "renamed delivery.md") for item in items)


class BidirectionalSyncTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        base = Path(self.temporary.name)
        self.linux = Repository.open(
            base / "linux", local_device="linux", target_device="android"
        )
        self.android = Repository.open(
            base / "android", local_device="android", target_device="linux"
        )

    def common_root(self):
        root = self.linux.create_revision(NOTE, "base", "linux", at(0))
        exchange(self.linux, self.android)
        return root

    def make_conflict(self, android_deleted=False):
        root = self.common_root()
        linux = self.linux.create_revision(NOTE, "linux edit", "linux", at(1))
        android = self.android.create_revision(
            NOTE, "" if android_deleted else "android edit", "android", at(2), android_deleted
        )
        exchange(self.linux, self.android)
        exchange(self.android, self.linux)
        return root, linux, android

    def assert_converged(self, note_id=NOTE):
        linux_heads = self.linux.graph().heads(note_id)
        android_heads = self.android.graph().heads(note_id)
        self.assertEqual(
            tuple(head.revision_id for head in linux_heads),
            tuple(head.revision_id for head in android_heads),
        )
        self.assertEqual(
            tuple((head.deleted, head.body_sha256, head.body) for head in linux_heads),
            tuple((head.deleted, head.body_sha256, head.body) for head in android_heads),
        )

    def test_independent_notes_exchange_to_equal_heads_and_bodies(self):
        linux = self.linux.create_revision(NOTE, "from linux", "linux", at(0))
        android = self.android.create_revision(OTHER, "from android", "android", at(1))

        exchange(self.linux, self.android)
        exchange(self.android, self.linux)

        self.assert_converged(NOTE)
        self.assert_converged(OTHER)
        self.assertEqual(self.linux.graph().heads(NOTE), (linux,))
        self.assertEqual(self.android.graph().heads(OTHER), (android,))

    def test_offline_edits_conflict_and_skip_preserves_both_device_heads(self):
        _, linux, android = self.make_conflict()

        skipped = resolve_conflict(self.linux, NOTE, "skip", "linux", at(3))

        self.assertIsNone(skipped)
        self.assertEqual(
            self.linux.graph().status(NOTE).head_ids,
            tuple(sorted((linux.revision_id, android.revision_id))),
        )

    def test_each_keep_choice_creates_multi_parent_revision_and_converges(self):
        for choice, expected in (("keep-linux", "linux edit"), ("keep-android", "android edit")):
            with self.subTest(choice=choice), TemporaryDirectory() as directory:
                base = Path(directory)
                linux_repo = Repository.open(base / "linux")
                android_repo = Repository.open(base / "android")
                root = linux_repo.create_revision(NOTE, "base", "linux", at(0))
                exchange(linux_repo, android_repo)
                linux_head = linux_repo.create_revision(NOTE, "linux edit", "linux", at(1))
                android_head = android_repo.create_revision(NOTE, "android edit", "android", at(2))
                exchange(linux_repo, android_repo)
                exchange(android_repo, linux_repo)
                resolution = resolve_conflict(linux_repo, NOTE, choice, "linux", at(3))
                exchange(linux_repo, android_repo)
                self.assertEqual(
                    resolution.parents,
                    tuple(sorted((linux_head.revision_id, android_head.revision_id))),
                )
                self.assertEqual(resolution.body, expected)
                self.assertEqual(linux_repo.graph().heads(NOTE), (resolution,))
                self.assertEqual(android_repo.graph().heads(NOTE), (resolution,))
                self.assertIsNotNone(linux_repo.revision(root.revision_id))

    def test_edit_delete_conflict_resolves_both_ways(self):
        _, edit, deletion = self.make_conflict(android_deleted=True)
        keep_edit = resolve_conflict(self.linux, NOTE, "keep-linux", "linux", at(3))
        exchange(self.linux, self.android)
        self.assertFalse(keep_edit.deleted)
        self.assertEqual(keep_edit.body, edit.body)
        self.assert_converged()

        with TemporaryDirectory() as directory:
            base = Path(directory)
            left = Repository.open(base / "left")
            right = Repository.open(base / "right")
            root = left.create_revision(NOTE, "base", "linux", at(0))
            exchange(left, right)
            left.create_revision(NOTE, "edit", "linux", at(1))
            tombstone = right.create_revision(NOTE, "", "android", at(2), True)
            exchange(left, right)
            exchange(right, left)
            keep_delete = resolve_conflict(left, NOTE, "keep-android", "linux", at(3))
            exchange(left, right)
            self.assertTrue(keep_delete.deleted)
            self.assertEqual(keep_delete.body, deletion.body)
            self.assertEqual(right.graph().heads(NOTE), (keep_delete,))
            self.assertIsNotNone(right.revision(tombstone.revision_id))

    def test_stale_resolution_duplicate_late_ancestor_and_out_of_order_are_safe(self):
        root, _, _ = self.make_conflict()
        resolution = resolve_conflict(self.linux, NOTE, "keep-linux", "linux", at(3))
        third = Revision.create(NOTE, (root.revision_id,), "android-2", at(4), False, "third")
        self.android.import_bytes(third.serialize(), "third.md")
        self.android.import_bytes(resolution.serialize(), "resolution-first.md")
        self.android.import_bytes(root.serialize(), "late root (1).md")
        self.android.import_bytes(resolution.serialize(), "resolution (1).md")

        self.assertEqual(
            self.android.graph().status(NOTE).head_ids,
            tuple(sorted((resolution.revision_id, third.revision_id))),
        )

    def test_history_only_interruption_reconciles_equal_view_and_heads(self):
        revision = self.linux.create_revision(NOTE, "durable", "linux", at(0))
        path = self.android.history_dir / str(NOTE) / f"{revision.revision_id}.md"
        path.parent.mkdir()
        path.write_bytes(revision.serialize())

        self.android = Repository.open(self.android.root)

        self.assert_converged()
        self.assertEqual((self.android.notes_dir / f"{NOTE}.md").read_text(), "durable")

    def test_retry_state_stays_bounded_during_exchange(self):
        revision = self.linux.create_revision(NOTE, "send", "linux", at(0))
        ledger = DeliveryLedger(self.linux.root)
        for second in (1, 2, 3):
            ledger.record_requested(revision.revision_id, "android", at(second), "linux")
        exchange(self.linux, self.android)

        self.assertEqual(
            ledger.entry(revision.revision_id, "android").state,
            DeliveryState.NEEDS_ATTENTION,
        )
        self.assertEqual(ledger.eligible("android", retry_unconfirmed=True), ())
        self.assertEqual(self.android.graph().heads(NOTE), (revision,))


if __name__ == "__main__":
    unittest.main()
