import os
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event, get_ident
import time
import unittest
from unittest.mock import patch
from uuid import UUID

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QCoreApplication, QMetaObject, QSettings, QUrl
from PySide6.QtGui import QGuiApplication
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuick import QQuickItem

from reasonnotes_app.liquid_glass_palette import LiquidGlassPalette
from reasonnotes_app.wayland_blur import WaylandBlurController
from reasonnotes_app.preferences import AppPreferences
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
        self.preferences = AppPreferences()
        self.blur_state = WaylandBlurController(self.preferences)
        self.liquid_palette = LiquidGlassPalette()

    def load_qml(self):
        engine = QQmlApplicationEngine()
        warnings = []
        engine.warnings.connect(lambda values: warnings.extend(values))
        engine.rootContext().setContextProperty("glassPalette", self.liquid_palette)
        engine.rootContext().setContextProperty("blurState", self.blur_state)
        engine.rootContext().setContextProperty("appPreferences", self.preferences)
        engine.rootContext().setContextProperty("presenter", self.bridge)
        engine.rootContext().setContextProperty("smokeTest", True)
        engine.load(QUrl.fromLocalFile(str(QML_MAIN)))
        QCoreApplication.processEvents()
        self.assertEqual(1, len(engine.rootObjects()))
        return engine, engine.rootObjects()[0], warnings

    def test_foreground_receiving_does_not_block_first_window_and_refreshes_when_ready(self):
        from threading import Event
        from time import monotonic
        from datetime import datetime, timezone
        from uuid import UUID
        from PySide6.QtTest import QTest
        from reasonnotes_sync.envelope import Revision

        repo = self.bridge.presenter.repository
        remote_id = UUID("cbbb1111-1111-4111-8111-111111111111")
        incoming = Revision.create(
            remote_id, (), "android",
            datetime(2026, 10, 16, 15, tzinfo=timezone.utc),
            False, "# Arriving after window opens\n\nRemote body",
        )
        entered = Event()
        release = Event()
        completed = []
        self.bridge.incomingScanFinished.connect(lambda result: completed.append(result))

        def slow_receive():
            entered.set()
            if not release.wait(timeout=5):
                raise TimeoutError("test did not release incoming scan")
            return repo.import_bytes(incoming.serialize(), "incoming.md")

        try:
            start = monotonic()
            self.assertTrue(self.bridge.start_incoming_scan(slow_receive))
            self.assertFalse(self.bridge.start_incoming_scan(slow_receive))
            self.assertTrue(entered.wait(timeout=2))
            engine, root, warnings = self.load_qml()
            self.assertEqual(1, len(engine.rootObjects()))
            QCoreApplication.processEvents()
            self.assertLess(monotonic() - start, 3)
            self.assertNotIn(
                str(remote_id),
                [self.bridge.notesModel.row(i)["noteId"]
                 for i in range(self.bridge.notesModel.rowCount())],
            )
        finally:
            release.set()

        deadline = monotonic() + 5
        while not completed and monotonic() < deadline:
            QCoreApplication.processEvents()
            QTest.qWait(10)
        self.assertEqual(1, len(completed))
        self.assertEqual("imported", completed[0].kind)
        self.assertIn(
            str(remote_id),
            [self.bridge.notesModel.row(i)["noteId"]
             for i in range(self.bridge.notesModel.rowCount())],
        )

    def test_f5_imports_new_receipt_files_after_window_is_open_without_sending_notes(self):
        from datetime import datetime, timezone
        from PySide6.QtCore import Qt
        from PySide6.QtTest import QTest
        from reasonnotes_sync.envelope import Revision
        from reasonnotes_sync.sync import scan_inbox

        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.requestActivate()
        QCoreApplication.processEvents()
        remote_id = UUID("97b16c77-c6d9-49c2-bae5-48647458f183")
        revision = Revision.create(
            remote_id, (), "android",
            datetime(2026, 10, 16, 18, tzinfo=timezone.utc),
            False, "# Received via KDE Connect\n\nA real note",
        )
        received = Path(self.temporary.name) / "received-later"
        received.mkdir()
        (received / f"{revision.revision_id}.md").write_bytes(revision.serialize())
        scans = []
        reports = []

        def scan():
            scans.append(True)
            return scan_inbox(
                self.bridge.presenter.repository,
                received,
                "linux",
            )

        self.bridge.configure_incoming_scan(scan)
        self.bridge.incomingScanFinished.connect(lambda report: reports.append(report))
        self.assertEqual(0, self.bridge.notesModel.rowCount())
        QTest.keyClick(root, Qt.Key_F5)
        deadline = time.monotonic() + 5
        while not reports and time.monotonic() < deadline:
            QCoreApplication.processEvents()
            QTest.qWait(10)
        self.assertEqual(1, len(scans))
        self.assertEqual(1, len(reports))
        self.assertEqual((revision.revision_id,), reports[0].imported)
        self.assertEqual(1, self.bridge.notesModel.rowCount())
        self.assertEqual(str(remote_id), self.bridge.notesModel.row(0)["noteId"])
        self.assertIn("Received 1 revision", self.bridge.incomingRefreshMessage)
        self.assertTrue(root.findChild(object, "incomingRefreshNotice").property("visible"))
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_f5_pressed_during_startup_scan_queues_one_followup_scan(self):
        from datetime import datetime, timezone
        from PySide6.QtCore import Qt
        from PySide6.QtTest import QTest
        from reasonnotes_sync.envelope import Revision
        from reasonnotes_sync.repository import ImportOutcome
        from reasonnotes_sync.sync import ScanReport

        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.requestActivate()
        QCoreApplication.processEvents()
        entered = Event()
        release = Event()
        calls = []
        remote_id = UUID("af737c25-4414-413e-9fc2-d6dcc0f9a9c8")
        revision = Revision.create(
            remote_id, (), "android",
            datetime(2026, 10, 16, 19, tzinfo=timezone.utc),
            False, "# Arrived after first scan",
        )

        def scanner():
            calls.append(True)
            if len(calls) == 1:
                entered.set()
                if not release.wait(5):
                    raise RuntimeError("test scan not released")
                return ImportOutcome("duplicate", None, "nothing new yet")
            imported = self.bridge.presenter.repository.import_bytes(
                revision.serialize(), "new-from-android.md"
            )
            return ScanReport(
                (imported.revision_id,), (), (), (), (), (), (), ()
            )

        self.bridge.configure_incoming_scan(scanner)
        completed = []
        self.bridge.incomingScanFinished.connect(lambda result: completed.append(result))
        self.assertTrue(self.bridge.start_incoming_scan(scanner))
        try:
            self.assertTrue(entered.wait(2))
            QTest.keyClick(root, Qt.Key_F5)
            QTest.keyClick(root, Qt.Key_F5)
            QCoreApplication.processEvents()
            self.assertEqual("Refresh queued…", self.bridge.incomingRefreshMessage)
            self.assertEqual(1, len(calls))
        finally:
            release.set()
        deadline = time.monotonic() + 5
        while len(completed) < 2 and time.monotonic() < deadline:
            QCoreApplication.processEvents()
            QTest.qWait(10)
        self.assertEqual(2, len(calls))
        self.assertEqual(2, len(completed))
        self.assertEqual(str(remote_id), self.bridge.notesModel.row(0)["noteId"])
        self.assertIn("Received 1 revision", self.bridge.incomingRefreshMessage)
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_f5_refresh_preserves_active_unsaved_draft_and_does_not_upload(self):
        from PySide6.QtCore import Qt
        from PySide6.QtTest import QTest
        from reasonnotes_sync.repository import ImportOutcome

        self.assertTrue(self.bridge.createNote())
        self.assertTrue(self.bridge.editTitle("Unsent title"))
        self.assertTrue(self.bridge.editContent("Unsent draft should stay"))
        original_id = self.bridge.activeNoteId
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.requestActivate()
        from reasonnotes_sync.sync import ScanReport
        self.bridge.configure_incoming_scan(
            lambda: ScanReport((), (), (), (), (), (), (), ())
        )
        complete = []
        self.bridge.incomingScanFinished.connect(lambda report: complete.append(report))
        # Keyboard delivery is validated in test_f5_imports_new...;
        # this independently checks that the refresh action preserves edits.
        self.assertTrue(self.bridge.refreshIncoming())
        deadline = time.monotonic() + 5
        while not complete and time.monotonic() < deadline:
            QCoreApplication.processEvents()
            QTest.qWait(10)
        self.assertEqual(1, len(complete))
        self.assertEqual(original_id, self.bridge.activeNoteId)
        self.assertIn("Unsent draft should stay", self.bridge.currentBody)
        self.assertTrue(self.bridge.presenter.dirty)
        self.assertEqual("Notes are up to date", self.bridge.incomingRefreshMessage)
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_settings_page_changes_theme_language_and_keeps_note_content(self):
        from PySide6.QtCore import QObject
        from PySide6.QtTest import QTest
        from PySide6.QtCore import QPoint, Qt

        self.assertTrue(self.bridge.createNote())
        self.assertTrue(self.bridge.editTitle("User text remains untouched"))
        self.assertTrue(self.bridge.editContent("Markdown *still works*"))
        self.assertTrue(self.bridge.saveNow())

        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.setProperty("width", 1200)
        root.setProperty("height", 800)
        QCoreApplication.processEvents()

        theme = root.findChild(QObject, "theme")
        i18n = root.findChild(QObject, "i18n")
        pane = root.findChild(QObject, "settingsPane")
        settings = root.findChild(QObject, "settingsButton")
        self.assertIsNotNone(theme)
        self.assertIsNotNone(i18n)
        self.assertIsNotNone(pane)
        self.assertIsNotNone(settings)
        self.assertEqual("#15171c", theme.property("mainBackground").name())
        self.assertEqual("en", i18n.property("language"))
        self.assertFalse(pane.property("visible"))

        # Opening Settings is a real sidebar action and hides only the editor.
        QTest.mouseClick(root, Qt.LeftButton, pos=QPoint(
            int(settings.mapToItem(None, settings.width() / 2, settings.height() / 2).x()),
            int(settings.mapToItem(None, settings.width() / 2, settings.height() / 2).y()),
        ))
        for _ in range(5):
            QCoreApplication.processEvents()
        self.assertTrue(pane.property("visible"))
        self.assertFalse(root.findChild(QObject, "editorPane").property("visible"))

        light = root.findChild(QObject, "lightThemeButton")
        self.assertIsNotNone(light)
        light_pos = light.mapToItem(None, light.width() / 2, light.height() / 2)
        QTest.mouseClick(root, Qt.LeftButton, pos=QPoint(int(light_pos.x()), int(light_pos.y())))
        QCoreApplication.processEvents()
        self.assertEqual("light", self.preferences.themeMode)
        self.assertEqual("#fafafc", theme.property("mainBackground").name())
        self.assertEqual("#1d2430", theme.property("textPrimary").name())
        self.assertTrue(root.findChild(QObject, "lightThemeButton").property("checked"))

        polish = root.findChild(QObject, "polishLanguageButton")
        self.assertIsNotNone(polish)
        polish_pos = polish.mapToItem(None, polish.width() / 2, polish.height() / 2)
        QTest.mouseClick(root, Qt.LeftButton, pos=QPoint(int(polish_pos.x()), int(polish_pos.y())))
        QCoreApplication.processEvents()
        self.assertEqual("pl", self.preferences.language)
        self.assertEqual("pl", i18n.property("language"))
        self.assertEqual("Polski", root.findChild(QObject, "polishLanguageButton").property("text"))
        self.assertTrue(root.findChild(QObject, "polishLanguageButton").property("checked"))
        labels = [obj.property("text") for obj in root.findChildren(QObject)
                  if isinstance(obj.property("text"), str)]
        self.assertIn("Nowa notatka", labels)
        self.assertIn("Ustawienia", labels)
        self.assertIn("Wygląd", labels)

        dark = root.findChild(QObject, "darkThemeButton")
        dark_pos = dark.mapToItem(None, dark.width() / 2, dark.height() / 2)
        QTest.mouseClick(root, Qt.LeftButton, pos=QPoint(int(dark_pos.x()), int(dark_pos.y())))
        english = root.findChild(QObject, "englishLanguageButton")
        english_pos = english.mapToItem(None, english.width() / 2, english.height() / 2)
        QTest.mouseClick(root, Qt.LeftButton, pos=QPoint(int(english_pos.x()), int(english_pos.y())))
        QCoreApplication.processEvents()
        self.assertEqual("dark", self.preferences.themeMode)
        self.assertEqual("en", self.preferences.language)
        self.assertEqual("#15171c", theme.property("mainBackground").name())
        self.assertIn("Settings", [obj.property("text") for obj in root.findChildren(QObject)
                                   if isinstance(obj.property("text"), str)])
        self.assertEqual("Markdown *still works*", self.bridge.currentContent)
        self.assertEqual("User text remains untouched", self.bridge.currentTitle)
        self.assertEqual([], [w.toString() for w in warnings])
        del engine

    def test_liquid_glass_theme_uses_live_kvantum_colors_and_preserves_notes(self):
        from PySide6.QtCore import QObject, QPoint, Qt
        from PySide6.QtTest import QTest

        self.assertTrue(self.bridge.createNote())
        self.assertTrue(self.bridge.editTitle("A real note"))
        self.assertTrue(self.bridge.editContent("Never recolor or change my text"))
        self.assertTrue(self.bridge.saveNow())
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.setProperty("width", 1200)
        root.setProperty("height", 800)
        QCoreApplication.processEvents()

        theme = root.findChild(QObject, "theme")
        settings = root.findChild(QObject, "settingsButton")
        pos = settings.mapToItem(None, settings.width() / 2, settings.height() / 2)
        QTest.mouseClick(root, Qt.LeftButton, pos=QPoint(int(pos.x()), int(pos.y())))
        QCoreApplication.processEvents()
        button = root.findChild(QObject, "glassThemeButton")
        self.assertIsNotNone(button)
        pos = button.mapToItem(None, button.width() / 2, button.height() / 2)
        QTest.mouseClick(root, Qt.LeftButton, pos=QPoint(int(pos.x()), int(pos.y())))
        for _ in range(6):
            QCoreApplication.processEvents()

        self.assertEqual("glass", self.preferences.themeMode)
        self.assertTrue(theme.property("isGlass"))
        self.assertEqual(
            self.liquid_palette.accentColor.name(),
            theme.property("accent").name(),
        )
        self.assertEqual(
            self.liquid_palette.textColor.name(),
            theme.property("textPrimary").name(),
        )
        # Simulate Noctalia regenerating the Kvantum material while the
        # desktop window is already open: QML must change reactively.
        test_palette = Path(self.temporary.name) / "Kvantum" / "LiquidGlass"
        test_palette.mkdir(parents=True)
        generated = test_palette / "LiquidGlass.kvconfig"
        generated.write_text(
            "[GeneralColors]\n"
            "window.color=#1c2021\n"
            "base.color=#2a2f32B8\n"
            "alt.base.color=#171a1c\n"
            "button.color=#2f3537\n"
            "light.color=#383f42\n"
            "mid.color=#636b6e\n"
            "highlight.color=#679dbc\n"
            "text.color=#f2f2f3\n"
            "disabled.text.color=#656a6d\n"
            "highlight.text.color=#1c2022\n",
            encoding="utf-8",
        )
        self.liquid_palette.path = generated
        self.liquid_palette.reload()
        QCoreApplication.processEvents()
        self.assertEqual("#679dbc", theme.property("accent").name())
        # Corrected material paints the root opaque. The previously
        # transparent root exposed sharp wallpaper bands on Hyprland.
        self.assertEqual(255, theme.property("mainBackground").alpha())
        self.assertEqual(255, root.property("color").alpha())
        self.assertEqual(255, theme.property("sidebar").alpha())
        self.assertEqual(255, theme.property("surface").alpha())
        # Simulate the protocol capability notification. A successful native
        # blur request unlocks translucent glass; turning it off restores
        # a solid, readable Qt window without changing note contents.
        self.blur_state._set_active(True)
        QCoreApplication.processEvents()
        self.assertLess(theme.property("mainBackground").alpha(), 255)
        self.assertLess(theme.property("sidebar").alpha(), 255)
        self.assertEqual(0, root.property("color").alpha())
        self.blur_state._set_active(False)
        QCoreApplication.processEvents()
        self.assertEqual(255, root.property("color").alpha())
        edges = root.findChildren(QObject, "glassEdgeReflection")
        self.assertEqual(3, len(edges))
        self.assertTrue(all(edge.property("height") == 28 for edge in edges))
        self.assertTrue(button.property("checked"))
        self.assertAlmostEqual(
            button.property("width"),
            root.findChild(QObject, "darkThemeButton").property("width"),
            delta=3,
        )
        self.assertAlmostEqual(
            button.property("width"),
            root.findChild(QObject, "lightThemeButton").property("width"),
            delta=3,
        )
        self.assertEqual("A real note", self.bridge.currentTitle)
        self.assertEqual("Never recolor or change my text", self.bridge.currentContent)

        # Existing themes remain identical and are selectable without restart.
        self.preferences.setThemeMode("dark")
        QCoreApplication.processEvents()
        self.assertEqual("#15171c", theme.property("mainBackground").name())
        self.preferences.setThemeMode("light")
        QCoreApplication.processEvents()
        self.assertEqual("#fafafc", theme.property("mainBackground").name())
        self.assertEqual([], [w.toString() for w in warnings])
        del engine

    def test_editor_omits_markdown_format_label_and_empty_body_placeholder(self):
        # Keep note editing and delivery status functional; only hide the two labels.
        self.assertTrue(self.bridge.createNote())
        self.assertTrue(self.bridge.editTitle("Desktop test"))
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        QCoreApplication.processEvents()

        editor = root.findChild(object, "markdownEditor")
        status_bar = root.findChild(object, "editorStatusBar")
        title = root.findChild(object, "currentNoteHeading")
        self.assertIsNotNone(editor)
        self.assertIsNotNone(status_bar)
        self.assertIsNotNone(title)
        self.assertEqual("", editor.property("placeholderText"))
        self.assertEqual("Desktop test", title.property("text"))
        self.assertFalse(any(
            obj.property("text") == "Markdown"
            for obj in status_bar.findChildren(object)
        ))
        self.assertTrue(any(
            str(obj.property("text")).startswith("Local:")
            for obj in status_bar.findChildren(object)
        ))
        self.assertTrue(any(
            str(obj.property("text")).startswith("Phone:")
            for obj in status_bar.findChildren(object)
        ))
        self.assertTrue(self.bridge.editContent("Regular note editing still works"))
        self.assertTrue(self.bridge.saveNow())
        self.assertEqual("Saved", self.bridge.saveState)
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_sidebar_action_icons_are_uniform_vector_shapes(self):
        self.assertTrue(self.bridge.createNote())
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        QCoreApplication.processEvents()

        icons = {
            "saveActionIcon": "save",
            "syncActionIcon": "sync",
            "deleteActionIcon": "delete",
        }
        for object_name, kind in icons.items():
            with self.subTest(icon=object_name):
                icon = root.findChild(object, object_name)
                self.assertIsNotNone(icon)
                self.assertEqual(kind, icon.property("kind"))
                self.assertEqual(24, icon.property("width"))
                self.assertEqual(24, icon.property("height"))
                self.assertTrue(icon.property("assetReady"))
                source = icon.property("assetSource")
                source_text = source.toString() if hasattr(source, "toString") else str(source)
                self.assertTrue(source_text.endswith(
                    {"save": "/save.svg", "sync": "/refresh-cw.svg",
                     "delete": "/trash.svg"}[kind]
                ), source_text)
                self.assertEqual("normal", icon.property("visualState"))

        # Save and Delete are enabled with a selected note, and all icons
        # still share the same source-independent vector geometry.
        self.assertTrue(root.findChild(object, "saveButton").property("enabled"))
        self.assertTrue(root.findChild(object, "deleteNoteButton").property("enabled"))
        self.assertEqual([], [warning.toString() for warning in warnings])

    def test_sidebar_note_icon_is_a_centered_svg_not_a_font_glyph(self):
        from PySide6.QtSvg import QSvgRenderer

        icon_dir = QML_MAIN.parent / "icons" / "lucide" / "themed"
        for name in ("notebook-text.svg", "notebook-text-danger.svg"):
            with self.subTest(asset=name):
                svg = QSvgRenderer(str(icon_dir / name))
                self.assertTrue(svg.isValid())
                self.assertEqual(24, svg.defaultSize().width())
                self.assertEqual(24, svg.defaultSize().height())

        source = (QML_MAIN.parent / "Sidebar.qml").read_text(encoding="utf-8")
        self.assertNotIn('text: "▤"', source)
        self.assertIn('objectName: "noteIconSlot"', source)
        self.assertIn('objectName: "noteRowIcon"', source)
        self.assertIn('Layout.preferredWidth: 24', source)
        self.assertIn('Layout.preferredHeight: 24', source)
        self.assertIn('anchors.centerIn: parent', source)
        self.assertIn('Layout.alignment: Qt.AlignVCenter', source)

        self.assertTrue(self.bridge.createNote())
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        for _ in range(5):
            QCoreApplication.processEvents()
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_note_row_uses_action_button_insets_and_shortcuts_stay_active(self):
        from PySide6.QtCore import Qt
        from PySide6.QtTest import QTest

        sidebar_source = (QML_MAIN.parent / "Sidebar.qml").read_text(
            encoding="utf-8"
        )
        main_source = QML_MAIN.read_text(encoding="utf-8")
        self.assertIn("leftPadding: saveButton.leftPadding", sidebar_source)
        self.assertIn("rightPadding: saveButton.rightPadding", sidebar_source)
        self.assertIn("Layout.preferredWidth: 24", sidebar_source)
        self.assertIn("Layout.alignment: Qt.AlignVCenter", sidebar_source)
        self.assertNotIn('qsTr("Ctrl+N")', sidebar_source)
        self.assertNotIn('qsTr("Ctrl+S")', sidebar_source)
        self.assertIn("sequences: [StandardKey.New]", main_source)
        self.assertIn("sequences: [StandardKey.Save]", main_source)

        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.requestActivate()
        QCoreApplication.processEvents()
        save = root.findChild(object, "saveButton")
        self.assertIsNotNone(save)
        self.assertEqual(4, save.property("leftPadding"))

        # Confirm the hidden labels did not disable the actual keyboard actions.
        self.assertEqual(0, self.bridge.notesModel.rowCount())
        QTest.keyClick(root, Qt.Key_N, Qt.ControlModifier)
        QCoreApplication.processEvents()
        self.assertEqual(1, self.bridge.notesModel.rowCount())
        self.assertTrue(self.bridge.editContent("Shortcut still works"))
        QTest.keyClick(root, Qt.Key_S, Qt.ControlModifier)
        QCoreApplication.processEvents()
        self.assertEqual("Saved", self.bridge.saveState)
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_shift_click_toggles_nonadjacent_notes_and_one_dialog_deletes_exact_targets(self):
        from PySide6.QtCore import Qt, QPoint
        from PySide6.QtTest import QTest

        ids = []
        for title in ("Alpha", "Beta", "Gamma", "Delta"):
            self.assertTrue(self.bridge.createNote())
            ids.append(self.bridge.activeNoteId)
            self.assertTrue(self.bridge.editTitle(title))
            self.assertTrue(self.bridge.saveNow())
        repo = self.bridge.presenter.repository
        original_active = self.bridge.activeNoteId
        root_engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.requestActivate()
        QCoreApplication.processEvents()

        def click_note(note_id, *, shift):
            delegates = root.findChild(object, "notesList").property("contentItem").childItems()
            matching = [row for row in delegates if row.objectName() == "noteDelegate"
                        and row.property("noteId") == note_id]
            self.assertEqual(1, len(matching), (note_id, len(delegates)))
            row = matching[0]
            point = row.mapToItem(None, row.width() * 0.60, row.height() / 2)
            QTest.mouseClick(root, Qt.LeftButton,
                Qt.ShiftModifier if shift else Qt.NoModifier,
                QPoint(round(point.x()), round(point.y())))
            QCoreApplication.processEvents()

        click_note(ids[0], shift=True)
        click_note(ids[2], shift=True)
        self.assertEqual(2, self.bridge.bulkSelectionCount)
        self.assertEqual({ids[0], ids[2]}, set(self.bridge.bulkSelectedNoteIds))
        self.assertEqual(original_active, self.bridge.activeNoteId)
        # A second Shift-click toggles the individual note off and on.
        click_note(ids[0], shift=True)
        self.assertEqual({ids[2]}, set(self.bridge.bulkSelectedNoteIds))
        click_note(ids[0], shift=True)
        self.assertEqual({ids[0], ids[2]}, set(self.bridge.bulkSelectedNoteIds))
        self.assertTrue(root.findChild(object, "bulkSelectionBar").property("visible"))
        self.assertTrue(root.findChild(object, "deleteNoteButton").property("enabled"))
        self.assertEqual(2, self.bridge.prepareDelete())
        self.assertEqual(2, self.bridge.pendingDeleteCount)
        # Cancel must preserve all notes and selection.
        self.bridge.cancelDelete()
        self.assertEqual(0, self.bridge.pendingDeleteCount)
        self.assertEqual(4, len(self.bridge.presenter.service.list_notes()))
        self.assertEqual(2, self.bridge.bulkSelectionCount)
        # Click the actual Delete button, inspect count, then confirm.
        button = root.findChild(object, "deleteNoteButton")
        position = button.mapToItem(None, button.width() / 2, button.height() / 2)
        QTest.mouseClick(root, Qt.LeftButton, Qt.NoModifier,
            QPoint(round(position.x()), round(position.y())))
        QCoreApplication.processEvents()
        dialog = root.findChild(object, "deleteDialog")
        self.assertTrue(dialog.property("visible"))
        self.assertEqual(2, self.bridge.pendingDeleteCount)
        self.assertIn("2 notes", dialog.property("title"))
        dialog.accept()
        QCoreApplication.processEvents()
        self.assertEqual(0, self.bridge.pendingDeleteCount)
        self.assertEqual(0, self.bridge.bulkSelectionCount)
        self.assertEqual({ids[1], ids[3]}, {
            str(row.note_id) for row in self.bridge.presenter.service.list_notes()
        })
        for deleted_id in (ids[0], ids[2]):
            self.assertTrue(repo.is_purged(UUID(deleted_id)))
            self.assertFalse((repo.history_dir / deleted_id).exists())
        for kept_id in (ids[1], ids[3]):
            self.assertFalse(repo.is_purged(UUID(kept_id)))
            self.assertTrue((repo.history_dir / kept_id).is_dir())
        self.assertEqual(original_active, self.bridge.activeNoteId)
        self.assertEqual([], [warning.toString() for warning in warnings])
        del root_engine

    def test_normal_click_opens_note_and_clears_bulk_selection(self):
        from PySide6.QtCore import Qt, QPoint
        from PySide6.QtTest import QTest

        ids = []
        for name in ("First", "Second", "Third"):
            self.assertTrue(self.bridge.createNote())
            ids.append(self.bridge.activeNoteId)
            self.assertTrue(self.bridge.editTitle(name))
            self.assertTrue(self.bridge.saveNow())
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.requestActivate()
        QCoreApplication.processEvents()
        def click(note_id, modifier):
            row, = [x for x in root.findChild(object, "notesList").property(
                    "contentItem").childItems()
                    if x.objectName() == "noteDelegate"
                    and x.property("noteId") == note_id]
            point = row.mapToItem(None, row.width() / 2, row.height() / 2)
            QTest.mouseClick(root, Qt.LeftButton, modifier,
                QPoint(round(point.x()), round(point.y())))
            QCoreApplication.processEvents()
        click(ids[0], Qt.ShiftModifier)
        click(ids[1], Qt.ShiftModifier)
        self.assertEqual(2, self.bridge.bulkSelectionCount)
        click(ids[0], Qt.NoModifier)
        self.assertEqual(ids[0], self.bridge.activeNoteId)
        self.assertEqual(0, self.bridge.bulkSelectionCount)
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_bulk_delete_selection_survives_search_and_is_snapshotted_for_confirmation(self):
        ids = []
        for name in ("First", "Second", "Third"):
            self.assertTrue(self.bridge.createNote())
            ids.append(self.bridge.activeNoteId)
            self.assertTrue(self.bridge.editTitle(name))
            self.assertTrue(self.bridge.saveNow())
        self.assertTrue(self.bridge.toggleBulkNote(ids[0]))
        self.assertTrue(self.bridge.toggleBulkNote(ids[1]))
        self.bridge.search("Third")
        self.assertEqual(1, self.bridge.notesModel.rowCount())
        self.assertEqual(2, self.bridge.bulkSelectionCount)
        self.assertEqual(2, self.bridge.prepareDelete())
        # Search doesn't accidentally expand the frozen delete batch.
        self.assertTrue(self.bridge.toggleBulkNote(ids[2]))
        self.assertEqual(3, self.bridge.bulkSelectionCount)
        self.assertEqual(2, self.bridge.pendingDeleteCount)
        self.assertTrue(self.bridge.confirmDelete())
        repo = self.bridge.presenter.repository
        for name in (ids[0], ids[1]):
            self.assertTrue(repo.is_purged(UUID(name)))
        self.assertFalse(repo.is_purged(UUID(ids[2])))
        self.assertEqual({ids[2]}, set(self.bridge.bulkSelectedNoteIds))
        self.bridge.clearBulkSelection()
        self.assertEqual(0, self.bridge.bulkSelectionCount)

    def test_bulk_delete_preserves_unselected_dirty_note_and_stops_on_failure(self):
        ids = []
        for name in ("Alpha", "Beta", "Gamma"):
            self.assertTrue(self.bridge.createNote())
            ids.append(self.bridge.activeNoteId)
            self.assertTrue(self.bridge.editTitle(name))
            self.assertTrue(self.bridge.saveNow())
        self.assertTrue(self.bridge.editContent("Unsent personal draft"))
        self.assertTrue(self.bridge.presenter.dirty)
        for note_id in ids[:2]:
            self.assertTrue(self.bridge.toggleBulkNote(note_id))
        self.assertEqual(2, self.bridge.prepareDelete())
        original_delete = self.bridge.presenter.service.delete_note
        calls = []
        def fail_second(note_id):
            calls.append(str(note_id))
            if len(calls) == 2:
                raise OSError("simulated persistent disk failure")
            original_delete(note_id)
        with patch.object(self.bridge.presenter.service, "delete_note", side_effect=fail_second):
            self.assertFalse(self.bridge.confirmDelete())
        self.assertEqual(2, len(calls))
        repo = self.bridge.presenter.repository
        self.assertTrue(repo.is_purged(UUID(calls[0])))
        self.assertFalse(repo.is_purged(UUID(calls[1])))
        self.assertFalse(repo.is_purged(UUID(ids[2])))
        self.assertEqual(ids[2], self.bridge.activeNoteId)
        self.assertIn("Unsent personal draft", self.bridge.currentBody)
        self.assertTrue(self.bridge.presenter.dirty)
        self.assertIn("Deleted 1 of 2", self.bridge.saveError)
        self.assertEqual({calls[1]}, set(self.bridge.bulkSelectedNoteIds))

    def test_selected_dirty_note_is_purged_without_saving_draft(self):
        self.assertTrue(self.bridge.createNote())
        target = self.bridge.activeNoteId
        self.assertTrue(self.bridge.editTitle("Will be deleted"))
        self.assertTrue(self.bridge.saveNow())
        self.assertTrue(self.bridge.editContent("Unsent draft must not be saved"))
        repo = self.bridge.presenter.repository
        self.assertTrue(self.bridge.toggleBulkNote(target))
        self.assertEqual(1, self.bridge.prepareDelete())
        self.assertTrue(self.bridge.confirmDelete())
        self.assertTrue(repo.is_purged(UUID(target)))
        self.assertFalse((repo.history_dir / target).exists())
        self.assertEqual("", self.bridge.activeNoteId)
        self.assertEqual("", self.bridge.currentBody)
        self.assertEqual(0, self.bridge.bulkSelectionCount)
        self.assertEqual(0, len(self.bridge.presenter.service.list_notes()))

    def test_stale_selection_never_purges_an_extra_note(self):
        ids = []
        for title in ("Gone remotely", "Still here"):
            self.assertTrue(self.bridge.createNote())
            ids.append(self.bridge.activeNoteId)
            self.assertTrue(self.bridge.editTitle(title))
            self.assertTrue(self.bridge.saveNow())
        self.assertTrue(self.bridge.toggleBulkNote(ids[0]))
        self.assertTrue(self.bridge.toggleBulkNote(ids[1]))
        self.assertEqual(2, self.bridge.prepareDelete())
        repo = self.bridge.presenter.repository
        repo.purge_note(UUID(ids[0]))
        # Confirmation must fail closed; no extra notes are deleted.
        self.assertFalse(self.bridge.confirmDelete())
        self.assertTrue(repo.is_purged(UUID(ids[0])))
        self.assertFalse(repo.is_purged(UUID(ids[1])))
        self.assertEqual(1, len(self.bridge.presenter.service.list_notes()))
        self.assertIn("Selection changed", self.bridge.saveError)
        self.assertEqual({ids[1]}, set(self.bridge.bulkSelectedNoteIds))

    def test_interrupted_cleanup_after_fence_is_not_reported_as_success(self):
        self.assertTrue(self.bridge.createNote())
        target = self.bridge.activeNoteId
        self.assertTrue(self.bridge.editTitle("Fence-first"))
        self.assertTrue(self.bridge.saveNow())
        self.assertTrue(self.bridge.toggleBulkNote(target))
        self.assertEqual(1, self.bridge.prepareDelete())
        original = self.bridge.presenter.service.delete_note
        def interrupted(note_id):
            original(note_id)
            raise OSError("simulated failure after commit")
        with patch.object(self.bridge.presenter.service, "delete_note", side_effect=interrupted):
            self.assertFalse(self.bridge.confirmDelete())
        self.assertTrue(self.bridge.presenter.repository.is_purged(UUID(target)))
        self.assertEqual("", self.bridge.activeNoteId)
        self.assertEqual(0, self.bridge.bulkSelectionCount)
        self.assertIn("remaining deletion failed", self.bridge.saveError)

    def test_dialog_cancel_preserves_selected_notes_and_does_not_purge(self):
        from PySide6.QtCore import QPoint, Qt
        from PySide6.QtTest import QTest

        self.assertTrue(self.bridge.createNote())
        selected = self.bridge.activeNoteId
        self.assertTrue(self.bridge.editTitle("Keep me"))
        self.assertTrue(self.bridge.saveNow())
        self.assertTrue(self.bridge.toggleBulkNote(selected))
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.requestActivate()
        QCoreApplication.processEvents()
        button = root.findChild(object, "deleteNoteButton")
        point = button.mapToItem(None, button.width()/2, button.height()/2)
        QTest.mouseClick(root, Qt.LeftButton, Qt.NoModifier,
            QPoint(round(point.x()), round(point.y())))
        QCoreApplication.processEvents()
        dialog = root.findChild(object, "deleteDialog")
        self.assertTrue(dialog.property("visible"))
        self.assertEqual(1, self.bridge.pendingDeleteCount)
        self.assertTrue(self.bridge.pendingDeleteIsBulk)
        self.assertIn("1 selected note", dialog.property("title"))
        dialog.reject()
        QCoreApplication.processEvents()
        self.assertEqual(0, self.bridge.pendingDeleteCount)
        self.assertFalse(self.bridge.pendingDeleteIsBulk)
        self.assertEqual(1, self.bridge.bulkSelectionCount)
        self.assertFalse(self.bridge.presenter.repository.is_purged(UUID(selected)))
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_selected_sidebar_note_has_no_gold_selection_bar(self):
        self.assertTrue(self.bridge.createNote())
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        QCoreApplication.processEvents()

        self.assertEqual(1, self.bridge.notesModel.rowCount())
        self.assertNotEqual("", self.bridge.activeNoteId)
        self.assertIsNone(root.findChild(object, "noteSelectionIndicator"))
        sidebar_qml = (QML_MAIN.parent / "Sidebar.qml").read_text(encoding="utf-8")
        self.assertNotIn("noteSelectionIndicator", sidebar_qml)
        self.assertIn("root.theme.selectedNote", sidebar_qml)
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_concept_c_theme_and_sidebar_hierarchy(self):
        self.assertTrue(self.bridge.createNote())
        self.bridge.editDraft("# Minimal Writing\nA calm writing space")
        self.assertTrue(self.bridge.saveNow())
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        QCoreApplication.processEvents()

        self.assertEqual([], [warning.toString() for warning in warnings])
        theme = root.findChild(object, "theme")
        sidebar = root.findChild(object, "sidebar")
        new_note = root.findChild(object, "newNoteButton")
        save = root.findChild(object, "saveButton")
        sync = root.findChild(object, "syncButton")
        delete = root.findChild(object, "deleteNoteButton")
        search = root.findChild(object, "searchField")
        notes = root.findChild(object, "notesList")
        status = root.findChild(object, "sidebarStatus")

        self.assertEqual("#15171c", theme.property("mainBackground").name())
        self.assertEqual("#1d2026", theme.property("sidebar").name())
        self.assertEqual("#dcaf48", theme.property("accent").name())
        self.assertEqual("#302c26", theme.property("selectedNote").name())
        self.assertGreaterEqual(sidebar.property("width"), 290)
        self.assertLessEqual(sidebar.property("width"), 310)
        self.assertGreaterEqual(new_note.property("height"), 48)
        self.assertGreaterEqual(save.property("height"), 40)
        self.assertGreaterEqual(sync.property("height"), 40)
        self.assertGreaterEqual(delete.property("height"), 40)
        self.assertGreaterEqual(search.property("height"), 44)
        self.assertGreater(new_note.property("width"), 240)
        self.assertLess(new_note.property("y"), save.property("y"))
        self.assertLess(save.property("y"), sync.property("y"))
        self.assertLess(sync.property("y"), delete.property("y"))
        self.assertLess(delete.property("y"), search.property("y"))
        self.assertLess(search.property("y"), notes.property("y"))
        self.assertLess(notes.property("y"), status.property("y"))
        del engine

    def test_concept_c_writing_column_is_centered_and_responsive(self):
        self.assertTrue(self.bridge.createNote())
        self.bridge.editDraft("# Responsive Note\nBody")
        self.assertTrue(self.bridge.saveNow())
        engine, root, warnings = self.load_qml()
        editor_pane = root.findChild(object, "editorPane")
        reading_column = root.findChild(object, "readingColumn")
        heading = root.findChild(object, "currentNoteHeading")
        editor = root.findChild(object, "markdownEditor")
        status = root.findChild(object, "editorStatusBar")

        root.setProperty("visible", True)
        root.setProperty("width", 2048)
        root.setProperty("height", 1152)
        QCoreApplication.processEvents()
        self.assertEqual([], [warning.toString() for warning in warnings])
        self.assertGreaterEqual(reading_column.property("width"), 850)
        self.assertLessEqual(reading_column.property("width"), 950)
        self.assertAlmostEqual(
            reading_column.property("x") * 2 + reading_column.property("width"),
            editor_pane.property("width"),
            delta=4,
        )
        self.assertGreaterEqual(heading.property("font").pixelSize(), 34)
        self.assertLessEqual(heading.property("font").pixelSize(), 38)
        self.assertGreaterEqual(editor.property("font").pixelSize(), 17)
        self.assertTrue(status.property("visible"))

        root.setProperty("width", 900)
        root.setProperty("height", 600)
        QCoreApplication.processEvents()
        self.assertLess(reading_column.property("width"), 850)
        self.assertGreaterEqual(reading_column.property("x"), 24)
        self.assertLessEqual(
            reading_column.property("width"),
            editor_pane.property("width") - 48,
        )
        del engine

    def test_open_long_note_starts_at_bottom_short_note_at_top_and_saves_do_not_jump(self):
        # Real ScrollView/Flickable geometry rather than a mocked scroll flag.
        self.assertTrue(self.bridge.createNote())
        long_id = self.bridge.activeNoteId
        self.assertTrue(self.bridge.editTitle("Long note"))
        self.assertTrue(self.bridge.editContent(
            "\n".join(f"entry {index}" for index in range(180))
        ))
        self.assertTrue(self.bridge.saveNow())

        self.assertTrue(self.bridge.createNote())
        short_id = self.bridge.activeNoteId
        self.assertTrue(self.bridge.editTitle("Short note"))
        self.assertTrue(self.bridge.editContent("Brief text"))
        self.assertTrue(self.bridge.saveNow())

        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.setProperty("width", 900)
        root.setProperty("height", 600)
        for _ in range(12):
            QCoreApplication.processEvents()

        scroll = root.findChild(object, "editorScrollView")
        flickable = scroll.property("contentItem")
        self.assertIsNotNone(flickable)
        self.assertTrue(self.bridge.selectNote(long_id))
        for _ in range(18):
            QCoreApplication.processEvents()

        total = float(flickable.property("contentHeight"))
        viewport = float(flickable.property("height"))
        position = float(flickable.property("contentY"))
        maximum = max(0, total - viewport)
        self.assertGreater(maximum, 200)
        self.assertAlmostEqual(maximum, position, delta=3)
        self.assertEqual([], [warning.toString() for warning in warnings])

        # Scrolling up to read old entries must not get overridden by saving.
        flickable.setProperty("contentY", 75.0)
        self.assertTrue(self.bridge.saveNow())
        for _ in range(10):
            QCoreApplication.processEvents()
        self.assertAlmostEqual(75.0, float(flickable.property("contentY")), delta=3)

        self.assertTrue(self.bridge.selectNote(short_id))
        for _ in range(12):
            QCoreApplication.processEvents()
        self.assertAlmostEqual(0.0, float(flickable.property("contentY")), delta=3)

        self.assertTrue(self.bridge.selectNote(long_id))
        for _ in range(12):
            QCoreApplication.processEvents()
        self.assertAlmostEqual(
            float(flickable.property("contentHeight")) - float(flickable.property("height")),
            float(flickable.property("contentY")),
            delta=3,
        )
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_sidebar_width_is_persisted_after_resize(self):
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        QCoreApplication.processEvents()
        sidebar = root.findChild(object, "sidebar")
        sidebar.setProperty("width", 360)

        deadline = time.monotonic() + 0.35
        while time.monotonic() < deadline:
            QCoreApplication.processEvents()
            time.sleep(0.01)
        root.close()
        del engine
        QCoreApplication.processEvents()

        restored_engine, restored_root, restored_warnings = self.load_qml()
        restored_root.setProperty("visible", True)
        QCoreApplication.processEvents()
        restored_sidebar = restored_root.findChild(object, "sidebar")

        self.assertEqual([], [warning.toString() for warning in warnings])
        self.assertEqual([], [warning.toString() for warning in restored_warnings])
        self.assertAlmostEqual(360, restored_sidebar.property("width"), delta=1)
        del restored_engine

    def test_blank_startup_has_editable_focused_editor_without_persisted_note(self):
        from PySide6.QtCore import Qt
        from PySide6.QtTest import QTest

        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.requestActivate()
        QCoreApplication.processEvents()
        QTest.qWait(30)
        editor_content = root.findChild(object, "editorContent")
        editor = root.findChild(object, "markdownEditor")
        self.assertIsNone(root.findChild(object, "emptyState"))
        self.assertTrue(editor_content.property("visible"))
        self.assertFalse(editor.property("readOnly"))
        self.assertEqual("", editor.property("text"))
        self.assertEqual("", self.bridge.activeNoteId)
        self.assertEqual(0, self.bridge.notesModel.rowCount())
        self.assertEqual(0, len(self.bridge.presenter.repository.revisions()))
        self.assertTrue(editor.hasActiveFocus())
        for key in (Qt.Key_H, Qt.Key_E, Qt.Key_L, Qt.Key_L, Qt.Key_O):
            QTest.keyClick(root, key)
        QCoreApplication.processEvents()
        self.assertIn("hello", editor.property("text").lower())
        self.assertEqual(0, len(self.bridge.presenter.repository.revisions()))
        self.assertEqual("Dirty", self.bridge.saveState)
        self.assertTrue(self.bridge.autosave())
        self.assertEqual(1, len(self.bridge.presenter.repository.revisions()))
        self.assertEqual(1, self.bridge.notesModel.rowCount())
        self.assertIn("hello", self.bridge.currentBody.lower())
        self.assertEqual("Saved", self.bridge.saveState)
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_blank_startup_deleted_text_does_not_create_an_empty_note(self):
        from PySide6.QtCore import Qt
        from PySide6.QtTest import QTest
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.requestActivate()
        QCoreApplication.processEvents()
        QTest.qWait(30)
        editor = root.findChild(object, "markdownEditor")
        self.assertTrue(editor.hasActiveFocus())
        for key in (Qt.Key_T, Qt.Key_E, Qt.Key_S, Qt.Key_T):
            QTest.keyClick(root, key)
        QTest.keyClick(root, Qt.Key_A, Qt.ControlModifier)
        QTest.keyClick(root, Qt.Key_Backspace)
        QCoreApplication.processEvents()
        self.assertEqual("", editor.property("text"))
        self.assertTrue(self.bridge.autosave())
        self.assertEqual(0, len(self.bridge.presenter.repository.revisions()))
        self.assertEqual("", self.bridge.activeNoteId)
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_blank_startup_title_only_is_saved_on_autosave(self):
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        QCoreApplication.processEvents()
        self.assertTrue(self.bridge.editTitle("Typed title only"))
        self.assertTrue(self.bridge.autosave())
        self.assertEqual(1, len(self.bridge.presenter.repository.revisions()))
        self.assertEqual("Typed title only", self.bridge.currentTitle)
        self.assertEqual("Saved", self.bridge.saveState)
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_split_handle_has_no_visible_bar_and_keeps_resize_handle(self):
        from PySide6.QtCore import QObject
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.setProperty("width", 1200)
        root.setProperty("height", 800)
        QCoreApplication.processEvents()
        handle = root.findChild(QObject, "splitHandle")
        sidebar = root.findChild(QObject, "sidebar")
        theme = root.findChild(QObject, "theme")
        self.assertIsNotNone(handle)
        self.assertAlmostEqual(9, handle.property("width"), delta=1)
        self.assertEqual(
            theme.property("sidebar").name(),
            handle.property("color").name(),
        )
        self.assertEqual(0, len(handle.childItems()))
        self.assertTrue(sidebar.property("width") >= 250)
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_blank_startup_autosaves_only_after_idle_and_survives_reopen(self):
        from PySide6.QtCore import Qt
        from PySide6.QtTest import QTest
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.requestActivate()
        QCoreApplication.processEvents()
        QTest.qWait(30)
        editor = root.findChild(object, "markdownEditor")
        self.assertTrue(editor.hasActiveFocus())
        for key in (Qt.Key_N, Qt.Key_O, Qt.Key_T, Qt.Key_E):
            QTest.keyClick(root, key)
        QCoreApplication.processEvents()
        self.assertEqual(0, len(self.bridge.presenter.repository.revisions()))
        # Real idle timer, not a direct presenter/autosave invocation.
        QTest.qWait(2700)
        QCoreApplication.processEvents()
        self.assertEqual(1, len(self.bridge.presenter.repository.revisions()))
        created_id = self.bridge.activeNoteId
        self.assertNotEqual("", created_id)
        self.assertIn("note", self.bridge.currentBody.lower())
        from reasonnotes_app.service import NoteService
        from reasonnotes_app.presenter import NotePresenter
        from reasonnotes_sync.repository import Repository
        reopened = NotePresenter(NoteService(
            Repository.open(self.bridge.presenter.repository.root),
            origin_device="linux",
        ))
        self.assertTrue(reopened.select_note(created_id))
        self.assertIn("note", reopened.draft.lower())
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_split_handle_drag_still_resizes_sidebar_without_decorative_stripe(self):
        from PySide6.QtCore import QPoint, Qt
        from PySide6.QtTest import QTest
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.requestActivate()
        QCoreApplication.processEvents()
        handle = root.findChild(object, "splitHandle")
        sidebar = root.findChild(object, "sidebar")
        previous = sidebar.width()
        self.assertEqual(0, len(handle.childItems()))
        point = handle.mapToItem(None, handle.width()/2, handle.height()/2)
        start = QPoint(round(point.x()), round(point.y()))
        QTest.mousePress(root, Qt.LeftButton, Qt.NoModifier, start)
        QTest.mouseMove(root, QPoint(start.x() + 55, start.y()), delay=15)
        QTest.mouseRelease(root, Qt.LeftButton, Qt.NoModifier,
                           QPoint(start.x() + 55, start.y()))
        QCoreApplication.processEvents()
        self.assertAlmostEqual(previous + 55, sidebar.width(), delta=3)
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_blank_startup_does_not_create_note_on_explicit_empty_save_or_sync(self):
        from reasonnotes_sync.sync import SyncReport
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        QCoreApplication.processEvents()
        self.assertTrue(self.bridge.saveNow())
        self.assertEqual(0, len(self.bridge.presenter.repository.revisions()))
        self.assertTrue(self.bridge.closeRequested())
        self.assertEqual(0, len(self.bridge.presenter.repository.revisions()))
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_secondary_views_follow_concept_c_visual_language(self):
        engine, root, warnings = self.load_qml()
        sync_surface = root.findChild(object, "syncDialogSurface")
        sync_heading = root.findChild(object, "syncDialogHeading")
        start_sync = root.findChild(object, "startSyncButton")
        conflict_view = root.findChild(object, "conflictView")
        conflict_heading = root.findChild(object, "conflictHeading")
        conflict_history = root.findChild(object, "conflictHistoryNote")

        self.assertEqual([], [warning.toString() for warning in warnings])
        self.assertEqual("#262931", sync_surface.property("color").name())
        self.assertGreaterEqual(sync_heading.property("font").pixelSize(), 24)
        self.assertGreaterEqual(start_sync.property("height"), 44)
        self.assertEqual("#15171c", conflict_view.property("color").name())
        self.assertGreaterEqual(conflict_heading.property("font").pixelSize(), 34)
        self.assertIn("recoverable", conflict_history.property("text"))
        del engine

    def test_approved_sync_drawer_is_right_aligned_and_responsive(self):
        from PySide6.QtCore import QObject
        self.assertTrue(self.bridge.createNote())
        self.assertTrue(self.bridge.editTitle("PC Note"))
        self.assertTrue(self.bridge.editContent("From Fedora"))
        self.assertTrue(self.bridge.saveNow())

        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.setProperty("width", 1648)
        root.setProperty("height", 928)
        for _ in range(8):
            QCoreApplication.processEvents()

        drawer = root.findChild(QObject, "syncDialog")
        self.assertTrue(QMetaObject.invokeMethod(drawer, "open"))
        drawer.setProperty("selectionMode", "selected")
        for _ in range(10):
            QCoreApplication.processEvents()

        self.assertGreaterEqual(drawer.property("width"), 560)
        self.assertLessEqual(drawer.property("width"), 600)
        self.assertAlmostEqual(
            drawer.property("x") + drawer.property("width"),
            root.property("width") - 8,
            delta=3,
        )
        self.assertGreater(drawer.property("height"), 860)
        self.assertTrue(root.findChild(QObject, "syncList").property("visible"))
        self.assertTrue(root.findChild(QObject, "syncDrawerSearch").property("visible"))
        self.assertTrue(root.findChild(QObject, "syncModeSwitch").property("visible"))
        self.assertTrue(root.findChild(QObject, "syncDrawerSelectionCount").property("visible"))

        root.setProperty("width", 820)
        root.setProperty("height", 560)
        for _ in range(10):
            QCoreApplication.processEvents()
        self.assertGreaterEqual(drawer.property("x"), 0)
        self.assertLessEqual(
            drawer.property("x") + drawer.property("width"),
            root.property("width"),
        )
        self.assertLessEqual(drawer.property("height"), root.property("height"))
        self.assertGreaterEqual(root.findChild(QObject, "syncList").property("height"), 96)
        self.assertEqual([], [w.toString() for w in warnings])
        del engine

    def test_sync_drawer_selection_search_and_preview_without_network(self):
        from PySide6.QtCore import QObject, QPoint, Qt
        from PySide6.QtTest import QTest

        self.assertTrue(self.bridge.createNote())
        self.assertTrue(self.bridge.editTitle("PC Note"))
        self.assertTrue(self.bridge.editContent("Written on Fedora"))
        self.assertTrue(self.bridge.saveNow())
        self.assertTrue(self.bridge.createNote())
        self.assertTrue(self.bridge.editTitle("Other Note"))
        self.assertTrue(self.bridge.editContent("Second body"))
        self.assertTrue(self.bridge.saveNow())
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.setProperty("width", 1250)
        root.setProperty("height", 850)
        self.bridge.openSyncDialog()
        drawer = root.findChild(QObject, "syncDialog")
        self.assertTrue(QMetaObject.invokeMethod(drawer, "open"))
        for _ in range(8):
            QCoreApplication.processEvents()

        self.assertEqual("all", drawer.property("selectionMode"))
        self.assertFalse(root.findChild(QObject, "syncList").property("visible"))
        self.assertTrue(root.findChild(QObject, "startSyncButton").property("enabled"))
        selected = root.findChild(QObject, "selectedSyncMode")
        local_pos = selected.mapToItem(None, selected.width() / 2, selected.height() / 2)
        QTest.mouseClick(root, Qt.LeftButton, pos=QPoint(int(local_pos.x()), int(local_pos.y())))
        QCoreApplication.processEvents()
        self.assertEqual("selected", drawer.property("selectionMode"))
        self.assertFalse(root.findChild(QObject, "startSyncButton").property("enabled"))
        self.assertEqual("Idle", self.bridge.syncState)

        search = root.findChild(QObject, "syncDrawerSearch")
        search.setProperty("text", "PC Note")
        for _ in range(5):
            QCoreApplication.processEvents()
        self.assertEqual(1, self.bridge.syncModel.rowCount())
        self.assertEqual("PC Note", self.bridge.syncModel.row(0)["title"])
        sync_list = root.findChild(QObject, "syncList")
        QMetaObject.invokeMethod(sync_list, "forceLayout")
        QTest.qWait(60)
        QCoreApplication.processEvents()
        # ListView delegates are *visual* children of contentItem rather
        # than QObject descendants of the top-level window.
        def visible_items(item):
            for child in item.childItems():
                if child.isVisible():
                    yield child
                yield from visible_items(child)
        previews = [
            item.property("text") for item in visible_items(sync_list)
            if item.objectName() == "syncNotePreview"
        ]
        self.assertIn("Written on Fedora", previews)
        self.assertNotIn("# PC Note Written on Fedora", previews)

        # Selection and clearing affect the presenter only, not the sender.
        select_all = root.findChild(QObject, "selectAllSyncButton")
        pos = select_all.mapToItem(None, select_all.width() / 2, select_all.height() / 2)
        QTest.mouseClick(root, Qt.LeftButton, pos=QPoint(int(pos.x()), int(pos.y())))
        for _ in range(5):
            QCoreApplication.processEvents()
        self.assertEqual(2, self.bridge.syncSelectionCount)
        self.assertTrue(root.findChild(QObject, "startSyncButton").property("enabled"))

        clear = root.findChild(QObject, "clearSyncButton")
        pos = clear.mapToItem(None, clear.width() / 2, clear.height() / 2)
        QTest.mouseClick(root, Qt.LeftButton, pos=QPoint(int(pos.x()), int(pos.y())))
        for _ in range(5):
            QCoreApplication.processEvents()
        self.assertEqual(0, self.bridge.syncSelectionCount)
        self.assertFalse(root.findChild(QObject, "startSyncButton").property("enabled"))

        self.preferences.setLanguage("pl")
        self.preferences.setThemeMode("light")
        QCoreApplication.processEvents()
        self.assertEqual("Wybrane notatki", selected.property("text"))
        self.assertEqual("#ffffff",
                         root.findChild(QObject, "syncDialogSurface").property("color").name())
        self.assertEqual("Idle", self.bridge.syncState)
        self.assertEqual([], [w.toString() for w in warnings])
        del engine

    def test_minimum_window_keeps_sync_catalogue_and_resize_handle_usable(self):
        self.assertTrue(self.bridge.createNote())
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.setProperty("width", 820)
        root.setProperty("height", 560)
        sync_dialog = root.findChild(object, "syncDialog")
        self.assertTrue(QMetaObject.invokeMethod(sync_dialog, "open"))
        sync_dialog.setProperty("selectionMode", "selected")
        QCoreApplication.processEvents()

        sync_list = root.findChild(object, "syncList")
        split_handle = root.findChild(object, "splitHandle")
        self.assertEqual([], [warning.toString() for warning in warnings])
        self.assertGreaterEqual(sync_list.property("height"), 96)
        self.assertGreaterEqual(split_handle.property("width"), 8)
        del engine

    def test_real_component_tree_loads_offscreen_without_warnings(self):
        self.assertTrue(self.bridge.createNote())
        self.bridge.editDraft("# Rendered delegate\nPreview")
        self.assertTrue(self.bridge.saveNow())
        engine = QQmlApplicationEngine()
        warnings = []
        engine.warnings.connect(lambda values: warnings.extend(values))
        engine.rootContext().setContextProperty("glassPalette", self.liquid_palette)
        engine.rootContext().setContextProperty("blurState", self.blur_state)
        engine.rootContext().setContextProperty("appPreferences", self.preferences)
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
        root.findChild(object, "syncDialog").setProperty("selectionMode", "selected")
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

    def test_editor_is_editable_even_before_first_note_exists(self):
        engine = QQmlApplicationEngine()
        engine.rootContext().setContextProperty("glassPalette", self.liquid_palette)
        engine.rootContext().setContextProperty("blurState", self.blur_state)
        engine.rootContext().setContextProperty("appPreferences", self.preferences)
        engine.rootContext().setContextProperty("presenter", self.bridge)
        engine.rootContext().setContextProperty("smokeTest", True)
        engine.load(QUrl.fromLocalFile(str(QML_MAIN)))
        QCoreApplication.processEvents()
        root = engine.rootObjects()[0]
        editor = root.findChild(object, "markdownEditor")

        self.assertFalse(editor.property("readOnly"))
        self.assertEqual(0, self.bridge.notesModel.rowCount())

        self.assertTrue(self.bridge.createNote())
        QCoreApplication.processEvents()
        self.assertFalse(editor.property("readOnly"))

    def test_long_editor_content_has_a_scrollable_viewport(self):
        self.assertTrue(self.bridge.createNote())
        self.bridge.editDraft("\n".join(f"line {index}" for index in range(200)))
        self.assertTrue(self.bridge.saveNow())
        engine = QQmlApplicationEngine()
        engine.rootContext().setContextProperty("glassPalette", self.liquid_palette)
        engine.rootContext().setContextProperty("blurState", self.blur_state)
        engine.rootContext().setContextProperty("appPreferences", self.preferences)
        engine.rootContext().setContextProperty("presenter", self.bridge)
        engine.rootContext().setContextProperty("smokeTest", True)
        engine.load(QUrl.fromLocalFile(str(QML_MAIN)))
        QCoreApplication.processEvents()
        root = engine.rootObjects()[0]
        root.setProperty("visible", True)
        QCoreApplication.processEvents()
        scroll = root.findChild(object, "editorScrollView")

        self.assertIsNotNone(scroll)
        self.assertGreater(
            scroll.property("contentHeight"),
            scroll.property("height"),
        )

    def test_bridge_updates_models_and_persists_only_after_save(self):
        self.assertTrue(self.bridge.createNote())
        self.assertEqual(1, self.bridge.notesModel.rowCount())
        self.assertEqual("Pending", self.bridge.activeDeliveryStatus)

        self.bridge.editDraft("# QML note\nBody")
        self.assertEqual("Dirty", self.bridge.saveState)
        self.assertTrue(self.bridge.saveNow())

        self.assertEqual("Saved", self.bridge.saveState)
        self.assertEqual("# QML note\nBody", self.bridge.currentBody)
        self.assertEqual("QML note", self.bridge.notesModel.row(0)["title"])

    def test_keystrokes_do_not_scan_history_or_rebuild_ui_models(self):
        self.assertTrue(self.bridge.createNote())
        note_count = len(self.bridge.presenter.repository.revisions())
        note_resets, sync_resets, general_signals, status_signals = [], [], [], []
        self.bridge.notesModel.modelReset.connect(lambda: note_resets.append(1))
        self.bridge.syncModel.modelReset.connect(lambda: sync_resets.append(1))
        self.bridge.stateChanged.connect(lambda: general_signals.append(1))
        self.bridge.saveStatusChanged.connect(lambda: status_signals.append(1))

        # A keystroke must never call graph(), enumerate revisions, or
        # rebuild either note list. Persistence is reserved for the timer
        # or explicit Save/selection/close, not these edit callbacks.
        with patch.object(self.bridge.presenter.repository, "graph", side_effect=AssertionError("per-key graph traversal")), patch.object(
            self.bridge, "_refresh_models", side_effect=AssertionError("per-key model refresh")
        ):
            for title in ("D", "Da", "Dai", "Daily"):
                self.assertTrue(self.bridge.editTitle(title))
            for body in ("O", "On", "One", "One line"):
                self.assertTrue(self.bridge.editContent(body))

        self.assertEqual([], note_resets)
        self.assertEqual([], sync_resets)
        self.assertEqual([], general_signals)
        self.assertEqual(8, len(status_signals))
        self.assertEqual("Dirty", self.bridge.saveState)
        self.assertEqual(note_count, len(self.bridge.presenter.repository.revisions()))
        self.assertEqual("# Daily\n\nOne line", self.bridge.currentBody)
        self.assertTrue(self.bridge.saveNow())
        self.assertEqual(note_count + 1, len(self.bridge.presenter.repository.revisions()))
        self.assertEqual("Daily", self.bridge.notesModel.row(0)["title"])
        self.assertGreater(len(note_resets), 0)
        self.assertGreater(len(sync_resets), 0)

    def test_clean_note_switch_only_changes_selection_not_catalogues(self):
        self.assertTrue(self.bridge.createNote())
        first = self.bridge.activeNoteId
        self.assertTrue(self.bridge.editTitle("First"))
        self.assertTrue(self.bridge.editContent("First body"))
        self.assertTrue(self.bridge.saveNow())
        self.assertTrue(self.bridge.createNote())
        second = self.bridge.activeNoteId
        self.assertTrue(self.bridge.editTitle("Second"))
        self.assertTrue(self.bridge.editContent("Second body"))
        self.assertTrue(self.bridge.saveNow())

        engine, root, warnings = self.load_qml()
        resets = {"notes": 0, "sync": 0}
        self.bridge.notesModel.modelReset.connect(
            lambda: resets.__setitem__("notes", resets["notes"] + 1)
        )
        self.bridge.syncModel.modelReset.connect(
            lambda: resets.__setitem__("sync", resets["sync"] + 1)
        )
        repo = self.bridge.presenter.repository
        load = repo._load_revisions
        scans = []

        def counted_load():
            scans.append(1)
            return load()

        # The real Qt property bindings also query save permissions and
        # delivery status after selection notifications. Emulate those reads.
        self.bridge.stateChanged.connect(
            lambda: (self.bridge.activeDeliveryStatus, self.bridge.canEdit)
        )
        with patch.object(repo, "_load_revisions", side_effect=counted_load):
            for note_id, title, content in (
                (first, "First", "First body"),
                (second, "Second", "Second body"),
                (first, "First", "First body"),
            ):
                previous_scans = len(scans)
                self.assertTrue(self.bridge.selectNote(note_id))
                QCoreApplication.processEvents()
                self.assertEqual(title, self.bridge.currentTitle)
                self.assertEqual(content, self.bridge.currentContent)
                self.assertEqual(title, root.findChild(object, "currentNoteHeading").property("text"))
                self.assertEqual(content, root.findChild(object, "markdownEditor").property("text"))
                self.assertEqual("Pending", self.bridge.activeDeliveryStatus)
                self.assertLessEqual(len(scans) - previous_scans, 8)
        self.assertEqual({"notes": 0, "sync": 0}, resets)
        self.assertEqual([], [warning.toString() for warning in warnings])
        del engine

    def test_switch_with_dirty_filtered_note_updates_sync_catalogue(self):
        self.assertTrue(self.bridge.createNote())
        edited_note = self.bridge.activeNoteId
        self.assertTrue(self.bridge.editTitle("Temporary"))
        self.assertTrue(self.bridge.saveNow())
        self.assertTrue(self.bridge.createNote())
        other_note = self.bridge.activeNoteId
        self.assertTrue(self.bridge.editTitle("Keep visible"))
        self.assertTrue(self.bridge.saveNow())
        self.bridge.search("Keep visible")
        self.assertEqual(1, self.bridge.notesModel.rowCount())
        self.assertTrue(self.bridge.selectNote(edited_note))
        self.assertTrue(self.bridge.editTitle("Renamed while filtered"))

        resets = []
        self.bridge.syncModel.modelReset.connect(lambda: resets.append(1))
        self.assertTrue(self.bridge.selectNote(other_note))
        self.assertEqual("Saved", self.bridge.saveState)
        self.assertGreater(len(resets), 0)
        self.assertEqual(1, self.bridge.notesModel.rowCount())
        rows = [
            self.bridge.syncModel.row(n)
            for n in range(self.bridge.syncModel.rowCount())
        ]
        self.assertEqual(
            "Renamed while filtered",
            next(row["title"] for row in rows if row["noteId"] == edited_note),
        )
        self.assertEqual(
            "# Renamed while filtered\n\n",
            self.bridge.presenter.service.open_note(UUID(edited_note)),
        )

    def test_selection_delivery_uses_snapshot_updated_by_sync(self):
        from datetime import datetime, timezone
        from reasonnotes_sync.delivery import DeliveryLedger

        self.assertTrue(self.bridge.createNote())
        note_id = self.bridge.activeNoteId
        repo = self.bridge.presenter.repository
        revision_id = repo.graph().heads(UUID(note_id))[0].revision_id
        self.assertEqual("Pending", self.bridge.activeDeliveryStatus)
        ledger = DeliveryLedger(repo.root)
        ledger.record_requested(
            revision_id, "android", datetime.now(timezone.utc).replace(microsecond=0)
        )
        # Repository mutation alone is not a sync completion notification.
        self.assertEqual("Pending", self.bridge.activeDeliveryStatus)
        self.bridge._sync_completed(
            object(), SyncReport((), (), (), (), (), (), (), ())
        )
        self.assertEqual("Transfer requested", self.bridge.activeDeliveryStatus)
        self.assertEqual("Transfer requested", self.bridge.syncModel.row(0)["deliveryStatus"])

    def test_clean_autosave_does_not_rebuild_catalogues(self):
        self.assertTrue(self.bridge.createNote())
        resets = []
        self.bridge.notesModel.modelReset.connect(lambda: resets.append("notes"))
        self.bridge.syncModel.modelReset.connect(lambda: resets.append("sync"))
        with patch.object(self.bridge, "_refresh_models", side_effect=AssertionError("clean autosave rescan")):
            self.assertTrue(self.bridge.autosave())
        self.assertEqual([], resets)

    def test_conflict_candidates_refresh_without_resetting_note_lists(self):
        from datetime import datetime, timezone
        from reasonnotes_sync.envelope import Revision

        self.assertTrue(self.bridge.createNote())
        first = self.bridge.activeNoteId
        self.assertTrue(self.bridge.editDraft("# Base"))
        self.assertTrue(self.bridge.saveNow())
        repo = self.bridge.presenter.repository
        base = repo.graph().heads(UUID(first))[0]
        self.assertTrue(self.bridge.editDraft("# Local head"))
        self.assertTrue(self.bridge.saveNow())
        remote = Revision.create(
            UUID(first), (base.revision_id,), "android",
            datetime(2026, 10, 16, 12, 0, tzinfo=timezone.utc),
            False, "# Remote head",
        )
        repo.import_bytes(remote.serialize(), "external-remote.md")
        self.bridge.presenter.refresh()
        self.bridge._notify()
        self.assertTrue(self.bridge.createNote())
        second = self.bridge.activeNoteId
        note_resets, sync_resets = [], []
        self.bridge.notesModel.modelReset.connect(lambda: note_resets.append(1))
        self.bridge.syncModel.modelReset.connect(lambda: sync_resets.append(1))

        self.assertTrue(self.bridge.selectNote(first))
        self.assertTrue(self.bridge.hasConflict)
        self.assertEqual(2, self.bridge.conflictModel.rowCount())
        self.assertFalse(self.bridge.canEdit)
        self.assertEqual([], note_resets)
        self.assertEqual([], sync_resets)

        self.assertTrue(self.bridge.selectNote(second))
        self.assertFalse(self.bridge.hasConflict)
        self.assertEqual(0, self.bridge.conflictModel.rowCount())
        self.assertEqual([], note_resets)
        self.assertEqual([], sync_resets)

    def test_autosave_waits_for_typing_pause(self):
        from PySide6.QtCore import QObject

        engine, root, warnings = self.load_qml()
        timer = root.findChild(QObject, "autosaveTimer")
        self.assertIsNotNone(timer)
        self.assertEqual(2500, timer.property("interval"))
        self.assertFalse(timer.property("repeat"))
        self.assertEqual([], [warning.toString() for warning in warnings])

    def test_title_is_editable_and_body_is_separate_in_qml(self):
        self.assertTrue(self.bridge.createNote())
        self.assertTrue(self.bridge.editTitle("Sleep log"))
        self.assertTrue(self.bridge.editContent("Bed Time:\nWake up:"))
        self.assertEqual("# Sleep log\n\nBed Time:\nWake up:", self.bridge.currentBody)
        self.assertTrue(self.bridge.saveNow())
        engine, root, warnings = self.load_qml()
        QCoreApplication.processEvents()

        heading = root.findChild(object, "currentNoteHeading")
        editor = root.findChild(object, "markdownEditor")
        self.assertIsNotNone(heading)
        self.assertFalse(heading.property("readOnly"))
        self.assertEqual("Sleep log", heading.property("text"))
        self.assertEqual("Bed Time:\nWake up:", editor.property("text"))
        self.assertIsNone(root.findChild(object, "notePreview"))
        self.assertEqual([], [warning.toString() for warning in warnings])

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

    def test_purged_note_is_absent_from_sync_catalogue(self):
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
        self.assertFalse(any(row["noteId"] == note_id for row in rows))
        self.assertTrue(self.bridge.presenter.repository.is_purged(UUID(note_id)))

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
