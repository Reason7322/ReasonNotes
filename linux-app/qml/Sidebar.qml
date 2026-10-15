import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: root
    required property var presenter
    required property var theme
    signal syncRequested()
    signal deleteRequested()

    color: theme.sidebar

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
                text: qsTr("Reason")
                color: root.theme.textPrimary
                font.family: root.theme.fontFamily
                font.pixelSize: root.theme.applicationTitleSize
                font.weight: Font.DemiBold
            }

            Label {
                text: qsTr("Notes")
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
            onClicked: root.presenter.createNote()

            contentItem: RowLayout {
                spacing: root.theme.spacing
                Label { text: "+"; color: root.theme.accentText; font.pixelSize: 24 }
                Label { text: qsTr("New Note"); color: root.theme.accentText; font.pixelSize: root.theme.secondarySize; font.weight: Font.DemiBold; Layout.fillWidth: true }
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
                    Layout.preferredWidth: 24
                    Layout.preferredHeight: 24
                    visualState: !saveButton.enabled ? "disabled"
                        : (saveButton.hovered ? "hover" : "normal")
                }
                Label { text: qsTr("Save"); color: saveButton.enabled ? root.theme.textPrimary : root.theme.textSecondary; font.pixelSize: root.theme.secondarySize; Layout.fillWidth: true }
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
                    Layout.preferredWidth: 24
                    Layout.preferredHeight: 24
                    visualState: syncButton.hovered ? "hover" : "normal"
                }
                Label { text: qsTr("Sync"); color: root.theme.textPrimary; font.pixelSize: root.theme.secondarySize; Layout.fillWidth: true }
            }
            background: Rectangle { color: syncButton.hovered ? root.theme.surfaceHover : "transparent"; border.color: syncButton.activeFocus ? root.theme.accent : "transparent"; radius: root.theme.radiusSmall }
        }

        Button {
            id: deleteNoteButton
            objectName: "deleteNoteButton"
            Layout.fillWidth: true
            implicitHeight: root.theme.actionButtonHeight
            enabled: root.presenter.activeNoteId !== ""
            hoverEnabled: true
            onClicked: root.deleteRequested()

            contentItem: RowLayout {
                spacing: root.theme.spacing
                ActionIcon {
                    objectName: "deleteActionIcon"
                    kind: "delete"
                    Layout.preferredWidth: 24
                    Layout.preferredHeight: 24
                    visualState: !deleteNoteButton.enabled ? "disabled"
                        : (deleteNoteButton.hovered ? "hover" : "normal")
                }
                Label { text: qsTr("Delete"); color: deleteNoteButton.enabled ? root.theme.textPrimary : root.theme.textSecondary; font.pixelSize: root.theme.secondarySize; Layout.fillWidth: true }
            }
            background: Rectangle { color: deleteNoteButton.hovered ? root.theme.surfaceHover : "transparent"; border.color: deleteNoteButton.activeFocus ? root.theme.accent : "transparent"; radius: root.theme.radiusSmall }
        }

        TextField {
            id: searchField
            objectName: "searchField"
            Layout.fillWidth: true
            Layout.topMargin: root.theme.spacing
            implicitHeight: root.theme.fieldHeight
            leftPadding: 42
            rightPadding: root.theme.spacing
            placeholderText: qsTr("Search notes…")
            color: root.theme.textPrimary
            placeholderTextColor: root.theme.textSecondary
            font.pixelSize: root.theme.secondarySize
            selectByMouse: true
            onTextChanged: root.presenter.search(text)
            background: Rectangle {
                color: root.theme.surface
                border.color: searchField.activeFocus ? root.theme.accent : root.theme.border
                radius: root.theme.radius
                Label {
                    anchors.left: parent.left
                    anchors.leftMargin: 14
                    anchors.verticalCenter: parent.verticalCenter
                    text: "⌕"
                    color: root.theme.textSecondary
                    font.pixelSize: 20
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
                highlighted: noteId === root.presenter.activeNoteId
                hoverEnabled: true
                onClicked: root.presenter.selectNote(noteId)

                background: Rectangle {
                    color: noteDelegate.highlighted
                        ? root.theme.selectedNote
                        : (noteDelegate.hovered ? root.theme.surfaceHover : "transparent")
                    radius: root.theme.radiusSmall
                    border.color: noteDelegate.activeFocus ? root.theme.accent : "transparent"
                }

                contentItem: RowLayout {
                    spacing: root.theme.spacing

                    Item {
                        objectName: "noteIconSlot"
                        Layout.preferredWidth: 24
                        Layout.preferredHeight: 24
                        Layout.alignment: Qt.AlignVCenter

                        Image {
                            objectName: "noteRowIcon"
                            anchors.centerIn: parent
                            width: 20
                            height: 20
                            source: noteDelegate.conflicted
                                ? "icons/lucide/themed/notebook-text-danger.svg"
                                : "icons/lucide/themed/notebook-text.svg"
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
            }

            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }
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
                        ? qsTr("Local · %1").arg(root.presenter.saveError)
                        : qsTr("Local · %1").arg(root.presenter.saveState)
                    color: root.presenter.saveState === "Error"
                        ? root.theme.error
                        : (root.presenter.saveState === "Saved" ? root.theme.success : root.theme.textPrimary)
                    font.pixelSize: root.theme.smallSize
                    elide: Text.ElideRight
                }

                Label {
                    width: parent.width
                    text: root.presenter.syncMessage !== ""
                        ? qsTr("Phone · %1 — %2").arg(root.presenter.syncState).arg(root.presenter.syncMessage)
                        : qsTr("Phone · %1").arg(root.presenter.syncState)
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
