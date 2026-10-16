import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtCore

ApplicationWindow {
    id: root
    objectName: "reasonNotesWindow"
    property var bridge: presenter
    property bool smokeMode: smokeTest
    property bool settingsVisible: false
    readonly property string appearanceMode: appPreferences.themeMode
    readonly property string languageCode: appPreferences.language
    property real restoredSidebarWidth: Math.max(
        theme.sidebarMinimum,
        Math.min(440, settings.sidebarWidth)
    )

    visible: !smokeMode
    width: 1200
    height: 800
    minimumWidth: 820
    minimumHeight: 560
    title: i18n.t("ReasonNotes")
    // Only use a transparent framebuffer when native compositor blur is active.
    // Otherwise preserve a readable opaque surface with no wallpaper banding.
    color: theme.isGlass && blurState.active ? "transparent" : theme.mainBackground
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
        mode: appPreferences.themeMode
    }

    I18n {
        id: i18n
        language: appPreferences.language
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
                anchors.horizontalCenterOffset: -3
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
            i18n: i18n
            settingsOpen: root.settingsVisible
            onSettingsRequested: root.settingsVisible = !root.settingsVisible
            onNotesRequested: root.settingsVisible = false
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
                i18n: i18n
                visible: !root.settingsVisible && !root.bridge.hasConflict
            }

            ConflictView {
                id: conflictView
                objectName: "conflictView"
                anchors.fill: parent
                presenter: root.bridge
                theme: theme
                i18n: i18n
                visible: !root.settingsVisible && root.bridge.hasConflict
            }

            SettingsPane {
                id: settingsPane
                objectName: "settingsPane"
                anchors.fill: parent
                theme: theme
                i18n: i18n
                preferences: appPreferences
                purgeSync: typeof purgeSyncBridge !== "undefined" ? purgeSyncBridge : null
                visible: root.settingsVisible
                onBackRequested: root.settingsVisible = false
            }
        }
    }

    SyncDialog {
        id: syncDialog
        objectName: "syncDialog"
        presenter: root.bridge
        theme: theme
        i18n: i18n
    }

    Dialog {
        id: deleteDialog
        objectName: "deleteDialog"
        anchors.centerIn: parent
        modal: true
        title: i18n.t("Delete this note?")
        width: Math.min(480, root.width - theme.spacingXXLarge * 2)
        standardButtons: Dialog.Cancel | Dialog.Ok
        closePolicy: Popup.CloseOnEscape
        onAccepted: root.bridge.deleteCurrent()

        Label {
            width: parent ? parent.width : 420
            text: i18n.t("This permanently erases the note and all its previous versions. It cannot be undone. The paired phone will delete its copy the next time both apps are open.")
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
        objectName: "incomingRefreshShortcut"
        sequence: "F5"
        onActivated: root.bridge.refreshIncoming()
    }

    // A small, temporary confirmation; refreshing never launches outgoing
    // KDE Connect transfers or turns an unconfirmed transfer into delivery.
    property string incomingRefreshNotice: ""

    Connections {
        target: root.bridge
        function onIncomingRefreshMessageChanged() {
            root.incomingRefreshNotice = root.bridge.incomingRefreshMessage
            hideIncomingRefreshNotice.stop()
            if (root.incomingRefreshNotice !== "Checking incoming notes…"
                    && root.incomingRefreshNotice !== "Refresh queued…") {
                hideIncomingRefreshNotice.start()
            }
        }
    }

    Timer {
        id: hideIncomingRefreshNotice
        interval: 3500
        repeat: false
        onTriggered: root.incomingRefreshNotice = ""
    }

    Rectangle {
        objectName: "incomingRefreshNotice"
        z: 200
        anchors.bottom: parent.bottom
        anchors.right: parent.right
        anchors.margins: theme.spacingLarge
        visible: root.incomingRefreshNotice !== ""
        color: theme.surface
        border.color: theme.border
        radius: theme.radiusSmall
        width: incomingRefreshText.implicitWidth + theme.spacingLarge * 2
        height: incomingRefreshText.implicitHeight + theme.spacingMedium * 2

        Label {
            id: incomingRefreshText
            anchors.centerIn: parent
            text: root.incomingRefreshNotice
            color: theme.textPrimary
            font.pixelSize: theme.secondarySize
        }
    }

    Shortcut {
        sequences: [StandardKey.Find]
        onActivated: sidebar.focusSearch()
    }

    onClosing: function(close) {
        close.accepted = root.bridge.closeRequested()
    }
}
