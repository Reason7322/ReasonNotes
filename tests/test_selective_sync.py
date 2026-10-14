from datetime import datetime, timedelta, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import UUID

from reasonnotes_sync.delivery import DeliveryLedger, DeliveryState
from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.conflicts import resolve_conflict
from reasonnotes_sync.kdeconnect_linux import TransferRequest
from reasonnotes_sync.receipt import ReceiptStore
from reasonnotes_sync.repository import Repository
from reasonnotes_sync.sync import sync_linux


NOTE_A = UUID("11111111-aaaa-4aaa-8aaa-111111111111")
NOTE_B = UUID("22222222-bbbb-4bbb-8bbb-222222222222")
REMOTE_NOTE = UUID("33333333-cccc-4ccc-8ccc-333333333333")
T0 = datetime(2026, 10, 14, 12, 0, tzinfo=timezone.utc)


class FakeAdapter:
    device_id = "52548cf9710c43cab2fa73ed4671aea9"

    def __init__(self):
        self.paths: list[Path] = []

    def reachable(self) -> bool:
        return True

    def request_transfer(self, path: Path) -> TransferRequest:
        self.paths.append(Path(path))
        return TransferRequest(True, False, "request accepted")


class SelectiveSyncTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        base = Path(self.temporary.name)
        self.root = base / "repository"
        self.inbox = base / "inbox"
        self.inbox.mkdir()
        self.repository = Repository.open(
            self.root,
            local_device="linux",
            target_device="android",
        )

    def test_explicit_selection_sends_only_selected_note_revisions(self) -> None:
        first_a = self.repository.create_revision(NOTE_A, "A1", "linux", T0)
        second_a = self.repository.create_revision(
            NOTE_A, "A2", "linux", T0 + timedelta(seconds=1)
        )
        note_b = self.repository.create_revision(NOTE_B, "B1", "linux", T0)
        adapter = FakeAdapter()

        report = sync_linux(
            self.repository,
            self.inbox,
            adapter,
            selected_note_ids=(NOTE_A,),
        )

        self.assertEqual(set(report.requested), {first_a.revision_id, second_a.revision_id})
        self.assertNotIn(note_b.revision_id, report.requested)
        self.assertEqual(
            DeliveryLedger(self.root).entry(note_b.revision_id, "android").state,
            DeliveryState.PENDING,
        )
        self.assertEqual(
            {path.parent.name for path in adapter.paths},
            {str(NOTE_A)},
        )

    def test_none_selection_preserves_ordinary_all_note_behavior(self) -> None:
        note_a = self.repository.create_revision(NOTE_A, "A", "linux", T0)
        note_b = self.repository.create_revision(NOTE_B, "B", "linux", T0)

        report = sync_linux(
            self.repository,
            self.inbox,
            FakeAdapter(),
            selected_note_ids=None,
        )

        self.assertEqual(set(report.requested), {note_a.revision_id, note_b.revision_id})

    def test_selected_note_includes_pending_ancestry_and_tombstone(self) -> None:
        ancestor = self.repository.create_revision(NOTE_A, "Shared", "linux", T0)
        tombstone = self.repository.create_revision(
            NOTE_A,
            "",
            "linux",
            T0 + timedelta(seconds=1),
            deleted=True,
        )
        unselected = self.repository.create_revision(NOTE_B, "Other", "linux", T0)

        report = sync_linux(
            self.repository,
            self.inbox,
            FakeAdapter(),
            selected_note_ids=(NOTE_A,),
        )

        self.assertEqual(
            set(report.requested),
            {ancestor.revision_id, tombstone.revision_id},
        )
        self.assertNotIn(unselected.revision_id, report.requested)

    def test_selected_note_sends_pending_tombstone_after_ancestor_was_shared(self) -> None:
        ancestor = self.repository.create_revision(NOTE_A, "Shared", "linux", T0)
        sync_linux(
            self.repository,
            self.inbox,
            FakeAdapter(),
            selected_note_ids=(NOTE_A,),
        )
        tombstone = self.repository.create_revision(
            NOTE_A,
            "",
            "linux",
            T0 + timedelta(seconds=1),
            deleted=True,
        )

        report = sync_linux(
            self.repository,
            self.inbox,
            FakeAdapter(),
            selected_note_ids=(NOTE_A,),
        )

        self.assertEqual(report.requested, (tombstone.revision_id,))
        self.assertNotIn(ancestor.revision_id, report.requested)

    def test_selected_note_includes_local_resolution_dependencies(self) -> None:
        base = self.repository.create_revision(NOTE_A, "Base", "linux", T0)
        linux = self.repository.create_revision(
            NOTE_A,
            "Linux",
            "linux",
            T0 + timedelta(seconds=1),
        )
        android = Revision.create(
            NOTE_A,
            (base.revision_id,),
            "android",
            T0 + timedelta(seconds=2),
            False,
            "Android",
        )
        self.repository.import_bytes(android.serialize(), "android.md")
        resolution = resolve_conflict(
            self.repository,
            NOTE_A,
            "keep-linux",
            "linux",
            T0 + timedelta(seconds=3),
            revision_id=linux.revision_id,
        )

        report = sync_linux(
            self.repository,
            self.inbox,
            FakeAdapter(),
            selected_note_ids=(NOTE_A,),
        )

        self.assertEqual(
            set(report.requested),
            {base.revision_id, linux.revision_id, resolution.revision_id},
        )
        self.assertNotIn(android.revision_id, report.requested)

    def test_empty_selection_still_scans_incoming_and_sends_receipts(self) -> None:
        local = self.repository.create_revision(NOTE_B, "Unselected", "linux", T0)
        remote = Revision.create(
            REMOTE_NOTE,
            (),
            "android",
            T0,
            False,
            "Incoming",
        )
        (self.inbox / "incoming.md").write_bytes(remote.serialize())
        adapter = FakeAdapter()

        report = sync_linux(
            self.repository,
            self.inbox,
            adapter,
            selected_note_ids=(),
        )

        self.assertEqual(report.imports, (remote.revision_id,))
        self.assertEqual(report.requested, ())
        self.assertEqual(len(report.receipt_requested), 1)
        self.assertEqual(
            DeliveryLedger(self.root).entry(local.revision_id, "android").state,
            DeliveryState.PENDING,
        )
        receipt_id = report.receipt_requested[0]
        self.assertEqual(
            adapter.paths,
            [ReceiptStore(self.root).outbox_path(receipt_id)],
        )

    def test_explicit_force_retry_remains_independent_of_ordinary_selection(self) -> None:
        note_a = self.repository.create_revision(NOTE_A, "A", "linux", T0)
        note_b = self.repository.create_revision(NOTE_B, "B", "linux", T0)
        sync_linux(self.repository, self.inbox, FakeAdapter())

        report = sync_linux(
            self.repository,
            self.inbox,
            FakeAdapter(),
            force_ids=(note_b.revision_id,),
            selected_note_ids=(NOTE_A,),
        )

        self.assertEqual(report.requested, (note_b.revision_id,))
        self.assertNotIn(note_a.revision_id, report.requested)


if __name__ == "__main__":
    unittest.main()
