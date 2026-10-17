"""Optional native Wayland compositor blur for ReasonNotes Liquid Glass.

Uses ext-background-effect-v1 on Qt's *own* Wayland connection and wl_surface.
Never opens a second wl_display, modifies user compositor config, polls the
GPU, or adds an in-app full-window shader. Safe opaque fallback on failure.
"""
from __future__ import annotations

import ctypes
import logging
from pathlib import Path

from PySide6.QtCore import QObject, Property, QTimer, Signal, Slot, qVersion
from PySide6.QtGui import QGuiApplication

_LOG = logging.getLogger(__name__)
_NATIVE_LIBRARY = (
    Path(__file__).resolve().parents[2]
    / "linux-app" / "native" / "libreasonnotes_wayland_blur.so"
)


class WaylandBlurController(QObject):
    activeChanged = Signal()

    def __init__(self, preferences, library_path: Path | None = None, parent=None):
        super().__init__(parent)
        self.preferences = preferences
        self.library_path = (
            Path(library_path) if library_path is not None else _NATIVE_LIBRARY
        )
        self._library = None
        self._handle = None
        self._window = None
        self._active = False
        self._last_error = ""
        self._first_frame_ready = False
        self.preferences.themeModeChanged.connect(self.refresh)

    @Property(bool, notify=activeChanged)
    def active(self):
        return self._active

    @property
    def last_error(self) -> str:
        return self._last_error

    def attach(self, window):
        """Call after QML has constructed the ApplicationWindow."""
        if self._window is not None:
            raise RuntimeError("Wayland blur may be attached to one window only")
        self._window = window
        # A blur effect registered before the first wl_surface commit may not
        # appear until a window resize causes another commit. Wait for Qt to
        # have actually presented its first opaque frame, then install the
        # extension and schedule a new Qt Quick render/Wayland commit.
        window.frameSwapped.connect(self._on_first_frame_swapped)
        window.update()
        self.refresh()

    @Slot()
    def _on_first_frame_swapped(self) -> None:
        if self._first_frame_ready or self._window is None:
            return
        self._first_frame_ready = True
        self._window.frameSwapped.disconnect(self._on_first_frame_swapped)
        # The first Qt-presented frame is complete. Install blur on the next
        # GUI event turn, so it is followed by a genuine surface commit.
        QTimer.singleShot(0, self.refresh)

    def _commit_blur_surface(self) -> None:
        if self._window is None:
            return
        # QQuickWindow.update() schedules a real render/Wayland buffer commit.
        # QWindow.requestUpdate() only posts an UpdateRequest and can leave
        # compositor background-effect state unapplied until a manual resize.
        self._window.update()

    def _set_active(self, active: bool) -> None:
        if active != self._active:
            self._active = active
            self.activeChanged.emit()
            if self._window is not None:
                self._commit_blur_surface()

    def _load_native(self) -> bool:
        if self._library:
            return True
        try:
            lib = ctypes.CDLL(str(self.library_path))
            lib.rn_blur_enable.argtypes = [
                ctypes.c_void_p, ctypes.c_void_p,
                ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)
            ]
            lib.rn_blur_enable.restype = ctypes.c_void_p
            lib.rn_blur_disable.argtypes = [ctypes.c_void_p]
            lib.rn_blur_disable.restype = None
            self._library = lib
            return True
        except (OSError, AttributeError) as err:
            self._last_error = f"Native Wayland blur helper unavailable: {err}"
            _LOG.warning("%s", self._last_error)
            return False

    @Slot()
    def refresh(self) -> None:
        if self._window is None:
            return
        app = QGuiApplication.instance()
        eligible = (
            self.preferences.themeMode == "glass"
            and app is not None
            and app.platformName() == "wayland"
        )
        if not eligible:
            self.disable()
            return
        major_minor = tuple(int(s) for s in qVersion().split(".")[:2])
        if major_minor < (6, 9):
            self._last_error = "Qt Wayland surface pointers require Qt 6.9+"
            return
        # Never expose 72%-alpha glass before Qt has committed one opaque
        # frame and a valid surface has been mapped by the compositor.
        if not self._first_frame_ready:
            return
        if self._handle or not self._load_native():
            return
        try:
            native = app.nativeInterface()
            display = int(native.display())
            compositor = int(native.compositor())
            surface = int(self._window.winId())
            if not all((display, compositor, surface)):
                raise RuntimeError("Qt Wayland surface not initialized")
            result = ctypes.c_int(-99)
            handle = self._library.rn_blur_enable(
                display, compositor, surface, ctypes.byref(result)
            )
            if not handle or result.value != 0:
                self._last_error = (
                    f"Wayland compositor blur not available: code {result.value}"
                )
                _LOG.info(self._last_error)
                return
        except (RuntimeError, TypeError, ValueError, OverflowError) as err:
            self._last_error = f"Wayland compositor blur failed: {err}"
            _LOG.warning("%s", self._last_error)
            return
        self._handle = handle
        self._last_error = ""
        self._set_active(True)
        # The effect and alpha switch must be committed *after* registering
        # the effect. A second bounded render covers delayed compositor
        # application without changing the window size or moving the cursor.
        QTimer.singleShot(0, self._commit_blur_surface)
        QTimer.singleShot(40, self._commit_blur_surface)

    def disable(self) -> None:
        if self._handle is not None:
            self._set_active(False)
            self._library.rn_blur_disable(self._handle)
            self._handle = None
        else:
            self._set_active(False)

    def close(self) -> None:
        # Called before QQuickWindow and Wayland display are destroyed.
        self.disable()
        if self._window is not None and not self._first_frame_ready:
            try:
                self._window.frameSwapped.disconnect(self._on_first_frame_swapped)
            except (RuntimeError, TypeError):
                pass
        self._window = None
