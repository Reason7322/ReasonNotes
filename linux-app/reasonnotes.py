#!/usr/bin/env python3
"""Launch the ReasonNotes Qt Quick desktop application."""

import argparse
from pathlib import Path
import sys


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = PROJECT_ROOT / "src"
if str(SOURCE_ROOT) not in sys.path:
    sys.path.insert(0, str(SOURCE_ROOT))

from PySide6.QtCore import QCoreApplication, QUrl
from PySide6.QtGui import QGuiApplication
from PySide6.QtQml import QQmlApplicationEngine

from reasonnotes_app.presenter import NotePresenter
from reasonnotes_app.qt_bridge import QtNotePresenter
from reasonnotes_app.service import NoteService
from reasonnotes_sync.kdeconnect_linux import KdeConnectLinux
from reasonnotes_sync.repository import Repository
from reasonnotes_sync.sync import sync_linux


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(prog="reasonnotes")
    result.add_argument("--root", required=True, type=Path)
    result.add_argument("--inbox", required=True, type=Path)
    result.add_argument(
        "--received-dir", type=Path,
        help="additional KDE Connect receive folder (for example ~/Downloads)",
    )
    result.add_argument("--device-id", required=True)
    result.add_argument("--target-device", default="android")
    return result


def main(arguments=None) -> int:
    options = parser().parse_args(arguments)
    QCoreApplication.setOrganizationName("ReasonNotes")
    QCoreApplication.setOrganizationDomain("reasonnotes.local")
    QCoreApplication.setApplicationName("ReasonNotes")
    application = QGuiApplication([sys.argv[0]])
    repository = Repository.open(
        options.root,
        local_device="linux",
        target_device=options.target_device,
    )
    adapter = KdeConnectLinux(options.device_id)
    presenter = NotePresenter(
        NoteService(repository, origin_device="linux"),
        lambda selected: sync_linux(
            repository,
            options.inbox,
            adapter,
            selected_note_ids=selected,
            received_dir=options.received_dir,
        ),
    )
    bridge = QtNotePresenter(presenter)
    engine = QQmlApplicationEngine()
    engine.rootContext().setContextProperty("presenter", bridge)
    engine.rootContext().setContextProperty("smokeTest", False)
    qml_path = Path(__file__).with_name("qml") / "Main.qml"
    engine.load(QUrl.fromLocalFile(str(qml_path)))
    if not engine.rootObjects():
        return 1
    return application.exec()


if __name__ == "__main__":
    raise SystemExit(main())
