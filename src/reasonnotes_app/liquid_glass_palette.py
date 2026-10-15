"""Read the user's Noctalia-generated Kvantum LiquidGlass material.

Qt Quick controls are not QWidget/Kvantum widgets. Their palette must be
bridged explicitly. This object watches the existing generated file read-only.
"""
from __future__ import annotations

import configparser
import os
from pathlib import Path

from PySide6.QtCore import QObject, Property, QFileSystemWatcher, Signal, Slot
from PySide6.QtGui import QColor


_DEFAULTS = {
    "window": "#1c2021",
    "base": "#2a2f32B8",
    "alternate": "#171a1c",
    "button": "#2f3537",
    "light": "#383f42",
    "mid": "#636b6e",
    "accent": "#98abb3",
    "text": "#f2f2f3",
    "disabled": "#656a6d",
    "accent_text": "#1c2022",
    "error": "#e58b8b",
}

_CONFIG_KEYS = {
    "window": "window.color",
    "base": "base.color",
    "alternate": "alt.base.color",
    "button": "button.color",
    "light": "light.color",
    "mid": "mid.color",
    "accent": "highlight.color",
    "text": "text.color",
    "disabled": "disabled.text.color",
    "accent_text": "highlight.text.color",
}


def _parse_css_color(source: str) -> QColor:
    """Kvantum uses #RRGGBB or CSS #RRGGBBAA (not Qt's #AARRGGBB)."""
    raw = source.strip()
    if len(raw) == 9 and raw.startswith("#"):
        number = int(raw[1:], 16)
        color = QColor((number >> 24) & 255, (number >> 16) & 255,
                       (number >> 8) & 255, number & 255)
    elif len(raw) == 7 and raw.startswith("#"):
        color = QColor(raw)
    else:
        raise ValueError("Kvantum color must be #RRGGBB or #RRGGBBAA")
    if not color.isValid():
        raise ValueError("Invalid Kvantum color")
    return color


def default_kvantum_file() -> Path:
    configured = os.environ.get("XDG_CONFIG_HOME")
    config_home = (
        Path(configured).expanduser()
        if configured and Path(configured).is_absolute()
        else Path.home() / ".config"
    )
    return config_home / "Kvantum" / "LiquidGlass" / "LiquidGlass.kvconfig"


class LiquidGlassPalette(QObject):
    """Expose Kvantum's active colors to Qt Quick and reload atomic updates."""

    colorsChanged = Signal()

    def __init__(self, path: Path | None = None, parent: QObject | None = None):
        super().__init__(parent)
        self.path = Path(path) if path is not None else default_kvantum_file()
        self._colors = {name: _parse_css_color(value) for name, value in _DEFAULTS.items()}
        self._watcher = QFileSystemWatcher(self)
        self._watcher.fileChanged.connect(self.reload)
        self._watcher.directoryChanged.connect(self.reload)
        self.reload()

    def _arm_watcher(self) -> None:
        # Noctalia can atomically replace the generated KV config. Watch its
        # directory as well as the file, and rearm after replacement.
        parent = str(self.path.parent)
        if self.path.parent.is_dir() and parent not in self._watcher.directories():
            self._watcher.addPath(parent)
        name = str(self.path)
        if self.path.is_file() and name not in self._watcher.files():
            self._watcher.addPath(name)

    @Slot()
    def reload(self) -> None:
        self._arm_watcher()
        if not self.path.is_file():
            return  # defaults or last working palette remain usable
        try:
            config = configparser.ConfigParser(interpolation=None)
            with self.path.open("r", encoding="utf-8") as source:
                config.read_file(source)
            colors = dict(self._colors)
            for name, config_key in _CONFIG_KEYS.items():
                colors[name] = _parse_css_color(
                    config.get("GeneralColors", config_key)
                )
        except (OSError, ValueError, configparser.Error):
            return  # malformed partial rewrite: retain last known good colors
        if any(colors[name] != self._colors[name] for name in colors):
            self._colors = colors
            self.colorsChanged.emit()
        self._arm_watcher()

    def _color(self, key: str) -> QColor:
        return QColor(self._colors[key])

    @Property(QColor, notify=colorsChanged)
    def windowColor(self): return self._color("window")

    @Property(QColor, notify=colorsChanged)
    def baseColor(self): return self._color("base")

    @Property(QColor, notify=colorsChanged)
    def alternateColor(self): return self._color("alternate")

    @Property(QColor, notify=colorsChanged)
    def buttonColor(self): return self._color("button")

    @Property(QColor, notify=colorsChanged)
    def lightColor(self): return self._color("light")

    @Property(QColor, notify=colorsChanged)
    def midColor(self): return self._color("mid")

    @Property(QColor, notify=colorsChanged)
    def accentColor(self): return self._color("accent")

    @Property(QColor, notify=colorsChanged)
    def textColor(self): return self._color("text")

    @Property(QColor, notify=colorsChanged)
    def disabledColor(self): return self._color("disabled")

    @Property(QColor, notify=colorsChanged)
    def accentTextColor(self): return self._color("accent_text")
