import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Dialog {
    id: root
    required property var presenter
    required property var theme

    modal: true
    title: qsTr("Synchronize notes")
    width: 620
    height: 620
    anchors.centerIn: parent
    closePolicy: Popup.CloseOnEscape

    background: Rectangle {
        color: root.theme.sidebar
        border.color: root.theme.border
        radius: root.theme.radius
    }

    contentItem: ColumnLayout {
        spacing: root.theme.spacing

        RadioButton {
            id: allMode
            text: qsTr("Sync all notes")
            checked: true
        }

        RadioButton {
            id: selectedMode
            objectName: "selectedSyncMode"
            text: qsTr("Choose notes to sync")
        }

        TextField {
            Layout.fillWidth: true
            visible: selectedMode.checked
            placeholderText: qsTr("Search notes")
            onTextChanged: root.presenter.searchSyncNotes(text)
        }

        RowLayout {
            visible: selectedMode.checked
            Button {
                text: qsTr("Select All")
                onClicked: root.presenter.selectAllSyncNotes()
            }
            Button {
                text: qsTr("Clear Selection")
                onClicked: root.presenter.clearSyncSelection()
            }
            Label {
                Layout.fillWidth: true
                horizontalAlignment: Text.AlignRight
                text: qsTr("%1 selected").arg(root.presenter.syncSelectionCount)
                color: root.theme.textSecondary
            }
        }

        ListView {
            id: syncList
            objectName: "syncList"
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: selectedMode.checked
            clip: true
            spacing: root.theme.spacingSmall
            model: root.presenter.syncModel

            delegate: CheckDelegate {
                required property string noteId
                required property string title
                required property string preview
                required property bool selected
                required property string deliveryStatus

                width: syncList.width
                text: qsTr("%1\n%2 · %3").arg(title).arg(preview).arg(deliveryStatus)
                checked: selected
                onToggled: root.presenter.setSyncSelected(noteId, checked)
            }

            ScrollBar.vertical: ScrollBar {}
        }

        Label {
            Layout.fillWidth: true
            text: qsTr("Transfer requests are not labeled confirmed until a ReasonNotes receipt is imported.")
            color: root.theme.textSecondary
            wrapMode: Text.Wrap
        }

        RowLayout {
            Layout.fillWidth: true

            Button {
                text: qsTr("Cancel")
                onClicked: root.close()
            }

            Item {
                Layout.fillWidth: true
            }

            Button {
                objectName: "startSyncButton"
                text: qsTr("Start Sync")
                enabled: allMode.checked || root.presenter.syncSelectionCount > 0
                onClicked: {
                    const started = allMode.checked
                        ? root.presenter.syncAll()
                        : root.presenter.syncSelected()
                    if (started)
                        root.close()
                }
            }
        }
    }
}
