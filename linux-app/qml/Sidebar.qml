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
    border.color: theme.border

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: root.theme.spacing
        spacing: root.theme.spacing

        Label {
            text: qsTr("ReasonNotes")
            color: root.theme.textPrimary
            font.pixelSize: root.theme.headingSize
            font.bold: true
            Layout.fillWidth: true
        }

        GridLayout {
            Layout.fillWidth: true
            columns: 2
            rowSpacing: root.theme.spacingSmall
            columnSpacing: root.theme.spacingSmall

            Button {
                text: qsTr("New Note")
                Layout.fillWidth: true
                onClicked: root.presenter.createNote()
            }

            Button {
                text: qsTr("Save")
                Layout.fillWidth: true
                enabled: root.presenter.activeNoteId !== ""
                onClicked: root.presenter.saveNow()
            }

            Button {
                text: qsTr("Sync")
                Layout.fillWidth: true
                onClicked: {
                    root.presenter.openSyncDialog()
                    root.syncRequested()
                }
            }

            Button {
                objectName: "deleteNoteButton"
                text: qsTr("Delete")
                Layout.fillWidth: true
                enabled: root.presenter.activeNoteId !== ""
                onClicked: root.deleteRequested()
            }
        }

        TextField {
            id: searchField
            objectName: "searchField"
            Layout.fillWidth: true
            placeholderText: qsTr("Search notes")
            color: root.theme.textPrimary
            placeholderTextColor: root.theme.textSecondary
            selectByMouse: true
            onTextChanged: root.presenter.search(text)
            background: Rectangle {
                color: root.theme.editor
                border.color: root.theme.border
                radius: root.theme.radius
            }
        }

        ListView {
            id: notesList
            objectName: "notesList"
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: root.theme.spacingSmall
            model: root.presenter.notesModel

            delegate: ItemDelegate {
                required property string noteId
                required property string title
                required property string preview
                required property bool conflicted
                required property string deliveryStatus

                width: notesList.width
                padding: root.theme.spacing
                highlighted: noteId === root.presenter.activeNoteId
                onClicked: root.presenter.selectNote(noteId)

                background: Rectangle {
                    color: parent.highlighted
                        ? root.theme.selection
                        : (parent.hovered ? root.theme.controlHover : root.theme.control)
                    radius: root.theme.radius
                    border.color: parent.conflicted
                        ? root.theme.warning
                        : root.theme.border
                }

                contentItem: Column {
                    spacing: root.theme.spacingSmall

                    Label {
                        width: parent.width
                        text: title
                        color: root.theme.textPrimary
                        font.bold: true
                        elide: Text.ElideRight
                    }

                    Label {
                        width: parent.width
                        text: preview
                        color: root.theme.textSecondary
                        maximumLineCount: 2
                        wrapMode: Text.Wrap
                        elide: Text.ElideRight
                    }

                    Label {
                        width: parent.width
                        text: conflicted
                            ? qsTr("Conflict · %1").arg(deliveryStatus)
                            : deliveryStatus
                        color: conflicted
                            ? root.theme.warning
                            : root.theme.textSecondary
                        font.pixelSize: root.theme.bodySize - 2
                        elide: Text.ElideRight
                    }
                }
            }

            ScrollBar.vertical: ScrollBar {}
        }

        Rectangle {
            Layout.fillWidth: true
            implicitHeight: 1
            color: root.theme.border
        }

        Label {
            Layout.fillWidth: true
            text: root.presenter.saveError !== ""
                ? qsTr("%1: %2").arg(root.presenter.saveState).arg(root.presenter.saveError)
                : root.presenter.saveState
            color: root.presenter.saveState === "Error"
                ? root.theme.error
                : root.theme.textSecondary
            wrapMode: Text.Wrap
        }

        Label {
            Layout.fillWidth: true
            text: root.presenter.syncMessage !== ""
                ? qsTr("%1: %2").arg(root.presenter.syncState).arg(root.presenter.syncMessage)
                : root.presenter.syncState
            color: root.presenter.syncState === "Error"
                ? root.theme.error
                : root.theme.textSecondary
            wrapMode: Text.Wrap
        }
    }

    function focusSearch() {
        searchField.forceActiveFocus()
        searchField.selectAll()
    }
}
