"""Wayland blur must be optional, reversible and never required to edit notes."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from PySide6.QtCore import QSettings
from PySide6.QtGui import QGuiApplication
from PySide6.QtQuick import QQuickWindow

from reasonnotes_app.preferences import AppPreferences
from reasonnotes_app.wayland_blur import WaylandBlurController


class WaylandBlurTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qt = QGuiApplication.instance() or QGuiApplication(
            ["reasonnotes-wayland-fallback-test", "-platform", "offscreen"]
        )

    def test_optional_blur_falls_back_to_solid_without_wayland(self):
        with TemporaryDirectory() as folder:
            preferences = AppPreferences(
                QSettings(str(Path(folder) / "prefs.ini"), QSettings.IniFormat)
            )
            preferences.setThemeMode("glass")
            controller = WaylandBlurController(
                preferences,
                Path(folder) / "native-binary-does-not-exist.so"
            )
            window = QQuickWindow()
            controller.attach(window)
            self.assertFalse(controller.active)
            self.assertFalse(controller.last_error)
            preferences.setThemeMode("dark")
            self.assertFalse(controller.active)
            preferences.setThemeMode("glass")
            self.assertFalse(controller.active)
            controller.close()
            self.assertIsNone(controller._window)
            window.destroy()

    def test_native_blur_region_commits_to_compositor_without_window_resize(self):
        # ext-background-effect-v1 section set_blur_region explicitly says
        # blur state is double-buffered until wl_surface.commit. The native
        # helper must perform this commit rather than waiting for a resize.
        source = (Path(__file__).resolve().parents[1] /
                  "linux-app/native/reasonnotes_wayland_blur.c").read_text()
        effect = source.index("ext_background_effect_surface_v1_set_blur_region(")
        commit = source.index("wl_surface_commit(state->surface)", effect)
        flush = source.index("wl_display_flush(state->display)", commit)
        self.assertGreater(commit, effect)
        self.assertGreater(flush, commit)
        self.assertIn("wl_surface_commit(state->surface)", source[:effect],
                      "effect disable also requires a surface commit")

    def test_app_requests_rgba_buffer_before_window_creation(self):
        source = (Path(__file__).resolve().parents[1] /
                  "linux-app/reasonnotes.py").read_text()
        assert source.index("QQuickWindow.setDefaultAlphaBuffer(True)") < source.index(
            "application = QGuiApplication("
        )

    def test_active_change_signal_and_no_note_side_effects(self):
        with TemporaryDirectory() as folder:
            preferences = AppPreferences(
                QSettings(str(Path(folder) / "prefs.ini"), QSettings.IniFormat)
            )
            controller = WaylandBlurController(preferences)
            changed = []
            controller.activeChanged.connect(lambda: changed.append(controller.active))
            controller._set_active(True)
            self.assertTrue(controller.active)
            controller._set_active(True)
            controller._set_active(False)
            self.assertEqual([True, False], changed)


if __name__ == "__main__":
    unittest.main()
