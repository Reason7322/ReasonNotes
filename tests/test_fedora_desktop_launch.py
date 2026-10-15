"""Fedora native app desktop entry, portable self-test and isolated installer."""
import importlib.util
import os
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import subprocess
import sys
import unittest
from unittest.mock import patch


PROJECT = Path(__file__).resolve().parents[1]
ENTRY = PROJECT / "linux-app" / "reasonnotes.py"
INSTALLER = PROJECT / "scripts" / "install-fedora-desktop.py"


def load_script(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DesktopLaunchTest(unittest.TestCase):
    def test_normal_launch_requires_no_options(self):
        application = load_script(ENTRY, "reasonnotes_desktop")
        args = application.parser().parse_args([])
        self.assertIsNone(args.root)
        self.assertIsNone(args.inbox)
        self.assertIsNone(args.device_id)
        self.assertEqual(Path.home() / "Downloads", args.received_dir)

    def test_xdg_notes_are_outside_source_checkout_and_config(self):
        application = load_script(ENTRY, "reasonnotes_xdg")
        with TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            with patch.dict(os.environ, {"XDG_DATA_HOME": str(root / "data")}):
                self.assertEqual(root / "data" / "ReasonNotes", application.data_directory())
            with patch.dict(os.environ, {"XDG_DATA_HOME": "relative/path"}):
                self.assertNotEqual(root / "data" / "ReasonNotes", application.data_directory())

    def test_device_auto_detection_is_unambiguous_and_optional(self):
        application = load_script(ENTRY, "reasonnotes_discovery")
        def one(*args, **kwargs):
            self.assertIn("timeout", kwargs)
            return SimpleNamespace(returncode=0, stdout="a" * 32 + "\n")
        self.assertEqual("a" * 32, application.discover_device_id(one))
        self.assertIsNone(application.discover_device_id(
            lambda *a, **k: SimpleNamespace(returncode=0, stdout="a" * 32 + "\n" + "b" * 32)
        ))
        self.assertIsNone(application.discover_device_id(
            lambda *a, **k: SimpleNamespace(returncode=1, stdout="")
        ))
        self.assertIsNone(application.discover_device_id(
            lambda *a, **k: (_ for _ in ()).throw(FileNotFoundError())
        ))

    def test_offscreen_standalone_qml_load_does_not_touch_user_notes(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            env = {**os.environ, "QT_QPA_PLATFORM": "offscreen", "PYTHONDONTWRITEBYTECODE": "1"}
            command = [
                sys.executable, str(ENTRY), "--root", str(root / "repository"),
                "--inbox", str(root / "inbox"), "--device-id", "a" * 32,
                "--check-launch",
            ]
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertTrue((root / "repository" / "history").is_dir())
            self.assertTrue((root / "inbox").is_dir())

    def test_zero_arg_receive_imports_real_archive_without_sending_or_mutating_zip(self):
        import hashlib
        from datetime import datetime, timezone
        from io import BytesIO
        from uuid import UUID
        from zipfile import ZipFile
        from reasonnotes_sync.envelope import Revision
        from reasonnotes_sync.receipt import ReceiptEnvelope
        from reasonnotes_sync.repository import Repository
        from reasonnotes_app.service import NoteService

        with TemporaryDirectory() as temp:
            home = Path(temp)
            downloads = home / "Downloads"
            downloads.mkdir()
            note = UUID("ab111111-1111-4111-8111-111111111111")
            revision = Revision.create(
                note, (), "android",
                datetime(2026, 10, 15, 13, 0, tzinfo=timezone.utc),
                False, "# Arrived note\n\nA test",
            )
            unrelated_receipt = ReceiptEnvelope.create(
                note, "f" * 64, "android", "linux",
                datetime(2026, 10, 15, 13, 1, tzinfo=timezone.utc),
            )
            zip_contents = BytesIO()
            with ZipFile(zip_contents, "w") as archive:
                archive.writestr(revision.revision_id + ".md", revision.serialize())
                archive.writestr(
                    unrelated_receipt.receipt_id + ".receipt.md",
                    unrelated_receipt.serialize(),
                )
            contents = zip_contents.getvalue()
            name = "reasonnotes-bundle-" + hashlib.sha256(contents).hexdigest() + ".zip"
            original = downloads / name
            original.write_bytes(contents)
            env = {
                **os.environ,
                "HOME": str(home),
                "XDG_DATA_HOME": str(home / ".local/share"),
                "QT_QPA_PLATFORM": "offscreen",
                "PYTHONDONTWRITEBYTECODE": "1",
            }
            command = [
                sys.executable, str(ENTRY), "--check-launch",
                "--device-id", "a" * 32,
            ]
            for _ in range(2):
                result = subprocess.run(
                    command, env=env, capture_output=True,
                    text=True, timeout=15,
                )
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertNotIn("receive warning", result.stderr)
            repo_root = home / ".local/share/ReasonNotes/repository"
            repo = Repository.open(repo_root, local_device="linux", target_device="android")
            self.assertEqual([revision.revision_id], [r.revision_id for r in repo.revisions()])
            self.assertEqual("Arrived note", NoteService(repo).list_notes()[0].title)
            self.assertEqual(contents, original.read_bytes())
            self.assertFalse((home / "Downloads" / "modified").exists())

    def test_user_desktop_installer_never_overwrites_existing_release_or_note_store(self):
        installer = load_script(INSTALLER, "reasonnotes_installer")
        with TemporaryDirectory() as temp:
            home = Path(temp)
            notes = home / ".local/share/ReasonNotes/repository/history/a.md"
            notes.parent.mkdir(parents=True)
            notes.write_text("personal notes remain untouched", encoding="utf-8")
            executable, desktop = installer.install_release("a" * 40, home)
            self.assertTrue(executable.is_file())
            self.assertTrue(os.access(executable, os.X_OK))
            self.assertIn("Exec=" + str(executable), desktop.read_text())
            self.assertIn("Terminal=false", desktop.read_text())
            icon = home / ".local/share/icons/hicolor/scalable/apps/org.reasonnotes.ReasonNotes.svg"
            self.assertTrue(icon.is_file())
            self.assertEqual(notes.read_text(), "personal notes remain untouched")
            with self.assertRaises(FileExistsError):
                installer.install_release("a" * 40, home)
            self.assertEqual(notes.read_text(), "personal notes remain untouched")


if __name__ == "__main__":
    unittest.main()
