import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtCore

ApplicationWindow {
    id: root
    objectName: "reasonNotesWindow"
    property var bridge: presenter
    property bool smokeMode: smokeTest
    property real restoredSidebarWidth: Math.max(
        theme.sidebarMinimum,
        Math.min(440, settings.sidebarWidth)
    )

    visible: !smokeMode
    width: 1200
    height: 800
    minimumWidth: 820
    minimumHeight: 560
    title: qsTr("ReasonNotes")
    color: theme.mainBackground
    palette.window: theme.sidebar
    palette.windowText: theme.textPrimary
    palette.base: theme.editor
    palette.alternateBase: theme.surface
    palette.text: theme.textPrimary
    palette.button: theme.surface
    palette.buttonText: theme.textPrimary
    palette.highlight: theme.accent
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

    Timer {
        id: saveSidebarWidth
        interval: 250
        onTriggered: settings.sidebarWidth = sidebar.width
    }

    SplitView {
        anchors.fill: parent
        orientation: Qt.Horizontal
        handle: Rectangle {
            objectName: "splitHandle"
            implicitWidth: 9
            color: "transparent"

            Rectangle {
                anchors.centerIn: parent
                width: 1
                height: parent.height
                color: parent.SplitHandle.hovered || parent.SplitHandle.pressed
                    ? theme.accent
                    : theme.border
            }
        }

        Sidebar {
            id: sidebar
            objectName: "sidebar"
            presenter: root.bridge
            theme: theme
            SplitView.minimumWidth: theme.sidebarMinimum
            SplitView.maximumWidth: 440
            SplitView.preferredWidth: root.restoredSidebarWidth
            onWidthChanged: saveSidebarWidth.restart()
            onSyncRequested: syncDialog.open()
            onDeleteRequested: deleteDialog.open()
        }

        Item {
            SplitView.fillWidth: true
            SplitView.minimumWidth: 520

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
        title: qsTr("Delete this note?")
        width: Math.min(480, root.width - theme.spacingXXLarge * 2)
        standardButtons: Dialog.Cancel | Dialog.Ok
        closePolicy: Popup.CloseOnEscape
        onAccepted: root.bridge.deleteCurrent()

        Label {
            width: parent ? parent.width : 420
            text: qsTr("ReasonNotes will create a deletion revision. The note remains recoverable from immutable history.")
            color: theme.textPrimary
            wrapMode: Text.Wrap
            font.pixelSize: theme.secondarySize
        }

        background: Rectangle {
            color: theme.surface
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
