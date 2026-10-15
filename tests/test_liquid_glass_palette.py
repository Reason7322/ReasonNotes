"""Read-only mapping from Noctalia's Kvantum LiquidGlass to Qt Quick."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from PySide6.QtCore import QSettings
from PySide6.QtGui import QGuiApplication

from reasonnotes_app.liquid_glass_palette import (
    LiquidGlassPalette, _parse_css_color,
)
from reasonnotes_app.preferences import AppPreferences


class LiquidGlassPaletteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.application = QGuiApplication.instance() or QGuiApplication(["reasonnotes-liquid-glass-test", "-platform", "offscreen"])

    def test_kvantum_css_alpha_is_parsed_in_css_order(self):
        color = _parse_css_color("#2a2f32B8")
        self.assertEqual("#2a2f32", color.name())
        self.assertEqual(184, color.alpha())
        self.assertAlmostEqual(184 / 255, color.alphaF())
        with self.assertRaises(ValueError):
            _parse_css_color("invalid")

    def test_generated_file_updates_palette_and_preserves_last_good_copy(self):
        with TemporaryDirectory() as folder:
            path = Path(folder) / "Kvantum" / "LiquidGlass" / "LiquidGlass.kvconfig"
            path.parent.mkdir(parents=True)
            original = """[GeneralColors]
window.color=#1c2021
base.color=#2a2f32B8
alt.base.color=#171a1c
button.color=#2f3537
light.color=#383f42
mid.color=#636b6e
highlight.color=#98abb3
text.color=#f2f2f3
disabled.text.color=#656a6d
highlight.text.color=#1c2022
"""
            path.write_text(original, encoding="utf-8")
            palette = LiquidGlassPalette(path)
            self.assertEqual("#98abb3", palette.accentColor.name())
            self.assertEqual("#2a2f32", palette.baseColor.name())
            self.assertEqual(184, palette.baseColor.alpha())
            self.assertIn(str(path), palette._watcher.files())
            self.assertIn(str(path.parent), palette._watcher.directories())

            changed = []
            palette.colorsChanged.connect(lambda: changed.append(palette.accentColor.name()))
            # Noctalia atomically replaces a generated theme rather than
            # editing the old file in-place.
            replacement = path.with_suffix(".tmp")
            replacement.write_text(original.replace("#98abb3", "#67a2bf"))
            replacement.replace(path)
            palette.reload()
            self.assertEqual(["#67a2bf"], changed)
            self.assertEqual("#67a2bf", palette.accentColor.name())
            self.assertIn(str(path), palette._watcher.files())
            palette.reload()
            self.assertEqual(1, len(changed))

            path.write_text("[GeneralColors]\nhighlight.color=invalid\n")
            palette.reload()
            self.assertEqual("#67a2bf", palette.accentColor.name())
            self.assertEqual(1, len(changed))

    def test_third_theme_is_persisted_without_disturbing_language(self):
        with TemporaryDirectory() as folder:
            config = str(Path(folder) / "prefs.ini")
            prefs = AppPreferences(QSettings(config, QSettings.IniFormat))
            prefs.setLanguage("pl")
            prefs.setThemeMode("glass")
            result = AppPreferences(QSettings(config, QSettings.IniFormat))
            self.assertEqual("glass", result.themeMode)
            self.assertEqual("pl", result.language)
            result.setThemeMode("dark")
            result.setThemeMode("light")
            self.assertEqual("light", result.themeMode)


if __name__ == "__main__":
    unittest.main()
