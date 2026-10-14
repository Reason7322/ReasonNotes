import os
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event, get_ident
import time
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QCoreApplication, QSettings, QUrl
from PySide6.QtGui import QGuiApplication
from PySide6.QtQml import QQmlApplicationEngine

from reasonnotes_app.presenter import NotePresenter
from reasonnotes_app.qt_bridge import QtNotePresenter
from reasonnotes_app.service import NoteService
from reasonnotes_sync.repository import Repository
from reasonnotes_sync.sync import SyncReport


QML_MAIN = (
    Path(__file__).resolve().parents[1]
    / "linux-app"
    / "qml"
    / "Main.qml"
)


class QmlSmokeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        QCoreApplication.setOrganizationName("ReasonNotes")
        QCoreApplication.setOrganizationDomain("reasonnotes.local")
        QCoreApplication.setApplicationName("ReasonNotesQmlSmoke")
        cls.application = QGuiApplication.instance() or QGuiApplication(
            ["reasonnotes-qml-smoke", "-platform", "offscreen"]
        )

    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        QSettings.setPath(
            QSettings.NativeFormat,
            QSettings.UserScope,
            str(root / "settings"),
        )
        repository = Repository.open(
            root,
            local_device="linux",
            target_device="android",
        )
        presenter = NotePresenter(NoteService(repository, origin_device="linux"))
        self.bridge = QtNotePresenter(presenter)

    def test_real_component_tree_loads_offscreen_without_warnings(self):
        self.assertTrue(self.bridge.createNote())
        self.bridge.editDraft("# Rendered delegate\nPreview")
        self.assertTrue(self.bridge.saveNow())
        engine = QQmlApplicationEngine()
        warnings = []
        engine.warnings.connect(lambda values: warnings.extend(values))
        engine.rootContext().setContextProperty("presenter", self.bridge)
        engine.rootContext().setContextProperty("smokeTest", True)

        engine.load(QUrl.fromLocalFile(str(QML_MAIN)))
        QCoreApplication.processEvents()

        self.assertEqual(
            [],
            [warning.toString() for warning in warnings],
        )
        self.assertEqual(1, len(engine.rootObjects()))
        root = engine.rootObjects()[0]
        self.assertEqual(1200, root.property("width"))
        self.assertEqual(800, root.property("height"))
        self.assertFalse(root.property("visible"))
        self.assertIsNotNone(root.findChild(object, "sidebar"))
        self.assertIsNotNone(root.findChild(object, "editorPane"))
        self.assertIsNotNone(root.findChild(object, "syncDialog"))
        self.assertTrue(root.findChild(object, "deleteNoteButton").property("enabled"))

        selected_mode = root.findChild(object, "selectedSyncMode")
        self.assertIsNotNone(selected_mode)
        selected_mode.setProperty("checked", True)
        QCoreApplication.processEvents()
        self.assertEqual(
            [],
            [warning.toString() for warning in warnings],
        )
        self.assertEqual(1, self.bridge.syncModel.rowCount())
        self.assertFalse(
            root.findChild(object, "startSyncButton").property("enabled")
        )
        note_id = self.bridge.notesModel.row(0)["noteId"]
        self.bridge.setSyncSelected(note_id, True)
        QCoreApplication.processEvents()
        self.assertTrue(
            root.findChild(object, "startSyncButton").property("enabled")
        )

    def test_editor_is_read_only_until_a_note_exists(self):
        engine = QQmlApplicationEngine()
        engine.rootContext().setContextProperty("presenter", self.bridge)
        engine.rootContext().setContextProperty("smokeTest", True)
        engine.load(QUrl.fromLocalFile(str(QML_MAIN)))
        QCoreApplication.processEvents()
        root = engine.rootObjects()[0]
        editor = root.findChild(object, "markdownEditor")

        self.assertTrue(editor.property("readOnly"))

        self.assertTrue(self.bridge.createNote())
        QCoreApplication.processEvents()
        self.assertFalse(editor.property("readOnly"))

    def test_long_editor_content_has_a_scrollable_viewport(self):
        self.assertTrue(self.bridge.createNote())
        self.bridge.editDraft("\n".join(f"line {index}" for index in range(200)))
        self.assertTrue(self.bridge.saveNow())
        engine = QQmlApplicationEngine()
        engine.rootContext().setContextProperty("presenter", self.bridge)
        engine.rootContext().setContextProperty("smokeTest", True)
        engine.load(QUrl.fromLocalFile(str(QML_MAIN)))
        QCoreApplication.processEvents()
        root = engine.rootObjects()[0]
        scroll = root.findChild(object, "editorScrollView")

        self.assertIsNotNone(scroll)
        self.assertGreater(
            scroll.property("contentHeight"),
            scroll.property("height"),
        )

    def test_bridge_updates_models_and_persists_only_after_save(self):
        self.assertTrue(self.bridge.createNote())
        self.assertEqual(1, self.bridge.notesModel.rowCount())

        self.bridge.editDraft("# QML note\nBody")
        self.assertEqual("Dirty", self.bridge.saveState)
        self.assertTrue(self.bridge.saveNow())

        self.assertEqual("Saved", self.bridge.saveState)
        self.assertEqual("# QML note\nBody", self.bridge.currentBody)
        self.assertEqual("QML note", self.bridge.notesModel.row(0)["title"])

    def test_sync_catalogue_is_independent_of_sidebar_search(self):
        self.assertTrue(self.bridge.createNote())
        self.bridge.editDraft("# Alpha")
        self.assertTrue(self.bridge.saveNow())
        self.assertTrue(self.bridge.createNote())
        self.bridge.editDraft("# Beta")
        self.assertTrue(self.bridge.saveNow())

        self.bridge.search("Alpha")
        self.assertEqual(1, self.bridge.notesModel.rowCount())
        self.bridge.openSyncDialog()
        self.bridge.searchSyncNotes("Beta")

        self.assertEqual(1, self.bridge.syncModel.rowCount())
        self.assertEqual("Beta", self.bridge.syncModel.row(0)["title"])

    def test_sync_catalogue_includes_deleted_note_tombstone(self):
        self.assertTrue(self.bridge.createNote())
        note_id = self.bridge.activeNoteId
        self.bridge.editDraft("# Shared")
        self.assertTrue(self.bridge.saveNow())
        self.assertTrue(self.bridge.deleteCurrent())

        self.bridge.openSyncDialog()

        rows = [
            self.bridge.syncModel.row(index)
            for index in range(self.bridge.syncModel.rowCount())
        ]
        deleted = next(row for row in rows if row["noteId"] == note_id)
        self.assertEqual("Deleted note", deleted["title"])
        self.assertEqual("Pending deletion", deleted["preview"])

    def test_sync_runs_off_gui_thread_and_reports_completion(self):
        started = Event()
        release = Event()
        self.addCleanup(release.set)
        worker_threads = []

        def runner(_selection):
            worker_threads.append(get_ident())
            started.set()
            self.assertTrue(release.wait(2))
            return SyncReport((), (), (), (), (), (), (), ())

        root = Path(self.temporary.name) / "async-repository"
        repository = Repository.open(
            root,
            local_device="linux",
            target_device="android",
        )
        presenter = NotePresenter(
            NoteService(repository, origin_device="linux"),
            runner,
        )
        completion_threads = []
        complete_sync = presenter.complete_sync

        def record_completion(report):
            completion_threads.append(get_ident())
            complete_sync(report)

        presenter.complete_sync = record_completion
        bridge = QtNotePresenter(presenter)
        gui_thread = get_ident()

        self.assertTrue(bridge.syncAll())
        self.assertTrue(started.wait(2))
        self.assertNotEqual(gui_thread, worker_threads[0])
        self.assertEqual("Running", bridge.syncState)
        self.assertFalse(bridge.syncAll())
        self.assertFalse(bridge.closeRequested())
        release.set()

        deadline = time.monotonic() + 2
        while bridge.syncState == "Running" and time.monotonic() < deadline:
            QCoreApplication.processEvents()
            time.sleep(0.01)

        self.assertEqual("Completed", bridge.syncState)
        self.assertEqual([gui_thread], completion_threads)
        self.assertTrue(bridge.closeRequested())


if __name__ == "__main__":
    unittest.main()
