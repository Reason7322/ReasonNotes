import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtCore

ApplicationWindow {
    id: root
    objectName: "reasonNotesWindow"
    property var bridge: presenter
    readonly property int deletionTargetCount: root.bridge
        ? root.bridge.pendingDeleteCount : 0
    readonly property bool deletingBulkSelection: root.bridge
        ? root.bridge.pendingDeleteIsBulk : false
    property bool smokeMode: smokeTest
    property bool settingsVisible: false
    readonly property string appearanceMode: appPreferences.themeMode
    readonly property string languageCode: appPreferences.language
    property real restoredSidebarWidth: Math.max(
        theme.sidebarMinimum,
        Math.min(440, settings.sidebarWidth)
    )
    // A single boundary coordinate directly positions both panels.
    // Unlike a native SplitView, this adds zero independently tinted pixels.
    property real liveSidebarWidth: restoredSidebarWidth

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

    Item {
        id: panelLayout
        objectName: "panelLayout"
        anchors.fill: parent

        Sidebar {
            id: sidebar
            objectName: "sidebar"
            presenter: root.bridge
            theme: theme
            i18n: i18n
            settingsOpen: root.settingsVisible
            onSettingsRequested: root.settingsVisible = !root.settingsVisible
            onNotesRequested: root.settingsVisible = false
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            anchors.left: parent.left
            width: Math.max(
                theme.sidebarMinimum,
                Math.min(440, root.width - 520, root.liveSidebarWidth)
            )
            onWidthChanged: saveSidebarWidth.restart()

            // Preserve an accessible drag target without painting an extra
            // vertical surface into the transparent Wayland framebuffer.
            // It sits entirely inside the otherwise unused sidebar margin.
            MouseArea {
                id: sidebarResizeTarget
                objectName: "sidebarResizeTarget"
                z: 100
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: 12
                acceptedButtons: Qt.LeftButton
                hoverEnabled: true
                preventStealing: true
                cursorShape: Qt.SplitHCursor
                property real pressWindowX: 0
                property real pressSidebarWidth: 0
                onPressed: function(mouse) {
                    pressWindowX = mapToItem(null, mouse.x, mouse.y).x
                    pressSidebarWidth = sidebar.width
                }
                onPositionChanged: function(mouse) {
                    if (!(pressedButtons & Qt.LeftButton))
                        return
                    const currentX = mapToItem(null, mouse.x, mouse.y).x
                    const maximum = Math.min(
                        440, root.width - 520
                    )
                    // Move the shared edge once: both sidebar and editor
                    // use this coordinate, with no painted separator.
                    root.liveSidebarWidth = Math.max(
                        theme.sidebarMinimum,
                        Math.min(maximum, pressSidebarWidth + currentX - pressWindowX)
                    )
                }
            }
            onSyncRequested: syncDialog.open()
            onDeleteRequested: {
                if (root.bridge.prepareDelete() > 0)
                    deleteDialog.open()
            }
        }

        Item {
            id: contentPanel
            objectName: "contentPanel"
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            anchors.left: sidebar.right
            anchors.right: parent.right

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
        title: root.deletingBulkSelection
            ? (root.deletionTargetCount === 1
                ? i18n.t("Permanently delete 1 selected note?")
                : i18n.t("Permanently delete %1 notes?").arg(root.deletionTargetCount))
            : i18n.t("Delete this note?")
        width: Math.min(480, root.width - theme.spacingXXLarge * 2)
        standardButtons: Dialog.Cancel | Dialog.Ok
        closePolicy: Popup.CloseOnEscape
        onAccepted: root.bridge.confirmDelete()
        onClosed: root.bridge.cancelDelete()

        Label {
            width: parent ? parent.width : 420
            text: root.deletingBulkSelection
                ? (root.deletionTargetCount === 1
                    ? i18n.t("This permanently erases the selected note and all its previous versions. It cannot be undone. The paired phone will delete its copy when both apps are open.")
                    : i18n.t("This permanently erases all %1 selected notes and every previous version of each. It cannot be undone. The paired phone will delete its copies when both apps are open.").arg(root.deletionTargetCount))
                : i18n.t("This permanently erases the note and all its previous versions. It cannot be undone. The paired phone will delete its copy the next time both apps are open.")
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
