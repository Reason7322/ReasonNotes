#!/usr/bin/env python3
"""Native Qt Quick ReasonNotes desktop entry point.

Normal launch needs no development arguments. Application code and user note
data live in separate directories; no existing test repositories are imported.
"""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = PROJECT_ROOT / "src"
if str(SOURCE_ROOT) not in sys.path:
    sys.path.insert(0, str(SOURCE_ROOT))

from PySide6.QtCore import QCoreApplication, QUrl
from PySide6.QtGui import QGuiApplication, QIcon
from PySide6.QtQml import QQmlApplicationEngine

from reasonnotes_app.liquid_glass_palette import LiquidGlassPalette
from reasonnotes_app.preferences import AppPreferences
from reasonnotes_app.presenter import NotePresenter
from reasonnotes_app.qt_bridge import QtNotePresenter
from reasonnotes_app.service import NoteService
from reasonnotes_app.wayland_blur import WaylandBlurController
from reasonnotes_sync.kdeconnect_linux import KdeConnectLinux
from reasonnotes_sync.repository import Repository
from reasonnotes_sync.foreground_purge import PurgeRelay, shared_key, PORT
from reasonnotes_sync.sync import scan_inbox, sync_linux


APP_ID = "org.reasonnotes.ReasonNotes"
DEVICE_ID_PATTERN = re.compile(r"[0-9a-f]{32}")


def data_directory() -> Path:
    """XDG-compliant user data; never a source checkout or temporary fixture."""
    home = Path.home()
    xdg = os.environ.get("XDG_DATA_HOME", "")
    base = Path(xdg).expanduser() if xdg and Path(xdg).is_absolute() else home / ".local/share"
    return base / "ReasonNotes"


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(prog="reasonnotes")
    result.add_argument(
        "--root", type=Path, default=None,
        help="notes repository (default: ~/.local/share/ReasonNotes/repository)",
    )
    result.add_argument(
        "--inbox", type=Path, default=None,
        help="private received files folder (default: ~/.local/share/ReasonNotes/inbox)",
    )
    result.add_argument(
        "--received-dir", type=Path, default=Path.home() / "Downloads",
        help="KDE Connect received folder (default: ~/Downloads)",
    )
    result.add_argument(
        "--device-id", default=None,
        help="specific paired Android KDE Connect device ID; otherwise discover one reachable device",
    )
    result.add_argument("--target-device", default="android")
    result.add_argument(
        "--no-auto-receive", action="store_true",
        help="skip incoming note/receipt scan on application startup",
    )
    result.add_argument("--check-launch", action="store_true", help=argparse.SUPPRESS)
    return result


def discover_device_id(command_runner=subprocess.run) -> str | None:
    """Choose only an unambiguous *reachable* KDE Connect peer."""
    try:
        result = command_runner(
            ["kdeconnect-cli", "--list-available", "--id-only"],
            env=KdeConnectLinux.environment(),
            capture_output=True, text=True, timeout=3, check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode:
        return None
    available = {
        line.strip() for line in result.stdout.splitlines()
        if DEVICE_ID_PATTERN.fullmatch(line.strip())
    }
    return next(iter(available)) if len(available) == 1 else None


def main(arguments=None) -> int:
    options = parser().parse_args(arguments)
    root = (options.root or data_directory() / "repository").expanduser()
    inbox = (options.inbox or data_directory() / "inbox").expanduser()
    if options.device_id is not None and not DEVICE_ID_PATTERN.fullmatch(options.device_id):
        raise SystemExit("--device-id must contain exactly 32 lowercase hexadecimal characters")
    device_id = options.device_id or discover_device_id()

    QCoreApplication.setOrganizationName("ReasonNotes")
    QCoreApplication.setOrganizationDomain("reasonnotes.local")
    QCoreApplication.setApplicationName("ReasonNotes")
    application = QGuiApplication([sys.argv[0]])
    application.setDesktopFileName(APP_ID)
    icon = Path(__file__).parent / "icons" / "reasonnotes.svg"
    if icon.is_file():
        application.setWindowIcon(QIcon(str(icon)))

    # Only the designated private user repository is opened at startup.
    # No network transfer, received-file import, or sync is automatic.
    repository = Repository.open(
        root,
        local_device="linux",
        target_device=options.target_device,
    )

    def run_sync(selected):
        if device_id is None:
            raise RuntimeError(
                "No single reachable KDE Connect device found. Pair your phone "
                "or launch ReasonNotes with --device-id."
            )
        inbox.mkdir(parents=True, exist_ok=True)
        return sync_linux(
            repository, inbox, KdeConnectLinux(device_id),
            selected_note_ids=selected, received_dir=options.received_dir,
        )

    # Receive-only startup: validate and durably import KDE Connect payloads
    # into this actual library. Never request a transfer or consume retries.
    # The later Sync button uses the same receive folder, so new arrivals
    # appear after the user requests a normal synchronization as well.
    if not options.no_auto_receive:
        inbox.mkdir(parents=True, exist_ok=True)
        report = scan_inbox(
            repository, inbox, "linux", received_dir=options.received_dir,
        )
        for error in report.errors:
            print("ReasonNotes receive warning: " + error, file=sys.stderr)

    presenter = NotePresenter(
        NoteService(repository, origin_device="linux"), run_sync,
    )
    bridge = QtNotePresenter(presenter)
    preferences = AppPreferences()
    liquid_palette = LiquidGlassPalette()
    blur_controller = WaylandBlurController(preferences)
    engine = QQmlApplicationEngine()
    engine.rootContext().setContextProperty("blurState", blur_controller)
    engine.rootContext().setContextProperty("glassPalette", liquid_palette)
    engine.rootContext().setContextProperty("appPreferences", preferences)
    engine.rootContext().setContextProperty("presenter", bridge)
    engine.rootContext().setContextProperty("smokeTest", False)
    # Foreground-only LAN relay. The key is never sent over HTTP. No socket
    # is opened when checking QML syntax or after the app exits.
    from PySide6.QtCore import QObject, Property, QTimer
    import socket

    class DeletionPairing(QObject):
        @Property(str, constant=True)
        def pairingCode(self):
            return key.hex()

        @Property(str, constant=True)
        def computerAddress(self):
            return display_address

        @Property(str, constant=True)
        def port(self):
            return str(PORT)

    key = bytes(32) if options.check_launch else shared_key(
        data_directory() / "delete-sync-key"
    )
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 80))
            display_address = probe.getsockname()[0]
    except OSError:
        display_address = "See your computer's LAN IPv4 address"
    pairing = DeletionPairing()
    engine.rootContext().setContextProperty("purgeSyncBridge", pairing)
    qml_path = Path(__file__).with_name("qml") / "Main.qml"
    engine.load(QUrl.fromLocalFile(str(qml_path)))
    if not engine.rootObjects():
        return 1
    blur_controller.attach(engine.rootObjects()[0])
    if options.check_launch:
        blur_controller.close()
        return 0

    relay = PurgeRelay(repository, key)
    try:
        relay.start()
    except OSError as error:
        # Do not crash the editor because a LAN port is unavailable.
        print(f"ReasonNotes deletion relay unavailable: {error}", file=sys.stderr)

    # QML state belongs to the GUI thread. Network workers only mutate the
    # shared, locked repository; the GUI refreshes when the counter changes.
    changes_seen = [relay.changes]
    refresh_timer = QTimer()
    refresh_timer.setInterval(600)

    def refresh_after_remote_purge():
        current = relay.changes
        if current == changes_seen[0]:
            return
        changes_seen[0] = current
        if presenter.active_note_id is not None and repository.is_purged(
            UUID(presenter.active_note_id)
        ):
            presenter.active_note_id = None
            presenter._active_head_ids = ()
            presenter.draft = ""
            presenter.dirty = False
            presenter.conflict_view = None
        presenter.refresh()
        bridge._notify(body=True, conflict=True)

    refresh_timer.timeout.connect(refresh_after_remote_purge)
    refresh_timer.start()
    application.aboutToQuit.connect(refresh_timer.stop)
    application.aboutToQuit.connect(relay.stop)
    application.aboutToQuit.connect(blur_controller.close)
    # Hold the bridge/engine alive for the full native Qt event loop.
    return application.exec()


if __name__ == "__main__":
    raise SystemExit(main())
