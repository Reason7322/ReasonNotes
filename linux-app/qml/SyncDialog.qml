import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Dialog {
    id: root
    required property var presenter
    required property var theme

    modal: true
    width: Math.min(680, parent.width - root.theme.spacingXXLarge * 2)
    height: selectedMode.checked
        ? Math.min(700, parent.height - root.theme.spacingLarge)
        : Math.min(410, parent.height - root.theme.spacingXXLarge * 2)
    anchors.centerIn: parent
    padding: root.theme.spacing
    closePolicy: Popup.CloseOnEscape

    ButtonGroup {
        id: syncModeGroup
    }

    header: Item {
        implicitHeight: 68

        Column {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.leftMargin: root.theme.spacingLarge
            anchors.rightMargin: root.theme.spacingLarge
            anchors.bottomMargin: root.theme.spacing
            spacing: root.theme.spacingTiny

            Label {
                id: syncDialogHeading
                objectName: "syncDialogHeading"
                text: qsTr("Synchronize notes")
                color: root.theme.textPrimary
                font.family: root.theme.fontFamily
                font.pixelSize: 26
                font.weight: Font.DemiBold
            }

            Label {
                text: qsTr("Choose what this device requests for transfer.")
                color: root.theme.textSecondary
                font.pixelSize: root.theme.smallSize
            }
        }
    }

    background: Rectangle {
        objectName: "syncDialogSurface"
        color: root.theme.surface
        border.color: root.theme.border
        border.width: 1
        radius: root.theme.radius
    }

    contentItem: ColumnLayout {
        spacing: root.theme.spacingSmall

        Rectangle {
            Layout.fillWidth: true
            implicitHeight: allMode.implicitHeight + root.theme.spacing * 2
            color: allMode.checked ? root.theme.selectedNote : root.theme.mainBackground
            border.color: allMode.activeFocus
                ? root.theme.textPrimary
                : (allMode.checked ? root.theme.accent : root.theme.border)
            radius: root.theme.radiusSmall

            RadioButton {
                id: allMode
                ButtonGroup.group: syncModeGroup
                anchors.fill: parent
                anchors.margins: root.theme.spacing
                checked: true
                text: qsTr("Sync all notes")
                font.pixelSize: root.theme.secondarySize
                palette.windowText: root.theme.textPrimary
            }
        }

        Rectangle {
            Layout.fillWidth: true
            implicitHeight: selectedMode.implicitHeight + root.theme.spacing * 2
            color: selectedMode.checked ? root.theme.selectedNote : root.theme.mainBackground
            border.color: selectedMode.activeFocus
                ? root.theme.textPrimary
                : (selectedMode.checked ? root.theme.accent : root.theme.border)
            radius: root.theme.radiusSmall

            RadioButton {
                id: selectedMode
                ButtonGroup.group: syncModeGroup
                objectName: "selectedSyncMode"
                anchors.fill: parent
                anchors.margins: root.theme.spacing
                text: qsTr("Choose notes to sync")
                font.pixelSize: root.theme.secondarySize
                palette.windowText: root.theme.textPrimary
            }
        }

        TextField {
            Layout.fillWidth: true
            implicitHeight: root.theme.fieldHeight
            visible: selectedMode.checked
            placeholderText: qsTr("Search the synchronization catalogue")
            color: root.theme.textPrimary
            placeholderTextColor: root.theme.textSecondary
            font.pixelSize: root.theme.secondarySize
            leftPadding: root.theme.spacing
            rightPadding: root.theme.spacing
            onTextChanged: root.presenter.searchSyncNotes(text)
            background: Rectangle {
                color: root.theme.mainBackground
                border.color: parent.activeFocus ? root.theme.accent : root.theme.border
                radius: root.theme.radiusSmall
            }
        }

        RowLayout {
            visible: selectedMode.checked
            spacing: root.theme.spacingSmall

            Button {
                text: qsTr("Select All")
                implicitHeight: 38
                onClicked: root.presenter.selectAllSyncNotes()
                contentItem: Label { text: parent.text; color: root.theme.textPrimary; font.pixelSize: root.theme.smallSize; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                background: Rectangle { color: parent.hovered ? root.theme.surfaceHover : root.theme.mainBackground; border.color: parent.activeFocus ? root.theme.accent : root.theme.border; radius: root.theme.radiusSmall }
            }
            Button {
                text: qsTr("Clear Selection")
                implicitHeight: 38
                onClicked: root.presenter.clearSyncSelection()
                contentItem: Label { text: parent.text; color: root.theme.textPrimary; font.pixelSize: root.theme.smallSize; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                background: Rectangle { color: parent.hovered ? root.theme.surfaceHover : root.theme.mainBackground; border.color: parent.activeFocus ? root.theme.accent : root.theme.border; radius: root.theme.radiusSmall }
            }
            Item { Layout.fillWidth: true }
            Label {
                text: qsTr("%1 selected").arg(root.presenter.syncSelectionCount)
                color: root.theme.textSecondary
                font.pixelSize: root.theme.smallSize
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
                height: 66
                checked: selected
                onToggled: root.presenter.setSyncSelected(noteId, checked)

                contentItem: Column {
                    leftPadding: root.theme.spacingSmall
                    spacing: root.theme.spacingTiny
                    Label { width: parent.width; text: title; color: root.theme.textPrimary; font.pixelSize: root.theme.sidebarTitleSize; font.weight: Font.Medium; elide: Text.ElideRight }
                    Label { width: parent.width; text: qsTr("%1 · %2").arg(preview.replace(/\s+/g, " ").trim()).arg(deliveryStatus); color: root.theme.textSecondary; font.pixelSize: root.theme.smallSize; elide: Text.ElideRight }
                }
                background: Rectangle { color: parent.hovered ? root.theme.surfaceHover : root.theme.mainBackground; border.color: root.theme.border; radius: root.theme.radiusSmall }
            }

            ScrollBar.vertical: ScrollBar {}
        }

        Item {
            Layout.fillHeight: !selectedMode.checked
            Layout.minimumHeight: selectedMode.checked ? 0 : root.theme.spacing
        }

        Label {
            Layout.fillWidth: true
            text: qsTr("A transfer request is shown as confirmed only after a ReasonNotes receipt is imported.")
            color: root.theme.textSecondary
            font.pixelSize: root.theme.smallSize
            wrapMode: Text.Wrap
        }

        Rectangle {
            Layout.fillWidth: true
            implicitHeight: 1
            color: root.theme.border
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: root.theme.spacing

            Button {
                text: qsTr("Cancel")
                implicitWidth: 104
                implicitHeight: 46
                onClicked: root.close()
                contentItem: Label { text: parent.text; color: root.theme.textPrimary; font.pixelSize: root.theme.secondarySize; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                background: Rectangle { color: parent.hovered ? root.theme.surfaceHover : "transparent"; border.color: parent.activeFocus ? root.theme.accent : root.theme.border; radius: root.theme.radiusSmall }
            }

            Item { Layout.fillWidth: true }

            Button {
                id: startSyncButton
                objectName: "startSyncButton"
                text: qsTr("Start Sync")
                implicitWidth: 132
                implicitHeight: 46
                enabled: allMode.checked || root.presenter.syncSelectionCount > 0
                onClicked: {
                    const started = allMode.checked
                        ? root.presenter.syncAll()
                        : root.presenter.syncSelected()
                    if (started)
                        root.close()
                }
                contentItem: Label { text: parent.text; color: root.theme.accentText; opacity: parent.enabled ? 1 : 0.5; font.pixelSize: root.theme.secondarySize; font.weight: Font.DemiBold; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                background: Rectangle { color: parent.enabled ? (parent.hovered ? Qt.lighter(root.theme.accent, 1.08) : root.theme.accent) : root.theme.border; border.color: parent.activeFocus ? root.theme.textPrimary : "transparent"; border.width: parent.activeFocus ? 2 : 0; radius: root.theme.radiusSmall }
            }
        }
    }
}
