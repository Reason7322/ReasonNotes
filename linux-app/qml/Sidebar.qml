import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: root
    required property var presenter
    required property var theme
    required property var i18n
    property bool settingsOpen: false
    readonly property int selectedCount: root.presenter
        ? root.presenter.bulkSelectionCount : 0
    signal settingsRequested()
    signal notesRequested()
    signal syncRequested()
    signal deleteRequested()

    color: theme.sidebar
    // The sidebar is the highest-priority Dolphin visual match: brighter
    // blue-gray at the top, a distinctly cool navigation middle, deep smoked
    // lower panel, with a gentle lift along the bottom edge.
    gradient: Gradient {
        objectName: "dolphinSidebarMaterial"
        GradientStop { position: 0.0; color: root.theme.isBlurredGlass ? root.theme.dolphinSidebarTop : root.theme.sidebar }
        GradientStop { position: 0.13; color: root.theme.isBlurredGlass ? root.theme.dolphinSidebarUpper : root.theme.sidebar }
        GradientStop { position: 0.24; color: root.theme.sidebar }
        GradientStop { position: 0.37; color: root.theme.isBlurredGlass ? root.theme.dolphinSidebarInterlude : root.theme.sidebar }
        GradientStop { position: 0.56; color: root.theme.isBlurredGlass ? root.theme.dolphinSidebarLower : root.theme.sidebar }
        GradientStop { position: 0.78; color: root.theme.isBlurredGlass ? root.theme.dolphinSidebarDeep : root.theme.sidebar }
        GradientStop { position: 1.0; color: root.theme.isBlurredGlass ? root.theme.dolphinSidebarBottom : root.theme.sidebar }
    }
    // Keep a thin edge reflection, separate from the surface tint.
    Rectangle {
        objectName: "glassEdgeReflection"
        visible: root.theme.isGlass
        z: 0
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 28
        color: "transparent"
        gradient: Gradient {
            GradientStop { position: 0; color: root.theme.glassTop }
            GradientStop { position: 1; color: root.theme.glassBottom }
        }
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.leftMargin: root.theme.spacingLarge
        anchors.rightMargin: root.theme.spacingLarge
        anchors.topMargin: root.theme.spacingLarge
        anchors.bottomMargin: root.theme.spacingLarge
        spacing: root.theme.spacingSmall

        Row {
            Layout.fillWidth: true
            Layout.bottomMargin: root.theme.spacingLarge

            Label {
                text: root.i18n.t("Reason")
                color: root.theme.textPrimary
                font.family: root.theme.fontFamily
                font.pixelSize: root.theme.applicationTitleSize
                font.weight: Font.DemiBold
            }

            Label {
                text: root.i18n.t("Notes")
                color: root.theme.textSecondary
                font.family: root.theme.fontFamily
                font.pixelSize: root.theme.applicationTitleSize
                font.weight: Font.Light
            }
        }

        Button {
            id: newNoteButton
            objectName: "newNoteButton"
            Layout.fillWidth: true
            implicitHeight: root.theme.primaryButtonHeight
            hoverEnabled: true
            onClicked: {
                root.notesRequested()
                root.presenter.createNote()
            }

            contentItem: RowLayout {
                spacing: root.theme.spacing
                // Reserve the same exact 24px icon slot as Save, Sync,
                // Delete, Settings and every note-list delegate.
                Item {
                    id: newNoteIconSlot
                    objectName: "newNoteIconSlot"
                    Layout.preferredWidth: 24
                    Layout.preferredHeight: 24
                    Label {
                        anchors.centerIn: parent
                        text: "+"
                        color: root.theme.accentText
                        font.pixelSize: 24
                    }
                }
                Label {
                    objectName: "newNoteText"
                    text: root.i18n.t("New Note")
                    color: root.theme.accentText
                    font.pixelSize: root.theme.secondarySize
                    font.weight: Font.DemiBold
                    Layout.fillWidth: true
                }
            }

            background: Rectangle {
                color: newNoteButton.down
                    ? Qt.darker(root.theme.accent, 1.12)
                    : (newNoteButton.hovered ? Qt.lighter(root.theme.accent, 1.08) : root.theme.accent)
                border.color: newNoteButton.activeFocus ? root.theme.textPrimary : "transparent"
                border.width: newNoteButton.activeFocus ? 2 : 0
                radius: root.theme.radiusSmall
            }
        }

        Button {
            id: saveButton
            objectName: "saveButton"
            Layout.fillWidth: true
            implicitHeight: root.theme.actionButtonHeight
            enabled: root.presenter.activeNoteId !== ""
            hoverEnabled: true
            onClicked: root.presenter.saveNow()

            contentItem: RowLayout {
                spacing: root.theme.spacing
                ActionIcon {
                    objectName: "saveActionIcon"
                    kind: "save"
                    lightMode: root.theme.isLight
                    glassMode: root.theme.isGlass
                    Layout.preferredWidth: 24
                    Layout.preferredHeight: 24
                    visualState: !saveButton.enabled ? "disabled"
                        : (saveButton.hovered ? "hover" : "normal")
                }
                Label { text: root.i18n.t("Save"); color: saveButton.enabled ? root.theme.textPrimary : root.theme.textSecondary; font.pixelSize: root.theme.secondarySize; Layout.fillWidth: true }
            }
            background: Rectangle { color: saveButton.hovered ? root.theme.surfaceHover : "transparent"; border.color: saveButton.activeFocus ? root.theme.accent : "transparent"; radius: root.theme.radiusSmall }
        }

        Button {
            id: syncButton
            objectName: "syncButton"
            Layout.fillWidth: true
            implicitHeight: root.theme.actionButtonHeight
            hoverEnabled: true
            onClicked: {
                root.presenter.openSyncDialog()
                root.syncRequested()
            }

            contentItem: RowLayout {
                spacing: root.theme.spacing
                ActionIcon {
                    objectName: "syncActionIcon"
                    kind: "sync"
                    lightMode: root.theme.isLight
                    glassMode: root.theme.isGlass
                    Layout.preferredWidth: 24
                    Layout.preferredHeight: 24
                    visualState: syncButton.hovered ? "hover" : "normal"
                }
                Label { text: root.i18n.t("Sync"); color: root.theme.textPrimary; font.pixelSize: root.theme.secondarySize; Layout.fillWidth: true }
            }
            background: Rectangle { color: syncButton.hovered ? root.theme.surfaceHover : "transparent"; border.color: syncButton.activeFocus ? root.theme.accent : "transparent"; radius: root.theme.radiusSmall }
        }

        Button {
            id: deleteNoteButton
            objectName: "deleteNoteButton"
            Layout.fillWidth: true
            implicitHeight: root.theme.actionButtonHeight
            enabled: root.presenter.activeNoteId !== "" || root.selectedCount > 0
            hoverEnabled: true
            onClicked: root.deleteRequested()

            contentItem: RowLayout {
                spacing: root.theme.spacing
                ActionIcon {
                    objectName: "deleteActionIcon"
                    kind: "delete"
                    lightMode: root.theme.isLight
                    glassMode: root.theme.isGlass
                    Layout.preferredWidth: 24
                    Layout.preferredHeight: 24
                    visualState: !deleteNoteButton.enabled ? "disabled"
                        : (deleteNoteButton.hovered ? "hover" : "normal")
                }
                Label {
                    text: root.selectedCount > 0
                        ? root.i18n.t("Delete (%1)").arg(root.selectedCount)
                        : root.i18n.t("Delete")
                    color: deleteNoteButton.enabled ? root.theme.textPrimary : root.theme.textSecondary
                    font.pixelSize: root.theme.secondarySize
                    Layout.fillWidth: true
                }
            }
            background: Rectangle { color: deleteNoteButton.hovered ? root.theme.surfaceHover : "transparent"; border.color: deleteNoteButton.activeFocus ? root.theme.accent : "transparent"; radius: root.theme.radiusSmall }
        }

        RowLayout {
            objectName: "bulkSelectionBar"
            Layout.fillWidth: true
            visible: root.selectedCount > 0
            Layout.preferredHeight: visible ? 28 : 0
            Label {
                objectName: "bulkSelectionLabel"
                text: root.i18n.t("%1 selected").arg(root.selectedCount)
                color: root.theme.textSecondary
                font.pixelSize: root.theme.smallSize
                Layout.fillWidth: true
            }
            Button {
                objectName: "clearBulkSelectionButton"
                text: root.i18n.t("Clear")
                flat: true
                hoverEnabled: true
                onClicked: root.presenter.clearBulkSelection()
            }
        }

        TextField {
            id: searchField
            objectName: "searchField"
            Layout.fillWidth: true
            Layout.topMargin: root.theme.spacing
            implicitHeight: root.theme.fieldHeight
            // x = 4px button inset + 24px icon slot + 12px gap.
            // Match all action labels and note titles at the same x.
            leftPadding: 40
            rightPadding: root.theme.spacing
            placeholderText: root.i18n.t("Search notes…")
            color: root.theme.textPrimary
            placeholderTextColor: root.theme.textSecondary
            font.pixelSize: root.theme.secondarySize
            selectByMouse: true
            onTextChanged: root.presenter.search(text)
            background: Rectangle {
                color: root.theme.surface
                border.color: searchField.activeFocus ? root.theme.accent : root.theme.border
                radius: root.theme.radius
                Item {
                    id: searchIconSlot
                    objectName: "searchIconSlot"
                    anchors.left: parent.left
                    anchors.leftMargin: 4
                    anchors.verticalCenter: parent.verticalCenter
                    width: 24
                    height: 24
                    Label {
                        anchors.centerIn: parent
                        text: "⌕"
                        color: root.theme.textSecondary
                        font.pixelSize: 20
                    }
                }
            }
        }

        ListView {
            id: notesList
            objectName: "notesList"
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.topMargin: root.theme.spacingSmall
            clip: true
            spacing: root.theme.spacingTiny
            model: root.presenter.notesModel

            delegate: ItemDelegate {
                id: noteDelegate
                objectName: "noteDelegate"
                required property string noteId
                required property string title
                required property bool conflicted
                required property string deliveryStatus

                width: notesList.width
                height: 56
                // Match the action buttons' content inset exactly:
                // note icon/title must share the Save/Sync/Delete left edge.
                padding: 0
                leftPadding: saveButton.leftPadding
                rightPadding: saveButton.rightPadding
                readonly property bool bulkSelected:
                    root.presenter.bulkSelectedNoteIds.indexOf(noteId) !== -1
                highlighted: noteId === root.presenter.activeNoteId
                hoverEnabled: true
                // Preserve keyboard activation (Enter/Space) for accessibility.
                onClicked: {
                    root.notesRequested()
                    root.presenter.selectNote(noteId)
                }

                background: Rectangle {
                    color: noteDelegate.bulkSelected || noteDelegate.highlighted
                        ? root.theme.selectedNote
                        : (noteClickArea.containsMouse ? root.theme.surfaceHover : "transparent")
                    radius: root.theme.radiusSmall
                    border.color: noteDelegate.bulkSelected || noteDelegate.activeFocus
                        ? root.theme.accent : "transparent"
                }

                contentItem: RowLayout {
                    spacing: root.theme.spacing

                    Item {
                        objectName: "noteIconSlot"
                        Layout.preferredWidth: 24
                        Layout.preferredHeight: 24
                        Layout.alignment: Qt.AlignVCenter

                        Label {
                            objectName: "bulkSelectedCheckmark"
                            anchors.centerIn: parent
                            visible: noteDelegate.bulkSelected
                            text: "✓"
                            color: root.theme.accent
                            font.pixelSize: 19
                            font.weight: Font.Bold
                        }

                        Image {
                            visible: !noteDelegate.bulkSelected
                            objectName: "noteRowIcon"
                            anchors.centerIn: parent
                            width: 20
                            height: 20
                            source: noteDelegate.conflicted
                                ? (root.theme.isLight
                                    ? "icons/lucide/themed/notebook-text-danger-light.svg"
                                    : (root.theme.isGlass
                                        ? "icons/lucide/themed/notebook-text-danger-glass.svg"
                                        : "icons/lucide/themed/notebook-text-danger.svg"))
                                : (root.theme.isLight
                                    ? "icons/lucide/themed/notebook-text-light.svg"
                                    : (root.theme.isGlass
                                        ? "icons/lucide/themed/notebook-text-glass.svg"
                                        : "icons/lucide/themed/notebook-text.svg"))
                            sourceSize.width: 40
                            sourceSize.height: 40
                            fillMode: Image.PreserveAspectFit
                            smooth: true
                        }
                    }

                    Label {
                        Layout.fillWidth: true
                        Layout.alignment: Qt.AlignVCenter
                        text: noteDelegate.title
                        color: root.theme.textPrimary
                        font.pixelSize: root.theme.sidebarTitleSize
                        font.weight: noteDelegate.highlighted ? Font.DemiBold : Font.Medium
                        elide: Text.ElideRight
                        verticalAlignment: Text.AlignVCenter
                    }

                    Label {
                        visible: noteDelegate.conflicted
                        Layout.alignment: Qt.AlignVCenter
                        text: "!"
                        color: root.theme.error
                        font.pixelSize: root.theme.sidebarTitleSize
                        font.weight: Font.Bold
                    }
                }

                // AbstractButton.clicked does not carry mouse modifiers.
                // An overlay MouseArea lets a normal click open a note and
                // Shift+left-click toggle exactly that row without opening it.
                MouseArea {
                    id: noteClickArea
                    objectName: "noteClickArea"
                    anchors.fill: parent
                    acceptedButtons: Qt.LeftButton
                    hoverEnabled: true
                    onClicked: function(mouse) {
                        root.notesRequested()
                        if (mouse.modifiers & Qt.ShiftModifier)
                            root.presenter.toggleBulkNote(noteDelegate.noteId)
                        else
                            root.presenter.selectNote(noteDelegate.noteId)
                    }
                }
            }

            // The remaining bright short strip at the sidebar edge is the
            // notes ListView scroll thumb; never paint a scrollbar track.
            // Mouse wheel, touchpad and list scrolling remain functional.
            ScrollBar.vertical: ScrollBar {
                objectName: "notesListScrollBar"
                policy: ScrollBar.AlwaysOff
            }
        }

        Button {
            id: settingsButton
            objectName: "settingsButton"
            Layout.fillWidth: true
            implicitHeight: root.theme.actionButtonHeight
            hoverEnabled: true
            onClicked: root.settingsRequested()
            contentItem: RowLayout {
                spacing: root.theme.spacing
                ActionIcon {
                    objectName: "settingsActionIcon"
                    kind: "settings"
                    lightMode: root.theme.isLight
                    glassMode: root.theme.isGlass
                    Layout.preferredWidth: 24
                    Layout.preferredHeight: 24
                    visualState: settingsButton.hovered ? "hover" : "normal"
                }
                Label {
                    text: root.i18n.t("Settings")
                    color: root.theme.textPrimary
                    font.pixelSize: root.theme.secondarySize
                    Layout.fillWidth: true
                }
            }
            background: Rectangle {
                color: root.settingsOpen ? root.theme.selectedNote
                    : (settingsButton.hovered ? root.theme.surfaceHover : "transparent")
                radius: root.theme.radiusSmall
                border.color: settingsButton.activeFocus ? root.theme.accent : "transparent"
            }
        }

        Item {
            id: sidebarStatus
            objectName: "sidebarStatus"
            Layout.fillWidth: true
            implicitHeight: 66

            Rectangle { anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top; height: 1; color: root.theme.border }

            Column {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                spacing: 4

                Label {
                    width: parent.width
                    text: root.presenter.saveError !== ""
                        ? root.i18n.t("Local · %1").arg(root.presenter.saveError)
                        : root.i18n.t("Local · %1").arg(root.i18n.status(root.presenter.saveState))
                    color: root.presenter.saveState === "Error"
                        ? root.theme.error
                        : (root.presenter.saveState === "Saved" ? root.theme.success : root.theme.textPrimary)
                    font.pixelSize: root.theme.smallSize
                    elide: Text.ElideRight
                }

                Label {
                    width: parent.width
                    text: root.presenter.syncMessage !== ""
                        ? root.i18n.t("Phone · %1 — %2").arg(root.i18n.status(root.presenter.syncState)).arg(root.presenter.syncMessage)
                        : root.i18n.t("Phone · %1").arg(root.i18n.status(root.presenter.syncState))
                    color: root.presenter.syncState === "Error" ? root.theme.error : root.theme.textSecondary
                    font.pixelSize: root.theme.smallSize
                    elide: Text.ElideRight
                }
            }
        }
    }

    function focusSearch() {
        searchField.forceActiveFocus()
        searchField.selectAll()
    }
}
