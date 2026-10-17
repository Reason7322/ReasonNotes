from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from uuid import UUID

from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.receipt import ReceiptStore
from reasonnotes_sync.receipt_return import return_pending_receipts


class FakeAdapter:
    def __init__(self, *, reachable=True, accepted=True):
        self.is_reachable = reachable
        self.accepted = accepted
        self.calls = []

    def reachable(self):
        return self.is_reachable

    def request_transfer(self, path):
        self.calls.append(Path(path))
        return SimpleNamespace(accepted=self.accepted)


class ReturnPendingReceiptsTests(unittest.TestCase):
    def setUp(self):
        self.dir = TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.root = Path(self.dir.name)
        self.repo = SimpleNamespace(root=self.root, target_device="android")
        self.store = ReceiptStore(self.root)
        self.now = datetime(2026, 10, 17, 4, 0, tzinfo=timezone.utc)
        revision = Revision.create(
            UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            (), "android", self.now, False, "# Test note",
        )
        self.receipt = self.store.queue(revision, "linux", self.now)

    def test_pending_is_sent_once_and_never_auto_retried(self):
        adapter = FakeAdapter()
        count, errors = return_pending_receipts(
            self.repo, adapter, clock=lambda: self.now)
        self.assertEqual((count, errors), (1, ()))
        self.assertEqual([p.name for p in adapter.calls],
                         [self.receipt.transport_filename()])
        self.assertEqual(len(self.store.eligible("android")), 0)
        count, errors = return_pending_receipts(
            self.repo, adapter, clock=lambda: self.now)
        self.assertEqual((count, errors), (0, ()))
        self.assertEqual(len(adapter.calls), 1)

    def test_unreachable_and_failed_request_leave_receipt_pending(self):
        count, errors = return_pending_receipts(
            self.repo, FakeAdapter(reachable=False), clock=lambda: self.now)
        self.assertEqual(count, 0)
        self.assertEqual(len(errors), 1)
        failed = FakeAdapter(accepted=False)
        count, errors = return_pending_receipts(
            self.repo, failed, clock=lambda: self.now)
        self.assertEqual(count, 0)
        self.assertEqual(len(errors), 1)
        self.assertEqual(len(self.store.eligible("android")), 1)


if __name__ == "__main__":
    unittest.main()
