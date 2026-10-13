from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event, Thread
import unittest
from uuid import UUID

from reasonnotes_sync.conflicts import resolve_conflict
from reasonnotes_sync.delivery import (
    DeliveryLedger,
    DeliveryLedgerError,
    DeliveryState,
    EventKind,
)
from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.repository import Repository


REVISION_ID = "8" * 64
RECEIPT_ID = "9" * 64
NOTE_ID = UUID("eeeeeeee-5555-4555-8555-eeeeeeeeeeee")
T0 = datetime(2026, 10, 12, 14, 0, 0, tzinfo=timezone.utc)
T1 = datetime(2026, 10, 12, 14, 0, 1, tzinfo=timezone.utc)
T2 = datetime(2026, 10, 12, 14, 0, 2, tzinfo=timezone.utc)
T3 = datetime(2026, 10, 12, 14, 0, 3, tzinfo=timezone.utc)


class DeliveryLedgerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.ledger = DeliveryLedger(self.root)
        self.ledger.register_local(REVISION_ID, "android")

    def test_default_request_is_once_and_not_implicitly_resent(self):
        self.assertEqual(self.ledger.eligible("android"), (REVISION_ID,))

        self.ledger.record_requested(REVISION_ID, "android", T0)

        entry = self.ledger.entry(REVISION_ID, "android")
        self.assertEqual(entry.state, DeliveryState.TRANSFER_REQUESTED)
        self.assertEqual(entry.attempts, 1)
        self.assertEqual(entry.last_requested_utc, T0)
        self.assertEqual(self.ledger.eligible("android"), ())

    def test_explicit_retry_allows_one_request_per_run_and_bounds_at_three(self):
        self.ledger.record_requested(REVISION_ID, "android", T0)
        self.assertEqual(
            self.ledger.eligible("android", retry_unconfirmed=True), (REVISION_ID,)
        )
        self.ledger.record_requested(REVISION_ID, "android", T1)
        self.assertEqual(
            self.ledger.eligible("android", retry_unconfirmed=True), (REVISION_ID,)
        )

        self.ledger.record_requested(REVISION_ID, "android", T2)

        entry = self.ledger.entry(REVISION_ID, "android")
        self.assertEqual(entry.state, DeliveryState.NEEDS_ATTENTION)
        self.assertEqual(entry.attempts, 3)
        self.assertEqual(self.ledger.eligible("android", retry_unconfirmed=True), ())

    def test_targeted_force_retry_remains_explicit_after_bound(self):
        for observed in (T0, T1, T2):
            self.ledger.record_requested(REVISION_ID, "android", observed)

        self.assertEqual(
            self.ledger.eligible("android", force_ids=(REVISION_ID,)), (REVISION_ID,)
        )
        self.ledger.record_requested(REVISION_ID, "android", T3)

        entry = self.ledger.entry(REVISION_ID, "android")
        self.assertEqual(entry.state, DeliveryState.NEEDS_ATTENTION)
        self.assertEqual(entry.attempts, 4)

    def test_unavailable_target_leaves_eligible_revision_pending(self):
        selected_before_reachability_check = self.ledger.eligible("android")

        self.assertEqual(selected_before_reachability_check, (REVISION_ID,))
        entry = self.ledger.entry(REVISION_ID, "android")
        self.assertEqual(entry.state, DeliveryState.PENDING)
        self.assertEqual(entry.attempts, 0)
        self.assertIsNone(entry.last_requested_utc)

    def test_distinct_event_kinds_never_imply_one_another(self):
        self.ledger.record_requested(REVISION_ID, "android", T0)
        self.ledger.record_event(
            EventKind.KDECONNECT_TRANSFER_COMPLETE,
            REVISION_ID,
            "android",
            T1,
            "receiver notification observed",
        )
        self.ledger.record_event(
            EventKind.REASONNOTES_IMPORTED,
            REVISION_ID,
            "android",
            T2,
            "validated and stored",
        )

        events = self.ledger.events()
        self.assertEqual(
            tuple(event.kind for event in events),
            (
                EventKind.TRANSFER_REQUESTED,
                EventKind.KDECONNECT_TRANSFER_COMPLETE,
                EventKind.REASONNOTES_IMPORTED,
            ),
        )
        self.assertEqual(self.ledger.entry(REVISION_ID, "android").attempts, 1)
        self.assertEqual(
            self.ledger.entry(REVISION_ID, "android").state,
            DeliveryState.TRANSFER_REQUESTED,
        )

    def test_valid_receipt_confirmation_is_distinct_durable_and_idempotent(self):
        self.ledger.record_requested(REVISION_ID, "android", T0)

        first = self.ledger.record_confirmed(
            REVISION_ID,
            "android",
            RECEIPT_ID,
            T1,
            observer_device="linux",
        )
        duplicate = self.ledger.record_confirmed(
            REVISION_ID,
            "android",
            RECEIPT_ID,
            T1,
            observer_device="linux",
        )
        with self.assertRaisesRegex(DeliveryLedgerError, "different receipt"):
            self.ledger.record_confirmed(
                REVISION_ID,
                "android",
                "a" * 64,
                T1,
                observer_device="linux",
            )

        self.assertTrue(first)
        self.assertFalse(duplicate)
        reopened = DeliveryLedger(self.root)
        entry = reopened.entry(REVISION_ID, "android")
        self.assertEqual(entry.state, DeliveryState.CONFIRMED)
        self.assertEqual(entry.receipt_id, RECEIPT_ID)
        self.assertEqual(entry.confirmed_utc, T1)
        self.assertEqual(reopened.eligible("android", force_ids=(REVISION_ID,)), ())
        self.assertEqual(
            tuple(event.kind for event in reopened.events()),
            (EventKind.TRANSFER_REQUESTED, EventKind.REASONNOTES_CONFIRMED),
        )

    def test_receipt_cannot_confirm_a_revision_that_was_never_requested(self):
        with self.assertRaisesRegex(DeliveryLedgerError, "never requested"):
            self.ledger.record_confirmed(
                REVISION_ID,
                "android",
                RECEIPT_ID,
                T1,
                observer_device="linux",
            )

        entry = self.ledger.entry(REVISION_ID, "android")
        self.assertEqual(entry.state, DeliveryState.PENDING)
        self.assertEqual(entry.attempts, 0)

    def test_confirmation_replay_repairs_event_after_interrupted_append(self):
        self.ledger.record_requested(REVISION_ID, "android", T0)
        self.ledger.record_confirmed(
            REVISION_ID,
            "android",
            RECEIPT_ID,
            T1,
            observer_device="linux",
        )
        requested_line = self.ledger.events_path.read_bytes().splitlines(
            keepends=True
        )[0]
        self.ledger.events_path.write_bytes(requested_line)

        changed = DeliveryLedger(self.root).record_confirmed(
            REVISION_ID,
            "android",
            RECEIPT_ID,
            T1,
            observer_device="linux",
        )

        self.assertFalse(changed)
        self.assertEqual(
            tuple(event.kind for event in DeliveryLedger(self.root).events()),
            (EventKind.TRANSFER_REQUESTED, EventKind.REASONNOTES_CONFIRMED),
        )

    def test_delivery_transactions_serialize_across_ledger_instances(self):
        second_revision = "b" * 64
        first_ledger = DeliveryLedger(self.root)
        second_ledger = DeliveryLedger(self.root)
        first_read = Event()
        release_first = Event()
        second_read = Event()
        errors = []
        original_first_read = first_ledger._read
        original_second_read = second_ledger._read

        def paused_first_read():
            entries = original_first_read()
            first_read.set()
            if not release_first.wait(2):
                raise AssertionError("timed out waiting to release first transaction")
            return entries

        def observed_second_read():
            second_read.set()
            return original_second_read()

        first_ledger._read = paused_first_read
        second_ledger._read = observed_second_read

        def request_first():
            try:
                first_ledger.record_requested(REVISION_ID, "android", T0)
            except BaseException as error:
                errors.append(error)

        def register_second():
            try:
                second_ledger.register_local(second_revision, "android")
            except BaseException as error:
                errors.append(error)

        requesting = Thread(target=request_first)
        requesting.start()
        self.assertTrue(first_read.wait(2))
        registering = Thread(target=register_second)
        registering.start()
        read_during_transaction = second_read.wait(0.2)
        release_first.set()
        requesting.join(2)
        registering.join(2)

        self.assertFalse(read_during_transaction)
        self.assertFalse(requesting.is_alive())
        self.assertFalse(registering.is_alive())
        self.assertEqual(errors, [])
        reopened = DeliveryLedger(self.root)
        self.assertEqual(
            reopened.entry(REVISION_ID, "android").state,
            DeliveryState.TRANSFER_REQUESTED,
        )
        self.assertEqual(
            reopened.entry(second_revision, "android").state,
            DeliveryState.PENDING,
        )


class RepositoryDeliveryHookTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = Repository.open(
            self.root, local_device="linux", target_device="android"
        )

    def test_local_creation_registers_pending_after_history_exists(self):
        revision = self.repository.create_revision(NOTE_ID, "local", "linux", T0)

        ledger = DeliveryLedger(self.root)
        entry = ledger.entry(revision.revision_id, "android")
        self.assertEqual(entry.state, DeliveryState.PENDING)
        self.assertEqual(entry.attempts, 0)
        self.assertTrue(
            (
                self.root
                / "history"
                / str(NOTE_ID)
                / f"{revision.revision_id}.md"
            ).exists()
        )

    def test_imported_remote_revision_is_not_reflected_back_automatically(self):
        remote = Revision.create(NOTE_ID, (), "android", T0, False, "remote")

        outcome = self.repository.import_bytes(remote.serialize(), "remote.md")

        ledger = DeliveryLedger(self.root)
        self.assertEqual(outcome.kind, "imported")
        self.assertIsNone(ledger.entry(remote.revision_id, "android"))
        self.assertEqual(ledger.eligible("android"), ())
        imported = tuple(
            event
            for event in ledger.events()
            if event.kind == EventKind.REASONNOTES_IMPORTED
        )
        self.assertEqual(len(imported), 1)
        self.assertEqual(imported[0].revision_id, remote.revision_id)
        self.assertEqual(imported[0].observer_device, "linux")

    def test_local_conflict_resolution_registers_pending(self):
        root = self.repository.create_revision(NOTE_ID, "base", "linux", T0)
        linux = self.repository.create_revision(NOTE_ID, "linux", "linux", T1)
        android = Revision.create(
            NOTE_ID, (root.revision_id,), "android", T2, False, "android"
        )
        self.repository.import_bytes(android.serialize(), "android.md")

        resolution = resolve_conflict(
            self.repository, NOTE_ID, "keep-linux", "linux", T3
        )

        ledger = DeliveryLedger(self.root)
        self.assertEqual(
            ledger.entry(resolution.revision_id, "android").state,
            DeliveryState.PENDING,
        )
        self.assertEqual(resolution.body, linux.body)


if __name__ == "__main__":
    unittest.main()
