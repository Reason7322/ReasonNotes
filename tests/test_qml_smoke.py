import os
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event, get_ident
import time
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QCoreApplication, QMetaObject, QSettings, QUrl
from PySide6.QtGui import QGuiApplication
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuick import QQuickItem

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

    def load_qml(self):
        engine = QQmlApplicationEngine()
        warnings = []
        engine.warnings.connect(lambda values: warnings.extend(values))
        engine.rootContext().setContextProperty("appPreferences", self.preferences)
        engine.rootContext().setContextProperty("presenter", self.bridge)
        engine.rootContext().setContextProperty("smokeTest", True)
        engine.load(QUrl.fromLocalFile(str(QML_MAIN)))
        QCoreApplication.processEvents()
        self.assertEqual(1, len(engine.rootObjects()))
        return engine, engine.rootObjects()[0], warnings

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

    def test_concept_c_empty_state_replaces_inactive_editor(self):
        engine, root, warnings = self.load_qml()
        empty_state = root.findChild(object, "emptyState")
        editor_content = root.findChild(object, "editorContent")

        self.assertEqual([], [warning.toString() for warning in warnings])
        self.assertTrue(empty_state.property("visible"))
        self.assertFalse(editor_content.property("visible"))
        self.assertIn("Create", empty_state.property("accessibleDescription"))
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

    def test_minimum_window_keeps_sync_catalogue_and_resize_handle_usable(self):
        self.assertTrue(self.bridge.createNote())
        engine, root, warnings = self.load_qml()
        root.setProperty("visible", True)
        root.setProperty("width", 820)
        root.setProperty("height", 560)
        selected_mode = root.findChild(object, "selectedSyncMode")
        selected_mode.setProperty("checked", True)
        sync_dialog = root.findChild(object, "syncDialog")
        self.assertTrue(QMetaObject.invokeMethod(sync_dialog, "open"))
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
        engine.rootContext().setContextProperty("appPreferences", self.preferences)
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
