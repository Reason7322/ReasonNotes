"""Persistent user interface preferences, independent from immutable note history."""
from __future__ import annotations

from PySide6.QtCore import QObject, Property, QSettings, Signal, Slot


class AppPreferences(QObject):
    themeModeChanged = Signal()
    languageChanged = Signal()

    def __init__(self, settings: QSettings | None = None, parent: QObject | None = None):
        super().__init__(parent)
        self._settings = settings if settings is not None else QSettings()
        self._theme_mode = self._load_choice("ui/themeMode", ("dark", "light"), "dark")
        self._language = self._load_choice("ui/language", ("en", "pl"), "en")

    def _load_choice(self, key: str, allowed: tuple[str, ...], default: str) -> str:
        saved = self._settings.value(key, default)
        return saved if isinstance(saved, str) and saved in allowed else default

    @Property(str, notify=themeModeChanged)
    def themeMode(self) -> str:
        return self._theme_mode

    @themeMode.setter
    def themeMode(self, new_value: str) -> None:
        if new_value not in ("dark", "light"):
            return
        if new_value == self._theme_mode:
            return
        self._settings.setValue("ui/themeMode", new_value)
        self._settings.sync()
        if self._settings.status() != QSettings.NoError:
            raise RuntimeError("Unable to persist ReasonNotes appearance preference")
        self._theme_mode = new_value
        self.themeModeChanged.emit()

    @Property(str, notify=languageChanged)
    def language(self) -> str:
        return self._language

    @language.setter
    def language(self, new_value: str) -> None:
        if new_value not in ("en", "pl"):
            return
        if new_value == self._language:
            return
        self._settings.setValue("ui/language", new_value)
        self._settings.sync()
        if self._settings.status() != QSettings.NoError:
            raise RuntimeError("Unable to persist ReasonNotes language preference")
        self._language = new_value
        self.languageChanged.emit()

    @Slot(str)
    def setThemeMode(self, value: str) -> None:
        self.themeMode = value

    @Slot(str)
    def setLanguage(self, value: str) -> None:
        self.language = value
