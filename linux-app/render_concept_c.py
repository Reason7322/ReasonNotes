#!/usr/bin/env python3
"""Render the Qt Quick desktop shell from disposable note data."""

import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from uuid import UUID


os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = PROJECT_ROOT / "src"
if str(SOURCE_ROOT) not in sys.path:
    sys.path.insert(0, str(SOURCE_ROOT))

from PySide6.QtCore import QCoreApplication, QMetaObject, QObject, QSettings, QUrl
from PySide6.QtGui import QGuiApplication
from PySide6.QtQml import QQmlApplicationEngine

from reasonnotes_app.presenter import NotePresenter
from reasonnotes_app.qt_bridge import QtNotePresenter
from reasonnotes_app.service import NoteService
from reasonnotes_sync.repository import Repository


SAMPLE_NOTES = (
    "# Project ideas\nA compact place for plans and loose thoughts.",
    "# Shopping list\n- Coffee\n- Oats\n- Fresh fruit",
    "# Linux commands\nUseful commands worth keeping close.",
    "# Conflict example\nA note that demonstrates visible status.",
    """# Welcome to ReasonNotes

A simple, private place for your notes, ideas, and thinking.

## A focused note-taking experience

ReasonNotes is designed to be a calm and reliable space for your thoughts.
It keeps things simple: a clean writing experience with a persistent sidebar.

## Key features

- Simple and fast
- Local first
- Sync when you want
- Plain text Markdown
- Recoverable history

Thanks for trying ReasonNotes!""",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--width", type=int, default=2048)
    parser.add_argument("--height", type=int, default=1152)
    parser.add_argument(
        "--view",
        choices=("editor", "sync", "sync-selected"),
        default="editor",
    )
    return parser.parse_args()


def main() -> int:
    options = parse_args()
    QCoreApplication.setOrganizationName("ReasonNotes")
    QCoreApplication.setOrganizationDomain("reasonnotes.local")
    QCoreApplication.setApplicationName("ReasonNotesConceptCRender")
    application = QGuiApplication([sys.argv[0], "-platform", "offscreen"])

    with TemporaryDirectory(prefix="reasonnotes-concept-c-") as temporary:
        temporary_root = Path(temporary)
        QSettings.setPath(
            QSettings.NativeFormat,
            QSettings.UserScope,
            str(temporary_root / "settings"),
        )
        repository = Repository.open(
            temporary_root / "notes",
            local_device="linux",
            target_device="android",
        )
        note_ids = iter(UUID(int=index) for index in range(1, len(SAMPLE_NOTES) + 1))
        service = NoteService(
            repository,
            origin_device="linux",
            clock=lambda: datetime(2026, 10, 14, 20, 0, tzinfo=timezone.utc),
            note_id_factory=lambda: next(note_ids),
        )
        presenter = NotePresenter(service)
        for body in SAMPLE_NOTES:
            presenter.create_note()
            presenter.edit_draft(body)
            presenter.save_active()

        bridge = QtNotePresenter(presenter)
        engine = QQmlApplicationEngine()
        warnings = []
        engine.warnings.connect(lambda values: warnings.extend(values))
        engine.rootContext().setContextProperty("presenter", bridge)
        engine.rootContext().setContextProperty("smokeTest", False)
        engine.load(QUrl.fromLocalFile(str(Path(__file__).with_name("qml") / "Main.qml")))
        if not engine.rootObjects():
            return 1

        window = engine.rootObjects()[0]
        window.setWidth(options.width)
        window.setHeight(options.height)
        for _ in range(8):
            application.processEvents()

        if options.view in ("sync", "sync-selected"):
            bridge.openSyncDialog()
            if options.view == "sync-selected":
                selected_mode = window.findChild(QObject, "selectedSyncMode")
                if selected_mode is None:
                    return 4
                selected_mode.setProperty("checked", True)
            sync_dialog = window.findChild(QObject, "syncDialog")
            if sync_dialog is None or not QMetaObject.invokeMethod(sync_dialog, "open"):
                return 5
            for _ in range(8):
                application.processEvents()

        options.output.parent.mkdir(parents=True, exist_ok=True)
        if warnings:
            for warning in warnings:
                print(warning.toString(), file=sys.stderr)
            return 2
        image = application.primaryScreen().grabWindow(window.winId())
        if image.isNull() or not image.save(str(options.output)):
            return 3
        window.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
