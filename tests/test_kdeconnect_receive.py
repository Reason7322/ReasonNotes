"""Isolated regression tests for KDE Connect's real Downloads receive behavior."""

from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import UUID

from reasonnotes_sync.envelope import Revision
from reasonnotes_sync.repository import Repository
from reasonnotes_sync.sync import scan_inbox, sync_linux


NOTE = UUID("88888888-1111-4111-8111-111111111111")
T0 = datetime(2026, 10, 15, 10, 0, tzinfo=timezone.utc)


class ReceiveDirectoryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.inbox = self.base / "inbox"
        self.downloads = self.base / "Downloads"
        self.inbox.mkdir()
        self.downloads.mkdir()
        self.repository = Repository.open(
            self.base / "repo", local_device="linux", target_device="android"
        )

    def test_extensionless_android_timestamp_revision_imported(self):
        revision = Revision.create(NOTE, (), "android", T0, False, "# Hello\n\nWorld")
        received = self.downloads / "1792061895446"
        received.write_bytes(revision.serialize())

        result = scan_inbox(
            self.repository, self.inbox, "linux", received_dir=self.downloads
        )

        self.assertEqual(result.imported, (revision.revision_id,))
        self.assertEqual(result.quarantined, ())
        self.assertTrue(received.is_file())
        self.assertEqual(received.read_bytes(), revision.serialize())

    def test_hashed_markdown_download_imports_and_remains_in_place(self):
        revision = Revision.create(NOTE, (), "android", T0, False, "# New note")
        received = self.downloads / f"{revision.revision_id}.md"
        received.write_bytes(revision.serialize())

        result = scan_inbox(
            self.repository, self.inbox, "linux", received_dir=self.downloads
        )

        self.assertEqual(result.imported, (revision.revision_id,))
        self.assertEqual(received.read_bytes(), revision.serialize())

    def test_sync_entrypoint_scans_additional_received_folder_first(self):
        revision = Revision.create(NOTE, (), "android", T0, False, "# Sync path")
        (self.downloads / f"{revision.revision_id}.md").write_bytes(revision.serialize())

        class OfflineAdapter:
            device_id = "offline-test-device"

            def reachable(self):
                return False

            def request_transfer(self, _path):
                raise AssertionError("read-only import test must not send files")

        result = sync_linux(
            self.repository, self.inbox, OfflineAdapter(),
            received_dir=self.downloads,
        )
        self.assertEqual(result.imports, (revision.revision_id,))
        self.assertEqual(self.repository.graph().heads(NOTE), (revision,))

    def test_cli_scan_accepts_optional_received_dir(self):
        from io import StringIO
        from reasonnotes_sync.cli import main

        revision = Revision.create(NOTE, (), "android", T0, False, "# CLI")
        (self.downloads / f"{revision.revision_id}.md").write_bytes(revision.serialize())
        output = StringIO()

        code = main([
            "--root", str(self.base / "cli-repo"), "scan",
            "--inbox", str(self.inbox), "--received-dir", str(self.downloads),
        ], stdout=output)

        self.assertEqual(code, 0)
        self.assertIn("imported=" + revision.revision_id, output.getvalue())

    def test_unrelated_downloads_not_imported_or_modified(self):
        unrelated = {
            "important.md": b"# User's ordinary Markdown\n",
            "unrelated.txt": b"---\nreasonnotes-format: 1\n",
            "1792061895447": b"plain text",
            "picture.jpg": b"\xff\xd8\xff\x00",
            "random-note.md": b"---\nreasonnotes-format: 1\n",
        }
        for name, data in unrelated.items():
            (self.downloads / name).write_bytes(data)

        result = scan_inbox(
            self.repository, self.inbox, "linux", received_dir=self.downloads
        )

        self.assertEqual(result.imported, ())
        self.assertEqual(result.quarantined, ())
        self.assertEqual(result.errors, ())
        self.assertEqual(self.repository.revisions(), ())
        for name, data in unrelated.items():
            self.assertEqual((self.downloads / name).read_bytes(), data)

    def test_kde_connect_collision_suffix_revisions_import_without_renaming(self):
        revision = Revision.create(NOTE, (), "android", T0, False, "# Collision")
        received = self.downloads / f"{revision.revision_id} (1).md"
        received.write_bytes(revision.serialize())
        report = scan_inbox(
            self.repository, self.inbox, "linux", received_dir=self.downloads
        )
        self.assertEqual(report.imported, (revision.revision_id,))
        self.assertEqual(received.read_bytes(), revision.serialize())

    def test_collisions_ignore_unrelated_and_invalid_names(self):
        revision = Revision.create(NOTE, (), "android", T0, False, "# Collision")
        for name in (
            "notes (1).md",
            "notes.md (1)",
            f"{revision.revision_id} (0).md",
            f"{revision.revision_id} (oops).md",
        ):
            (self.downloads / name).write_bytes(revision.serialize())
        report = scan_inbox(
            self.repository, self.inbox, "linux", received_dir=self.downloads
        )
        self.assertEqual(report.imported, ())
        self.assertEqual(self.repository.revisions(), ())

    def test_extensionless_arbitrary_name_remains_ignored(self):
        revision = Revision.create(NOTE, (), "android", T0, False, "hello")
        (self.downloads / "user-private-document").write_bytes(revision.serialize())

        result = scan_inbox(
            self.repository, self.inbox, "linux", received_dir=self.downloads
        )

        self.assertEqual(result.imported, ())
        self.assertEqual(self.repository.revisions(), ())

    def test_existing_inbox_and_received_dir_are_scanned_together_without_duplicates(self):
        revision = Revision.create(NOTE, (), "android", T0, False, "hello")
        (self.inbox / "incoming.md").write_bytes(revision.serialize())
        (self.downloads / f"{revision.revision_id}.md").write_bytes(revision.serialize())

        result = scan_inbox(
            self.repository, self.inbox, "linux", received_dir=self.downloads
        )

        self.assertEqual(result.imported, (revision.revision_id,))
        self.assertEqual(result.duplicates, (revision.revision_id,))

    def test_receipt_is_recognized_in_received_downloads(self):
        from reasonnotes_sync.delivery import DeliveryLedger, DeliveryState
        from reasonnotes_sync.receipt import ReceiptEnvelope

        local = self.repository.create_revision(NOTE, "# Example", "linux", T0)
        ledger = DeliveryLedger(self.repository.root)
        ledger.record_requested(local.revision_id, "android", T0)
        receipt = ReceiptEnvelope.create(
            NOTE, local.revision_id, "android", "linux", T0
        )
        received = self.downloads / f"{receipt.receipt_id}.receipt (1).md"
        received.write_bytes(receipt.serialize())

        result = scan_inbox(
            self.repository, self.inbox, "linux", received_dir=self.downloads
        )

        self.assertEqual(result.confirmed, (local.revision_id,))
        self.assertEqual(
            DeliveryLedger(self.repository.root).entry(
                local.revision_id, "android"
            ).state,
            DeliveryState.CONFIRMED,
        )
        self.assertEqual(received.read_bytes(), receipt.serialize())

    def test_missing_explicit_received_dir_returns_error(self):
        result = scan_inbox(
            self.repository, self.inbox, "linux", received_dir=self.base / "missing"
        )
        self.assertTrue(any("not a directory" in e for e in result.errors))


if __name__ == "__main__":
    unittest.main()
