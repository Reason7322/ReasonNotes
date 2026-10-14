import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtCore

ApplicationWindow {
    id: root
    objectName: "reasonNotesWindow"
    property var bridge: presenter
    property bool smokeMode: smokeTest

    visible: !smokeMode
    width: 1200
    height: 800
    minimumWidth: 760
    minimumHeight: 520
    title: qsTr("ReasonNotes")
    color: theme.editor
    palette.window: theme.sidebar
    palette.windowText: theme.textPrimary
    palette.base: theme.editor
    palette.alternateBase: theme.control
    palette.text: theme.textPrimary
    palette.button: theme.control
    palette.buttonText: theme.textPrimary
    palette.highlight: theme.selection
    palette.highlightedText: theme.textPrimary
    palette.placeholderText: theme.textSecondary

    Theme {
        id: theme
    }

    Settings {
        id: settings
        category: "ReasonNotesWindow"
        property real sidebarWidth: theme.sidebarInitial
    }

    SplitView {
        anchors.fill: parent
        orientation: Qt.Horizontal

        Sidebar {
            id: sidebar
            objectName: "sidebar"
            presenter: root.bridge
            theme: theme
            SplitView.minimumWidth: theme.sidebarMinimum
            SplitView.preferredWidth: Math.max(
                theme.sidebarMinimum,
                settings.sidebarWidth
            )
            onWidthChanged: {
                if (width >= theme.sidebarMinimum)
                    settings.sidebarWidth = width
            }
            onSyncRequested: syncDialog.open()
            onDeleteRequested: deleteDialog.open()
        }

        Item {
            SplitView.fillWidth: true
            SplitView.minimumWidth: 420

            EditorPane {
                id: editorPane
                objectName: "editorPane"
                anchors.fill: parent
                presenter: root.bridge
                theme: theme
                visible: !root.bridge.hasConflict
            }

            ConflictView {
                id: conflictView
                objectName: "conflictView"
                anchors.fill: parent
                presenter: root.bridge
                theme: theme
                visible: root.bridge.hasConflict
            }
        }
    }

    SyncDialog {
        id: syncDialog
        objectName: "syncDialog"
        presenter: root.bridge
        theme: theme
    }

    Dialog {
        id: deleteDialog
        objectName: "deleteDialog"
        anchors.centerIn: parent
        modal: true
        title: qsTr("Delete note?")
        standardButtons: Dialog.Cancel | Dialog.Ok
        closePolicy: Popup.CloseOnEscape
        onAccepted: root.bridge.deleteCurrent()

        Label {
            text: qsTr("This creates a deletion revision. Immutable history remains recoverable.")
            color: theme.textPrimary
            wrapMode: Text.Wrap
        }

        background: Rectangle {
            color: theme.sidebar
            border.color: theme.border
            radius: theme.radius
        }
    }

    Shortcut {
        sequences: [StandardKey.New]
        onActivated: root.bridge.createNote()
    }

    Shortcut {
        sequences: [StandardKey.Save]
        onActivated: root.bridge.saveNow()
    }

    Shortcut {
        sequences: [StandardKey.Find]
        onActivated: sidebar.focusSearch()
    }

    onClosing: function(close) {
        close.accepted = root.bridge.closeRequested()
    }
}
