from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import UUID

from reasonnotes_sync.delivery import DeliveryLedger, DeliveryState, EventKind
from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.kdeconnect_linux import TransferRequest
from reasonnotes_sync.receipt import ReceiptEnvelope, ReceiptStore
from reasonnotes_sync.repository import Repository
from reasonnotes_sync.sync import scan_inbox, sync_linux


NOTE_LOCAL = UUID("11111111-6666-4666-8666-111111111111")
NOTE_REMOTE = UUID("22222222-7777-4777-8777-222222222222")
T0 = datetime(2026, 10, 12, 15, 0, 0, tzinfo=timezone.utc)
T1 = datetime(2026, 10, 12, 15, 0, 1, tzinfo=timezone.utc)
DEVICE_ID = "52548cf9710c43cab2fa73ed4671aea9"


class FakeAdapter:
    def __init__(self, reachable=True, before_request=None):
        self.device_id = DEVICE_ID
        self.is_reachable = reachable
        self.before_request = before_request
        self.calls = []

    def reachable(self):
        self.calls.append("reachable")
        return self.is_reachable

    def request_transfer(self, path):
        self.calls.append(("request", Path(path)))
        if self.before_request:
            self.before_request()
        return TransferRequest(True, False, "request accepted")


class SyncTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "repo"
        self.inbox = Path(self.temporary.name) / "inbox"
        self.inbox.mkdir()
        self.repository = Repository.open(
            self.root, local_device="linux", target_device="android"
        )

    def write_incoming(self, revision, name="incoming.md"):
        path = self.inbox / name
        path.write_bytes(revision.serialize())
        return path

    def test_scan_filters_unrelated_files_and_recovers_missed_listener_import(self):
        remote = Revision.create(NOTE_REMOTE, (), "android", T0, False, "remote")
        self.write_incoming(remote)
        (self.inbox / "photo.jpg").write_bytes(b"not a note")
        (self.inbox / "ordinary.md").write_text("# ordinary markdown")

        report = scan_inbox(self.repository, self.inbox, "linux")

        self.assertEqual(report.imported, (remote.revision_id,))
        self.assertEqual(report.quarantined, ())
        self.assertEqual(self.repository.graph().heads(NOTE_REMOTE), (remote,))
        self.assertEqual(len(tuple((self.root / "quarantine").iterdir())), 0)

    def test_sync_scans_before_sending_each_pending_local_revision(self):
        local = self.repository.create_revision(NOTE_LOCAL, "local", "linux", T0)
        remote = Revision.create(NOTE_REMOTE, (), "android", T0, False, "remote")
        self.write_incoming(remote)
        adapter = FakeAdapter(
            before_request=lambda: self.assertEqual(
                self.repository.graph().heads(NOTE_REMOTE), (remote,)
            )
        )

        report = sync_linux(self.repository, self.inbox, adapter)

        self.assertEqual(report.imports, (remote.revision_id,))
        self.assertEqual(report.requested, (local.revision_id,))
        self.assertEqual(adapter.calls[0], "reachable")
        events = DeliveryLedger(self.root).events()
        imported = next(event for event in events if event.kind == EventKind.REASONNOTES_IMPORTED)
        self.assertTrue(
            (self.root / "history" / str(NOTE_REMOTE) / f"{imported.revision_id}.md").exists()
        )

    def test_unreachable_target_records_no_request_and_leaves_pending(self):
        local = self.repository.create_revision(NOTE_LOCAL, "local", "linux", T0)

        report = sync_linux(self.repository, self.inbox, FakeAdapter(reachable=False))

        entry = DeliveryLedger(self.root).entry(local.revision_id, "android")
        self.assertEqual(entry.attempts, 0)
        self.assertEqual(report.requested, ())
        self.assertEqual(report.pending, (local.revision_id,))
        self.assertTrue(report.errors)

    def test_default_does_not_resend_but_explicit_retry_requests_once(self):
        local = self.repository.create_revision(NOTE_LOCAL, "local", "linux", T0)
        adapter = FakeAdapter()
        sync_linux(self.repository, self.inbox, adapter)

        second = sync_linux(self.repository, self.inbox, adapter)
        retry = sync_linux(
            self.repository, self.inbox, adapter, retry_unconfirmed=True
        )

        self.assertEqual(second.requested, ())
        self.assertEqual(retry.requested, (local.revision_id,))
        self.assertEqual(DeliveryLedger(self.root).entry(local.revision_id, "android").attempts, 2)

    def test_imported_concurrent_head_is_reported_as_conflict(self):
        root = self.repository.create_revision(NOTE_LOCAL, "base", "linux", T0)
        self.repository.create_revision(
            NOTE_LOCAL,
            "linux",
            "linux",
            datetime(2026, 10, 12, 15, 0, 1, tzinfo=timezone.utc),
        )
        remote = Revision.create(
            NOTE_LOCAL,
            (root.revision_id,),
            "android",
            datetime(2026, 10, 12, 15, 0, 2, tzinfo=timezone.utc),
            False,
            "android",
        )
        self.write_incoming(remote)

        report = sync_linux(self.repository, self.inbox, FakeAdapter(reachable=False))

        self.assertEqual(report.conflicts, (str(NOTE_LOCAL),))

    def test_scan_queues_receipt_only_after_durable_revision_import(self):
        remote = Revision.create(NOTE_REMOTE, (), "android", T0, False, "remote")
        self.write_incoming(remote)
        (self.inbox / "truncated.md").write_bytes(remote.serialize()[:-2])

        report = scan_inbox(self.repository, self.inbox, "linux")

        self.assertEqual(report.imported, (remote.revision_id,))
        self.assertEqual(len(report.receipts_queued), 1)
        receipt_id = report.receipts_queued[0]
        receipt_store = ReceiptStore(self.root)
        receipt = ReceiptEnvelope.parse(receipt_store.outbox_path(receipt_id).read_bytes())
        self.assertEqual(receipt.note_id, remote.note_id)
        self.assertEqual(receipt.revision_id, remote.revision_id)
        self.assertEqual(receipt.acknowledging_device, "linux")
        self.assertEqual(receipt.destination_device, "android")
        self.assertTrue(
            (
                self.root
                / "history"
                / str(remote.note_id)
                / f"{remote.revision_id}.md"
            ).is_file()
        )
        self.assertEqual(len(receipt_store.entries("android")), 1)

    def test_incoming_receipt_confirms_only_after_durable_receipt_storage(self):
        local = self.repository.create_revision(NOTE_LOCAL, "local", "linux", T0)
        sync_linux(self.repository, self.inbox, FakeAdapter())
        receipt = ReceiptEnvelope.create(
            local.note_id,
            local.revision_id,
            "android",
            "linux",
            T1,
        )
        (self.inbox / "confirmation.md").write_bytes(receipt.serialize())

        report = scan_inbox(self.repository, self.inbox, "linux")

        self.assertEqual(report.confirmed, (local.revision_id,))
        entry = DeliveryLedger(self.root).entry(local.revision_id, "android")
        self.assertEqual(entry.state, DeliveryState.CONFIRMED)
        self.assertEqual(entry.receipt_id, receipt.receipt_id)
        self.assertEqual(
            ReceiptStore(self.root).inbox_path(receipt.receipt_id).read_bytes(),
            receipt.serialize(),
        )

    def test_offline_receipt_stays_pending_then_requests_once_and_retries_explicitly(self):
        remote = Revision.create(NOTE_REMOTE, (), "android", T0, False, "remote")
        self.write_incoming(remote)
        scan = scan_inbox(self.repository, self.inbox, "linux")
        receipt_id = scan.receipts_queued[0]

        offline = sync_linux(
            self.repository, self.inbox, FakeAdapter(reachable=False)
        )
        pending = ReceiptStore(self.root).entry(receipt_id, "android")
        first = sync_linux(self.repository, self.inbox, FakeAdapter())
        default_again = sync_linux(self.repository, self.inbox, FakeAdapter())
        retry = sync_linux(
            self.repository,
            self.inbox,
            FakeAdapter(),
            retry_unconfirmed=True,
        )

        self.assertTrue(offline.errors)
        self.assertEqual(pending.state, DeliveryState.PENDING)
        self.assertEqual(pending.attempts, 0)
        self.assertEqual(first.receipt_requested, (receipt_id,))
        self.assertEqual(default_again.receipt_requested, ())
        self.assertEqual(retry.receipt_requested, (receipt_id,))
        self.assertEqual(
            ReceiptStore(self.root).entry(receipt_id, "android").attempts, 2
        )

    def test_corrupt_or_wrong_peer_receipt_never_confirms_delivery(self):
        local = self.repository.create_revision(NOTE_LOCAL, "local", "linux", T0)
        sync_linux(self.repository, self.inbox, FakeAdapter())
        receipt = ReceiptEnvelope.create(
            local.note_id,
            local.revision_id,
            "phone",
            "linux",
            T1,
        )
        (self.inbox / "wrong-peer.md").write_bytes(receipt.serialize())
        (self.inbox / "truncated-receipt.md").write_bytes(
            receipt.serialize()[:-4]
        )

        report = scan_inbox(self.repository, self.inbox, "linux")

        self.assertTrue(report.errors)
        self.assertEqual(report.confirmed, ())
        self.assertEqual(
            DeliveryLedger(self.root).entry(local.revision_id, "android").state,
            DeliveryState.TRANSFER_REQUESTED,
        )


if __name__ == "__main__":
    unittest.main()
