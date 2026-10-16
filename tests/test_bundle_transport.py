"""No-phone regression tests for single-carrier KDE Connect ZIP exchange."""

from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path
from tempfile import TemporaryDirectory
from uuid import UUID
import hashlib
import unittest
from unittest.mock import patch
from zipfile import ZIP_DEFLATED, ZipFile

from reasonnotes_sync.bundle import BundleError, read_bundle
from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.receipt import ReceiptEnvelope
from reasonnotes_sync.repository import Repository
from reasonnotes_sync.sync import scan_inbox


NOTE = UUID("a2222222-1111-4111-8111-111111111111")
OTHER = UUID("b2222222-1111-4111-8111-111111111111")
WHEN = datetime(2026, 10, 15, 13, 0, tzinfo=timezone.utc)


def carrier(members, *, collision=""):
    output = BytesIO()
    with ZipFile(output, "w", compression=ZIP_DEFLATED) as archive:
        for name, data in members:
            archive.writestr(name, data)
    data = output.getvalue()
    return f"reasonnotes-bundle-{hashlib.sha256(data).hexdigest()}{collision}.zip", data


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.inbox = self.root / "inbox"
        self.downloads = self.root / "Downloads"
        self.inbox.mkdir()
        self.downloads.mkdir()
        self.repository = Repository.open(
            self.root / "repository", local_device="linux", target_device="android"
        )

    def test_receipt_and_revision_import_from_one_archive(self):
        local = self.repository.create_revision(NOTE, "linux text", "linux", WHEN)
        from reasonnotes_sync.delivery import DeliveryLedger
        ledger = DeliveryLedger(self.repository.root)
        ledger.record_requested(local.revision_id, "android", WHEN)
        ack = ReceiptEnvelope.create(
            NOTE, local.revision_id, "android", "linux", WHEN
        )
        remote = Revision.create(OTHER, (), "android", WHEN, False, "# Android\n")
        name, data = carrier([
            (f"{ack.receipt_id}.receipt.md", ack.serialize()),
            (f"{remote.revision_id}.md", remote.serialize()),
        ])
        incoming = self.downloads / name
        incoming.write_bytes(data)

        result = scan_inbox(
            self.repository, self.inbox, "linux", received_dir=self.downloads
        )

        self.assertEqual(result.imported, (remote.revision_id,))
        self.assertEqual(result.confirmed, (local.revision_id,))
        self.assertEqual(len(result.receipts_queued), 1)
        self.assertEqual(result.errors, ())
        self.assertEqual(incoming.read_bytes(), data)

    def test_duplicate_carrier_filename_imports_while_untouched(self):
        revisions = [
            Revision.create(NOTE, (), "android", WHEN, False, "# One"),
            Revision.create(OTHER, (), "android", WHEN, False, "# Two"),
        ]
        name, data = carrier(
            [(f"{r.revision_id}.md", r.serialize()) for r in revisions],
            collision=" (1)",
        )
        (self.downloads / name).write_bytes(data)
        report = scan_inbox(
            self.repository, self.inbox, "linux", received_dir=self.downloads
        )
        self.assertEqual(set(report.imported), {r.revision_id for r in revisions})
        self.assertEqual(report.errors, ())
        report2 = scan_inbox(
            self.repository, self.inbox, "linux", received_dir=self.downloads
        )
        self.assertEqual(set(report2.duplicates), {r.revision_id for r in revisions})

    def test_repeated_bundle_scan_uses_no_per_member_history_lookups(self):
        remote = [
            Revision.create(NOTE, (), "android", WHEN, False, "first"),
            Revision.create(OTHER, (), "android", WHEN, False, "second"),
        ]
        name, data = carrier([
            (f"{revision.revision_id}.md", revision.serialize())
            for revision in remote
        ])
        (self.downloads / name).write_bytes(data)
        first = scan_inbox(self.repository, self.inbox, "linux", received_dir=self.downloads)
        self.assertEqual(set(first.imported), {revision.revision_id for revision in remote})
        self.assertEqual(2, len(first.receipts_queued))

        with (
            patch.object(self.repository, "revision", side_effect=AssertionError("per-member history scan")),
            patch.object(self.repository, "reconcile", side_effect=AssertionError("duplicate reconcile")),
        ):
            second = scan_inbox(
                self.repository, self.inbox, "linux", received_dir=self.downloads
            )
        self.assertEqual(set(second.duplicates), {revision.revision_id for revision in remote})
        self.assertEqual(second.receipts_queued, ())
        self.assertEqual(second.errors, ())

    def test_invalid_member_rejects_entire_bundle_before_repository_mutation(self):
        good = Revision.create(NOTE, (), "android", WHEN, False, "# Good")
        name, data = carrier([
            (f"{good.revision_id}.md", good.serialize()),
            ("../payload.md", b"not a revision"),
        ])
        (self.downloads / name).write_bytes(data)
        report = scan_inbox(self.repository, self.inbox, "linux", received_dir=self.downloads)
        self.assertEqual(report.imported, ())
        self.assertEqual(self.repository.revisions(), ())
        self.assertTrue(any("unsafe" in e for e in report.errors))

    def test_member_name_hash_must_match_envelope(self):
        a = Revision.create(NOTE, (), "android", WHEN, False, "# Good")
        b = Revision.create(OTHER, (), "android", WHEN, False, "# Good")
        name, data = carrier([
            (f"{a.revision_id}.md", b.serialize()),
            (f"{b.revision_id}.md", a.serialize()),
        ])
        self.assertRaises(BundleError, read_bundle, data, name)

    def test_reject_modified_archive_hash(self):
        a = Revision.create(NOTE, (), "android", WHEN, False, "# A")
        b = Revision.create(OTHER, (), "android", WHEN, False, "# B")
        name, data = carrier([
            (f"{a.revision_id}.md", a.serialize()),
            (f"{b.revision_id}.md", b.serialize()),
        ])
        self.assertRaises(BundleError, read_bundle, data + b"X", name)

    def test_reject_duplicate_member_names(self):
        a = Revision.create(NOTE, (), "android", WHEN, False, "# A")
        name, data = carrier([
            (f"{a.revision_id}.md", a.serialize()),
            (f"{a.revision_id}.md", a.serialize()),
        ])
        self.assertRaises(BundleError, read_bundle, data, name)

    def test_twenty_one_receipts_confirm_individually_from_one_archive(self):
        from reasonnotes_sync.delivery import DeliveryLedger

        ledger = DeliveryLedger(self.repository.root)
        receipts = []
        revisions = []
        for index in range(21):
            note = UUID(int=0xA0000000000000000000000000000000 + index)
            local = self.repository.create_revision(note, f"# Linux {index}", "linux", WHEN)
            ledger.record_requested(local.revision_id, "android", WHEN)
            receipt = ReceiptEnvelope.create(
                note, local.revision_id, "android", "linux", WHEN
            )
            receipts.append((f"{receipt.receipt_id}.receipt.md", receipt.serialize()))
            revisions.append(local.revision_id)
        name, data = carrier(receipts)
        (self.downloads / name).write_bytes(data)

        report = scan_inbox(self.repository, self.inbox, "linux", received_dir=self.downloads)

        self.assertEqual(set(report.confirmed), set(revisions))
        self.assertEqual(len(report.confirmed), 21)
        self.assertEqual(report.errors, ())
        self.assertEqual(report.imported, ())

    def test_unrelated_download_zip_ignored(self):
        archive = self.downloads / "notes.zip"
        archive.write_bytes(b"User backup; not ReasonNotes")
        report = scan_inbox(self.repository, self.inbox, "linux", received_dir=self.downloads)
        self.assertEqual(report.imported, ())
        self.assertEqual(report.errors, ())
        self.assertEqual(archive.read_bytes(), b"User backup; not ReasonNotes")

    def test_missing_member_or_too_many_members_rejected(self):
        a = Revision.create(NOTE, (), "android", WHEN, False, "# A")
        name, data = carrier([(f"{a.revision_id}.md", a.serialize())])
        self.assertRaises(BundleError, read_bundle, data, name)

    def test_extensionless_zip_payload_not_silently_imported(self):
        a = Revision.create(NOTE, (), "android", WHEN, False, "# A")
        b = Revision.create(OTHER, (), "android", WHEN, False, "# B")
        name, data = carrier([
            (f"{a.revision_id}.md", a.serialize()),
            (f"{b.revision_id}.md", b.serialize()),
        ])
        (self.downloads / "unrelatedfile").write_bytes(data)
        report = scan_inbox(self.repository, self.inbox, "linux", received_dir=self.downloads)
        self.assertEqual(report.imported, ())
        self.assertEqual(report.errors, ())


if __name__ == "__main__":
    unittest.main()
