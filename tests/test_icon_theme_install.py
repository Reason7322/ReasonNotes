"""Focused regression: icon-theme coexistence and app identity."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
DESKTOP_ID = "org.reasonnotes.ReasonNotes"


def importer():
    path = ROOT / "scripts" / "install-fedora-desktop.py"
    spec = importlib.util.spec_from_file_location("reasonnotes_icon_installer", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class IconThemeInstallTests(unittest.TestCase):
    def test_colors_for_fallback_and_monochrome_override_for_yamis(self):
        installer = importer()
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            yamis = home / ".local/share/icons/YAMIS"
            yamis.mkdir(parents=True)
            (yamis / "index.theme").write_text("[Icon Theme]\nName=YAMIS\n")
            original = home / ".local/share/icons/hicolor/scalable/apps" / (DESKTOP_ID + ".svg")
            original.parent.mkdir(parents=True)
            original.write_bytes((ROOT / "linux-app/icons/reasonnotes.svg").read_bytes())
            with patch.object(installer.subprocess, "run"):
                launcher, shortcut = installer.install_release("ace0123456", home)
            self.assertTrue(launcher.is_file())
            self.assertIn("Icon=" + DESKTOP_ID, shortcut.read_text())
            for size in (32, 48, 64, 128, 256, 512):
                path = home / ".local/share/icons/hicolor" / f"{size}x{size}" / "apps" / (DESKTOP_ID + ".png")
                self.assertEqual(path.read_bytes(), (
                    ROOT / "linux-app/icons/hicolor" / f"{size}x{size}" / "reasonnotes.png").read_bytes())
            themed = yamis / "apps/scalable" / (DESKTOP_ID + ".svg")
            self.assertEqual(themed.read_bytes(), (ROOT / "linux-app/icons/reasonnotes-yamis.svg").read_bytes())
            self.assertIn(b"currentColor", themed.read_bytes())
            self.assertFalse(original.exists(), "outdated fallback scalable icon masks the new PNG")

    def test_customized_existing_fallback_icon_is_not_deleted(self):
        installer = importer()
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            icon = home / ".local/share/icons/hicolor/scalable/apps" / (DESKTOP_ID + ".svg")
            icon.parent.mkdir(parents=True)
            icon.write_bytes(b"<svg>custom user icon</svg>")
            with patch.object(installer.subprocess, "run"):
                installer.install_release("abc0123456", home)
            self.assertEqual(icon.read_bytes(), b"<svg>custom user icon</svg>")
            self.assertFalse((home / ".local/share/icons/YAMIS").exists())


if __name__ == "__main__":
    unittest.main()
