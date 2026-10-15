"""Persistent theme/language preferences without touching the user's note library."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from PySide6.QtCore import QSettings

from reasonnotes_app.preferences import AppPreferences


class AppPreferencesTest(unittest.TestCase):
    def test_saves_both_choices_and_restores_them_from_disk(self):
        with TemporaryDirectory() as directory:
            file_path = str(Path(directory) / "preferences.ini")
            settings = QSettings(file_path, QSettings.IniFormat)
            prefs = AppPreferences(settings)
            theme_changes = []
            language_changes = []
            prefs.themeModeChanged.connect(lambda: theme_changes.append(prefs.themeMode))
            prefs.languageChanged.connect(lambda: language_changes.append(prefs.language))

            self.assertEqual("dark", prefs.themeMode)
            self.assertEqual("en", prefs.language)
            prefs.setThemeMode("light")
            prefs.setLanguage("pl")
            self.assertEqual(["light"], theme_changes)
            self.assertEqual(["pl"], language_changes)
            self.assertTrue(Path(file_path).is_file())

            # A new object must load from the actual persisted file, not
            # share the first instance's in-memory state.
            saved = AppPreferences(QSettings(file_path, QSettings.IniFormat))
            self.assertEqual("light", saved.themeMode)
            self.assertEqual("pl", saved.language)

            # Unchanged/unsupported inputs cannot corrupt the preferences.
            saved.setThemeMode("light")
            saved.setLanguage("pl")
            saved.setThemeMode("neon")
            saved.setLanguage("unknown")
            self.assertEqual("light", saved.themeMode)
            self.assertEqual("pl", saved.language)
            saved.setThemeMode("dark")
            saved.setLanguage("en")
            restored = AppPreferences(QSettings(file_path, QSettings.IniFormat))
            self.assertEqual("dark", restored.themeMode)
            self.assertEqual("en", restored.language)

    def test_invalid_stored_values_fall_back_without_touching_other_keys(self):
        with TemporaryDirectory() as directory:
            path = str(Path(directory) / "preferences.ini")
            settings = QSettings(path, QSettings.IniFormat)
            settings.setValue("ui/themeMode", "invalid")
            settings.setValue("ui/language", "garbled")
            settings.setValue("other/unrelated", "keep")
            settings.sync()
            prefs = AppPreferences(QSettings(path, QSettings.IniFormat))
            self.assertEqual("dark", prefs.themeMode)
            self.assertEqual("en", prefs.language)
            check = QSettings(path, QSettings.IniFormat)
            self.assertEqual("keep", check.value("other/unrelated"))


if __name__ == "__main__":
    unittest.main()
