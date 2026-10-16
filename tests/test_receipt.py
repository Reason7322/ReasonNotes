from datetime import datetime, timedelta, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event, Thread
import unittest
from uuid import UUID

from reasonnotes_sync.delivery import DeliveryLedger, DeliveryState, EventKind
from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.receipt import ReceiptEnvelope, ReceiptError, ReceiptStore
from reasonnotes_sync.repository import Repository


NOTE_ID = UUID("11111111-1111-4111-8111-111111111111")
REVISION_ID = "a" * 64
IMPORTED = datetime(2026, 10, 12, 21, 0, 0, tzinfo=timezone.utc)
RECEIPT_ID = "89dc65423853a87ed22b14561ed9535a50f7fd9bb9a1f5fd54263af96b18650d"
CANONICAL_RECEIPT = (
    "---\n"
    "reasonnotes-receipt-format: 1\n"
    f"receipt-id: {RECEIPT_ID}\n"
    f"note-id: {NOTE_ID}\n"
    f"revision-id: {REVISION_ID}\n"
    "acknowledging-device: android\n"
    "destination-device: linux\n"
    "imported-utc: 2026-10-12T21:00:00Z\n"
    "---\n"
).encode("utf-8")


class ReceiptEnvelopeTests(unittest.TestCase):
    def make_receipt(self, **overrides):
        values = {
            "note_id": NOTE_ID,
            "revision_id": REVISION_ID,
            "acknowledging_device": "android",
            "destination_device": "linux",
            "imported_utc": IMPORTED,
        }
        values.update(overrides)
        return ReceiptEnvelope.create(**values)

    def test_round_trip_uses_canonical_human_readable_envelope(self):
        receipt = self.make_receipt()

        self.assertEqual(receipt.receipt_id, RECEIPT_ID)
        self.assertEqual(receipt.serialize(), CANONICAL_RECEIPT)
        self.assertEqual(ReceiptEnvelope.parse(CANONICAL_RECEIPT), receipt)
        self.assertEqual(receipt.transport_filename(), f"{RECEIPT_ID}.receipt.md")

    def test_identity_hash_covers_note_revision_devices_and_time(self):
        original = self.make_receipt()
        variants = (
            self.make_receipt(note_id=UUID("22222222-2222-4222-8222-222222222222")),
            self.make_receipt(revision_id="b" * 64),
            self.make_receipt(acknowledging_device="phone"),
            self.make_receipt(destination_device="desktop"),
            self.make_receipt(
                imported_utc=datetime(2026, 10, 12, 21, 0, 1, tzinfo=timezone.utc)
            ),
        )

        for variant in variants:
            with self.subTest(variant=variant):
                self.assertNotEqual(variant.receipt_id, original.receipt_id)

    def test_rejects_corrupt_truncated_and_noncanonical_receipts(self):
        cases = {
            "bad-hash": CANONICAL_RECEIPT.replace(RECEIPT_ID.encode(), b"0" * 64),
            "truncated": CANONICAL_RECEIPT.removesuffix(b"---\n"),
            "unknown-key": CANONICAL_RECEIPT.replace(
                b"note-id: ", b"unexpected: value\nnote-id: ", 1
            ),
            "repeated-key": CANONICAL_RECEIPT.replace(
                b"destination-device: linux\n",
                b"destination-device: linux\ndestination-device: linux\n",
            ),
            "body": CANONICAL_RECEIPT + b"not allowed\n",
            "carriage-return": CANONICAL_RECEIPT.replace(b"---\n", b"---\r\n", 1),
            "non-utf8": CANONICAL_RECEIPT + b"\xff",
        }

        for case, data in cases.items():
            with self.subTest(case=case), self.assertRaises(ReceiptError):
                ReceiptEnvelope.parse(data)

    def test_create_rejects_invalid_identity_devices_and_timestamps(self):
        cases = (
            {"revision_id": "A" * 64},
            {"acknowledging_device": "Android Phone"},
            {"destination_device": ""},
            {"acknowledging_device": "linux", "destination_device": "linux"},
            {"imported_utc": datetime(2026, 10, 12, 21, 0, 0)},
            {
                "imported_utc": datetime(
                    2026, 10, 12, 22, 0, 0, tzinfo=timezone(timedelta(hours=1))
                )
            },
            {
                "imported_utc": datetime(
                    2026, 10, 12, 21, 0, 0, 1, tzinfo=timezone.utc
                )
            },
        )

        for values in cases:
            with self.subTest(values=values), self.assertRaises(ReceiptError):
                self.make_receipt(**values)


class ReceiptStoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = Repository.open(
            self.root, local_device="linux", target_device="android"
        )
        self.revision = self.repository.create_revision(
            NOTE_ID, "sent body\n", "linux", IMPORTED
        )
        self.ledger = DeliveryLedger(self.root)
        self.store = ReceiptStore(self.root)

    def requested_revision(self):
        self.ledger.record_requested(
            self.revision.revision_id,
            "android",
            IMPORTED + timedelta(seconds=1),
            observer_device="linux",
        )

    def receipt(self, **overrides):
        values = {
            "note_id": self.revision.note_id,
            "revision_id": self.revision.revision_id,
            "acknowledging_device": "android",
            "destination_device": "linux",
            "imported_utc": IMPORTED + timedelta(seconds=2),
        }
        values.update(overrides)
        return ReceiptEnvelope.create(**values)

    def test_valid_receipt_is_stored_before_confirming_and_duplicate_is_idempotent(self):
        self.requested_revision()
        receipt = self.receipt()

        first = self.store.accept(
            receipt.serialize(),
            self.repository,
            self.ledger,
            local_device="linux",
            source_name="incoming.receipt.md",
            confirmed_utc=IMPORTED + timedelta(seconds=3),
        )
        duplicate = self.store.accept(
            receipt.serialize(),
            self.repository,
            self.ledger,
            local_device="linux",
            source_name="renamed-duplicate.md",
            confirmed_utc=IMPORTED + timedelta(seconds=4),
        )

        self.assertEqual(first.kind, "confirmed")
        self.assertEqual(duplicate.kind, "duplicate")
        self.assertEqual(first.revision_id, self.revision.revision_id)
        self.assertEqual(
            self.store.inbox_path(receipt.receipt_id).read_bytes(), receipt.serialize()
        )
        entry = DeliveryLedger(self.root).entry(self.revision.revision_id, "android")
        self.assertEqual(entry.state, DeliveryState.CONFIRMED)
        self.assertEqual(entry.receipt_id, receipt.receipt_id)
        confirmed_events = tuple(
            event
            for event in DeliveryLedger(self.root).events()
            if event.kind == EventKind.REASONNOTES_CONFIRMED
        )
        self.assertEqual(len(confirmed_events), 1)
        history = self.root / "history" / str(NOTE_ID)
        self.assertEqual(len(tuple(history.glob("*.md"))), 1)

    def test_second_valid_receipt_for_same_revision_and_peer_is_redundant(self):
        self.requested_revision()
        accepted = self.receipt()
        self.store.accept(
            accepted.serialize(),
            self.repository,
            self.ledger,
            local_device="linux",
            source_name="accepted.md",
            confirmed_utc=IMPORTED + timedelta(seconds=3),
        )
        different = self.receipt(imported_utc=IMPORTED + timedelta(seconds=9))

        redundant = self.store.accept(
            different.serialize(),
            self.repository,
            self.ledger,
            local_device="linux",
            source_name="different.md",
            confirmed_utc=IMPORTED + timedelta(seconds=10),
        )
        self.assertEqual(redundant.kind, "duplicate")
        self.assertFalse(self.store.inbox_path(different.receipt_id).exists())
        entry = DeliveryLedger(self.root).entry(self.revision.revision_id, "android")
        self.assertEqual(entry.receipt_id, accepted.receipt_id)
        self.assertEqual(
            sum(event.kind == EventKind.REASONNOTES_CONFIRMED
                for event in DeliveryLedger(self.root).events()), 1
        )

        # A second receipt can already exist in the persisted inbox from
        # an interrupted older scan. Recovery must not reject it either.
        self.store.inbox_path(different.receipt_id).write_bytes(different.serialize())
        report = self.store.reconcile_confirmations(
            self.repository, self.ledger,
            local_device="linux",
            confirmed_utc=IMPORTED + timedelta(seconds=11),
        )
        self.assertEqual(report.errors, ())
        self.assertEqual(report.confirmed, ())
        entry = DeliveryLedger(self.root).entry(self.revision.revision_id, "android")
        self.assertEqual(entry.receipt_id, accepted.receipt_id)

    def test_invalid_identity_peer_and_never_requested_receipts_do_not_confirm(self):
        cases = {
            "never-requested": self.receipt(),
            "wrong-note": self.receipt(
                note_id=UUID("22222222-2222-4222-8222-222222222222")
            ),
            "wrong-destination": self.receipt(
                acknowledging_device="phone", destination_device="android"
            ),
            "wrong-peer": self.receipt(acknowledging_device="phone"),
            "unknown-revision": self.receipt(revision_id="b" * 64),
        }

        for case, receipt in cases.items():
            with self.subTest(case=case):
                if case != "never-requested":
                    self.requested_revision()
                with self.assertRaises(ReceiptError):
                    self.store.accept(
                        receipt.serialize(),
                        self.repository,
                        self.ledger,
                        local_device="linux",
                        source_name=f"{case}.md",
                        confirmed_utc=IMPORTED + timedelta(seconds=3),
                    )
                entry = DeliveryLedger(self.root).entry(
                    self.revision.revision_id, "android"
                )
                self.assertNotEqual(entry.state, DeliveryState.CONFIRMED)
                self.assertFalse(self.store.inbox_path(receipt.receipt_id).exists())
                if case != "never-requested":
                    self.root.joinpath("state", "delivery.json").unlink()
                    self.ledger = DeliveryLedger(self.root)
                    self.ledger.register_local(self.revision.revision_id, "android")

    def test_corrupt_receipt_does_not_change_requested_delivery(self):
        self.requested_revision()
        receipt = self.receipt()
        corrupt = receipt.serialize().replace(receipt.receipt_id.encode(), b"0" * 64)

        with self.assertRaises(ReceiptError):
            self.store.accept(
                corrupt,
                self.repository,
                self.ledger,
                local_device="linux",
                source_name="corrupt.md",
                confirmed_utc=IMPORTED + timedelta(seconds=3),
            )

        entry = DeliveryLedger(self.root).entry(self.revision.revision_id, "android")
        self.assertEqual(entry.state, DeliveryState.TRANSFER_REQUESTED)
        self.assertEqual(tuple(self.store.inbox_dir.glob("*.md")), ())

    def test_startup_reconciles_durable_receipt_after_interrupted_ledger_update(self):
        self.requested_revision()
        receipt = self.receipt()
        self.store.inbox_dir.mkdir(parents=True, exist_ok=True)
        self.store.inbox_path(receipt.receipt_id).write_bytes(receipt.serialize())

        report = ReceiptStore(self.root).reconcile_confirmations(
            self.repository,
            DeliveryLedger(self.root),
            local_device="linux",
            confirmed_utc=IMPORTED + timedelta(seconds=3),
        )

        self.assertEqual(report.confirmed, (self.revision.revision_id,))
        entry = DeliveryLedger(self.root).entry(self.revision.revision_id, "android")
        self.assertEqual(entry.state, DeliveryState.CONFIRMED)

    def test_receipt_outbox_is_durable_idempotent_and_bounded(self):
        remote = Revision.create(
            UUID("33333333-3333-4333-8333-333333333333"),
            (),
            "android",
            IMPORTED,
            False,
            "remote\n",
        )

        first = self.store.queue(remote, "linux", IMPORTED + timedelta(seconds=1))
        duplicate = self.store.queue(remote, "linux", IMPORTED + timedelta(seconds=9))

        self.assertEqual(duplicate, first)
        self.assertEqual(self.store.eligible("android"), (first.receipt_id,))
        self.assertEqual(len(tuple(self.store.outbox_dir.rglob("*.md"))), 1)
        for second in (2, 3, 4):
            self.store.record_requested(
                first.receipt_id,
                "android",
                IMPORTED + timedelta(seconds=second),
            )
        entry = ReceiptStore(self.root).entry(first.receipt_id, "android")
        self.assertEqual(entry.state, DeliveryState.NEEDS_ATTENTION)
        self.assertEqual(entry.attempts, 3)
        self.assertEqual(self.store.eligible("android", retry_unconfirmed=True), ())
        self.assertEqual(
            self.store.eligible("android", force_ids=(first.receipt_id,)),
            (first.receipt_id,),
        )

    def test_receipt_delivery_transactions_serialize_across_store_instances(self):
        first_revision = Revision.create(
            UUID("33333333-3333-4333-8333-333333333333"),
            (),
            "android",
            IMPORTED,
            False,
            "first\n",
        )
        second_revision = Revision.create(
            UUID("44444444-4444-4444-8444-444444444444"),
            (),
            "android",
            IMPORTED,
            False,
            "second\n",
        )
        first_store = ReceiptStore(self.root)
        first = first_store.queue(
            first_revision, "linux", IMPORTED + timedelta(seconds=1)
        )
        second_store = ReceiptStore(self.root)
        first_read = Event()
        release_first = Event()
        second_read = Event()
        errors = []
        original_first_read = first_store._read_entries
        original_second_read = second_store._read_entries

        def paused_first_read():
            entries = original_first_read()
            first_read.set()
            if not release_first.wait(2):
                raise AssertionError("timed out waiting to release first transaction")
            return entries

        def observed_second_read():
            second_read.set()
            return original_second_read()

        first_store._read_entries = paused_first_read
        second_store._read_entries = observed_second_read

        def request_first():
            try:
                first_store.record_requested(
                    first.receipt_id, "android", IMPORTED + timedelta(seconds=2)
                )
            except BaseException as error:
                errors.append(error)

        def queue_second():
            try:
                second_store.queue(
                    second_revision, "linux", IMPORTED + timedelta(seconds=3)
                )
            except BaseException as error:
                errors.append(error)

        requesting = Thread(target=request_first)
        requesting.start()
        self.assertTrue(first_read.wait(2))
        queuing = Thread(target=queue_second)
        queuing.start()
        read_during_transaction = second_read.wait(0.2)
        release_first.set()
        requesting.join(2)
        queuing.join(2)

        self.assertFalse(read_during_transaction)
        self.assertFalse(requesting.is_alive())
        self.assertFalse(queuing.is_alive())
        self.assertEqual(errors, [])
        reopened = ReceiptStore(self.root)
        self.assertEqual(
            reopened.entry(first.receipt_id, "android").state,
            DeliveryState.TRANSFER_REQUESTED,
        )
        self.assertTrue(
            any(
                entry.revision_id == second_revision.revision_id
                for entry in reopened.entries("android")
            )
        )


if __name__ == "__main__":
    unittest.main()
