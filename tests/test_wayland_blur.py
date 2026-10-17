"""Wayland blur must be optional, reversible and never required to edit notes."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch
from types import SimpleNamespace

from PySide6.QtCore import QObject, QSettings, QCoreApplication, Signal
from PySide6.QtTest import QTest
from PySide6.QtGui import QGuiApplication
from PySide6.QtQuick import QQuickWindow

from reasonnotes_app.preferences import AppPreferences
from reasonnotes_app.wayland_blur import WaylandBlurController


class TestWindow(QObject):
    __test__ = False
    frameSwapped = Signal()

    def __init__(self):
        super().__init__()
        self.update_count = 0

    def update(self):
        self.update_count += 1

    def winId(self):
        return 0x112233


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

    def test_initial_blur_waits_for_real_first_frame_and_commits_without_resize(self):
        with TemporaryDirectory() as folder:
            prefs = AppPreferences(
                QSettings(str(Path(folder) / "prefs.ini"), QSettings.IniFormat)
            )
            prefs.setThemeMode("glass")
            window = TestWindow()
            controller = WaylandBlurController(prefs)
            fake_enable = Mock()

            def enable(display, compositor, surface, result):
                self.assertEqual((display, compositor, surface), (123, 456, 0x112233))
                result._obj.value = 0
                return 789

            fake_enable.side_effect = enable
            fake_disable = Mock()
            library = SimpleNamespace(
                rn_blur_enable=fake_enable, rn_blur_disable=fake_disable
            )
            app = Mock()
            app.platformName.return_value = "wayland"
            app.nativeInterface.return_value = SimpleNamespace(
                display=lambda: 123, compositor=lambda: 456
            )
            with patch("reasonnotes_app.wayland_blur.QGuiApplication.instance", return_value=app):
                controller._library = library
                controller.attach(window)
                self.assertFalse(controller.active)
                fake_enable.assert_not_called()
                self.assertEqual(1, window.update_count)
                # The first frame is still opaque; the protocol registration
                # and alpha transition only occur *after* frameSwapped.
                window.frameSwapped.emit()
                fake_enable.assert_not_called()
                QCoreApplication.processEvents()
                self.assertTrue(controller.active)
                fake_enable.assert_called_once()
                self.assertGreaterEqual(window.update_count, 2)
                QTest.qWait(65)
                # One immediate and one delayed bounded Qt Quick repaint
                # force compositor state commits without resizing the window.
                self.assertGreaterEqual(window.update_count, 4)
                controller.close()
                fake_disable.assert_called_once_with(789)

    def test_closing_before_first_frame_never_enables_translucent_blur(self):
        with TemporaryDirectory() as folder:
            prefs = AppPreferences(
                QSettings(str(Path(folder) / "prefs.ini"), QSettings.IniFormat)
            )
            prefs.setThemeMode("glass")
            window = TestWindow()
            controller = WaylandBlurController(prefs)
            with patch("reasonnotes_app.wayland_blur.QGuiApplication.instance") as app:
                app.return_value.platformName.return_value = "wayland"
                controller.attach(window)
                controller.close()
                window.frameSwapped.emit()
                QCoreApplication.processEvents()
                self.assertFalse(controller.active)
                self.assertIsNone(controller._window)

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
