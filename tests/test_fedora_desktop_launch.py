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
        self.assertIsNone(args.received_dir)

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
            self.assertFalse((root / "inbox").exists())

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
