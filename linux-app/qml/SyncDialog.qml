import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Approved design: full-height right-hand drawer. This is a visual-only
// presentation of the existing QtNotePresenter sync model and actions.
Dialog {
    id: root
    objectName: "syncDrawer"
    required property var presenter
    required property var theme
    required property var i18n
    property string selectionMode: "all"

    modal: true
    width: Math.min(596, Math.max(440, parent.width * 0.43))
    height: Math.max(420, parent.height - 16)
    x: parent.width - width - 8
    y: 8
    padding: 20
    spacing: 0
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    Overlay.modal: Rectangle {
        color: "#99000000"
    }

    function cleanPreview(source, title) {
        // The presenter returns a canonical Markdown preview. Strip the
        // duplicated "# Title" without modifying the underlying note.
        let preview = String(source || "").replace(/[\r\n]+/g, " ").trim()
        preview = preview.replace(/^#{1,6}\s+/, "").trim()
        if (preview.toLowerCase().startsWith(title.toLowerCase())) {
            preview = preview.slice(title.length).trim()
        }
        return preview.replace(/^[\s·:–—-]+/, "").replace(/\s+/g, " ").trim()
    }

    function shortStatus(value) {
        if (value === "Transfer requested") return root.i18n.t("Requested")
        return root.i18n.status(value)
    }

    function statusColor(value) {
        if (value === "Transfer requested" || value === "Needs attention")
            return root.theme.isLight ? "#925E0C" : "#E2B55A"
        if (value === "Confirmed")
            return root.theme.success
        return root.theme.textSecondary
    }

    onOpened: {
        root.selectionMode = "all"
        syncSearch.text = ""
    }

    background: Rectangle {
        objectName: "syncDialogSurface"
        color: root.theme.surface
        gradient: Gradient {
            GradientStop { position: 0; color: root.theme.isGlass ? root.theme.frosted(glassPalette.lightColor, 0.98) : root.theme.surface }
            GradientStop { position: 0.24; color: root.theme.isGlass ? root.theme.frosted(glassPalette.baseColor, 0.98) : root.theme.surface }
            GradientStop { position: 1; color: root.theme.isGlass ? root.theme.frosted(glassPalette.windowColor, 0.98) : root.theme.surface }
        }
        border.color: root.theme.border
        border.width: 1
        radius: 14
    }

    header: Item {
        implicitHeight: 104

        ColumnLayout {
            anchors.fill: parent
            anchors.leftMargin: 20
            anchors.rightMargin: 12
            anchors.topMargin: 16
            anchors.bottomMargin: 12
            spacing: 6

            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                Label {
                    id: syncDialogHeading
                    objectName: "syncDialogHeading"
                    Layout.fillWidth: true
                    text: root.i18n.t("Synchronize notes")
                    color: root.theme.textPrimary
                    font.family: root.theme.fontFamily
                    font.pixelSize: 26
                    font.weight: Font.DemiBold
                    elide: Text.ElideRight
                }
                Button {
                    objectName: "closeSyncDrawerButton"
                    implicitWidth: 34
                    implicitHeight: 34
                    onClicked: root.close()
                    text: "×"
                    contentItem: Label {
                        text: parent.text
                        color: root.theme.textSecondary
                        font.pixelSize: 26
                        font.weight: Font.Light
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                    }
                    background: Rectangle {
                        color: parent.hovered ? root.theme.surfaceHover : "transparent"
                        radius: root.theme.radiusSmall
                    }
                }
            }
            Label {
                Layout.fillWidth: true
                text: root.i18n.t("Choose what this device requests for transfer.")
                color: root.theme.textSecondary
                font.pixelSize: root.theme.secondarySize
                elide: Text.ElideRight
            }
        }
    }

    contentItem: ColumnLayout {
        spacing: 12

        // Small segmented mode control, not oversized stacked radio cards.
        RowLayout {
            objectName: "syncModeSwitch"
            Layout.fillWidth: true
            spacing: 4

            Button {
                id: allMode
                objectName: "allSyncMode"
                Layout.fillWidth: true
                implicitHeight: 48
                checked: root.selectionMode === "all"
                onClicked: root.selectionMode = "all"
                text: root.i18n.t("All notes")
                contentItem: Label {
                    text: parent.text
                    color: parent.checked ? root.theme.accent : root.theme.textPrimary
                    font.pixelSize: root.theme.secondarySize
                    font.weight: parent.checked ? Font.DemiBold : Font.Normal
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                background: Rectangle {
                    color: parent.checked ? root.theme.selectedNote : root.theme.mainBackground
                    border.color: parent.checked ? root.theme.accent
                        : (parent.activeFocus ? root.theme.textPrimary : root.theme.border)
                    radius: root.theme.radiusSmall
                }
            }

            Button {
                id: selectedMode
                objectName: "selectedSyncMode"
                Layout.fillWidth: true
                implicitHeight: 48
                checked: root.selectionMode === "selected"
                onClicked: root.selectionMode = "selected"
                text: root.i18n.t("Selected notes")
                contentItem: Label {
                    text: parent.text
                    color: parent.checked ? root.theme.accent : root.theme.textPrimary
                    font.pixelSize: root.theme.secondarySize
                    font.weight: parent.checked ? Font.DemiBold : Font.Normal
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                background: Rectangle {
                    color: parent.checked ? root.theme.selectedNote : root.theme.mainBackground
                    border.color: parent.checked ? root.theme.accent
                        : (parent.activeFocus ? root.theme.textPrimary : root.theme.border)
                    radius: root.theme.radiusSmall
                }
            }
        }

        TextField {
            id: syncSearch
            objectName: "syncDrawerSearch"
            Layout.fillWidth: true
            implicitHeight: 45
            visible: root.selectionMode === "selected"
            placeholderText: root.i18n.t("Search notes…")
            color: root.theme.textPrimary
            placeholderTextColor: root.theme.textSecondary
            font.pixelSize: root.theme.secondarySize
            leftPadding: 14
            rightPadding: 14
            onTextChanged: root.presenter.searchSyncNotes(text)
            background: Rectangle {
                color: root.theme.mainBackground
                border.color: parent.activeFocus ? root.theme.accent : root.theme.border
                radius: root.theme.radiusSmall
            }
        }

        RowLayout {
            visible: root.selectionMode === "selected"
            Layout.fillWidth: true
            spacing: 10

            Button {
                objectName: "selectAllSyncButton"
                text: root.i18n.t("Select all")
                implicitHeight: 35
                onClicked: root.presenter.selectAllSyncNotes()
                contentItem: Label {
                    text: parent.text
                    color: root.theme.textPrimary
                    font.pixelSize: root.theme.smallSize
                    verticalAlignment: Text.AlignVCenter
                    horizontalAlignment: Text.AlignHCenter
                }
                background: Rectangle {
                    color: parent.hovered ? root.theme.surfaceHover : root.theme.mainBackground
                    border.color: parent.activeFocus ? root.theme.accent : root.theme.border
                    radius: root.theme.radiusSmall
                }
            }
            Button {
                objectName: "clearSyncButton"
                text: root.i18n.t("Clear")
                implicitHeight: 35
                onClicked: root.presenter.clearSyncSelection()
                contentItem: Label {
                    text: parent.text
                    color: root.theme.textPrimary
                    font.pixelSize: root.theme.smallSize
                    verticalAlignment: Text.AlignVCenter
                    horizontalAlignment: Text.AlignHCenter
                }
                background: Rectangle {
                    color: parent.hovered ? root.theme.surfaceHover : root.theme.mainBackground
                    border.color: parent.activeFocus ? root.theme.accent : root.theme.border
                    radius: root.theme.radiusSmall
                }
            }
            Item { Layout.fillWidth: true }
            Label {
                objectName: "syncDrawerSelectionCount"
                text: root.i18n.t("%1 selected").arg(root.presenter.syncSelectionCount)
                color: root.theme.textSecondary
                font.pixelSize: root.theme.smallSize
            }
        }

        ListView {
            id: syncList
            objectName: "syncList"
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.minimumHeight: root.selectionMode === "selected" ? 100 : 0
            visible: root.selectionMode === "selected"
            clip: true
            spacing: 6
            model: root.presenter.syncModel
            boundsBehavior: Flickable.StopAtBounds

            delegate: CheckDelegate {
                id: noteRow
                objectName: "syncNoteRow"
                required property string noteId
                required property string title
                required property string preview
                required property bool selected
                required property string deliveryStatus
                property string summary: root.cleanPreview(preview, title)

                width: syncList.width - (syncList.contentHeight > syncList.height ? 9 : 0)
                height: 69
                checked: selected
                leftPadding: 14
                rightPadding: 40
                topPadding: 8
                bottomPadding: 8
                onToggled: root.presenter.setSyncSelected(noteId, checked)

                indicator: Rectangle {
                    x: noteRow.width - width - 13
                    y: Math.round((noteRow.height - height) / 2)
                    implicitWidth: 19
                    implicitHeight: 19
                    radius: 4
                    border.width: 1
                    border.color: noteRow.checked ? root.theme.accent : root.theme.textSecondary
                    color: noteRow.checked ? root.theme.accent : "transparent"
                    Label {
                        anchors.centerIn: parent
                        text: "✓"
                        visible: noteRow.checked
                        color: root.theme.accentText
                        font.pixelSize: 14
                        font.weight: Font.Bold
                    }
                }

                contentItem: RowLayout {
                    spacing: 10
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 3
                        Label {
                            Layout.fillWidth: true
                            text: noteRow.title
                            color: root.theme.textPrimary
                            font.pixelSize: root.theme.sidebarTitleSize
                            font.weight: Font.Medium
                            elide: Text.ElideRight
                        }
                        Label {
                            objectName: "syncNotePreview"
                            Layout.fillWidth: true
                            text: noteRow.summary
                            color: root.theme.textSecondary
                            font.pixelSize: root.theme.smallSize
                            elide: Text.ElideRight
                            visible: text.length > 0
                        }
                    }
                    Rectangle {
                        Layout.alignment: Qt.AlignVCenter
                        implicitHeight: 25
                        implicitWidth: badgeText.implicitWidth + 17
                        radius: 12
                        color: root.theme.isLight ? root.theme.sidebar : root.theme.surfaceHover
                        border.color: root.theme.border
                        Label {
                            id: badgeText
                            objectName: "syncStatusText"
                            anchors.centerIn: parent
                            text: root.shortStatus(noteRow.deliveryStatus)
                            color: root.statusColor(noteRow.deliveryStatus)
                            font.pixelSize: 11
                            font.weight: Font.Medium
                        }
                    }
                }

                background: Rectangle {
                    color: noteRow.hovered || noteRow.checked
                        ? root.theme.surfaceHover : root.theme.mainBackground
                    border.color: noteRow.activeFocus ? root.theme.accent : "transparent"
                    border.width: 1
                    radius: root.theme.radiusSmall
                }
            }

            ScrollBar.vertical: ScrollBar {
                policy: ScrollBar.AsNeeded
            }
        }

        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: root.selectionMode === "all"
            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                implicitHeight: 106
                color: root.theme.mainBackground
                radius: root.theme.radiusSmall
                Column {
                    anchors.centerIn: parent
                    spacing: 7
                    Label {
                        text: root.i18n.t("All notes")
                        color: root.theme.textPrimary
                        font.pixelSize: root.theme.sidebarTitleSize
                        font.weight: Font.DemiBold
                        anchors.horizontalCenter: parent.horizontalCenter
                    }
                    Label {
                        text: root.i18n.t("All notes will be requested for transfer.")
                        color: root.theme.textSecondary
                        font.pixelSize: root.theme.smallSize
                        anchors.horizontalCenter: parent.horizontalCenter
                    }
                }
            }
        }
    }

    footer: ColumnLayout {
        spacing: 10
        Item {
            Layout.fillWidth: true
            implicitHeight: 1
            Rectangle {
                anchors.fill: parent
                anchors.leftMargin: 20
                anchors.rightMargin: 20
                color: root.theme.border
            }
        }
        Label {
            Layout.fillWidth: true
            Layout.leftMargin: 20
            Layout.rightMargin: 20
            text: root.i18n.t("Requests are confirmed after receipt import.")
            color: root.theme.textSecondary
            font.pixelSize: root.theme.smallSize
            wrapMode: Text.Wrap
        }
        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 20
            Layout.rightMargin: 20
            Layout.bottomMargin: 17
            spacing: 12
            Button {
                objectName: "cancelSyncButton"
                text: root.i18n.t("Cancel")
                implicitWidth: 106
                implicitHeight: 46
                onClicked: root.close()
                contentItem: Label {
                    text: parent.text
                    color: root.theme.textPrimary
                    font.pixelSize: root.theme.secondarySize
                    verticalAlignment: Text.AlignVCenter
                    horizontalAlignment: Text.AlignHCenter
                }
                background: Rectangle {
                    color: parent.hovered ? root.theme.surfaceHover : "transparent"
                    border.color: parent.activeFocus ? root.theme.accent : root.theme.border
                    radius: root.theme.radiusSmall
                }
            }
            Item { Layout.fillWidth: true }
            Button {
                id: startSyncButton
                objectName: "startSyncButton"
                text: root.i18n.t("Start Sync")
                implicitWidth: 150
                implicitHeight: 46
                enabled: root.selectionMode === "all" || root.presenter.syncSelectionCount > 0
                onClicked: {
                    const started = root.selectionMode === "all"
                        ? root.presenter.syncAll()
                        : root.presenter.syncSelected()
                    if (started)
                        root.close()
                }
                contentItem: Label {
                    text: parent.text
                    color: root.theme.accentText
                    opacity: parent.enabled ? 1 : 0.5
                    font.pixelSize: root.theme.secondarySize
                    font.weight: Font.DemiBold
                    verticalAlignment: Text.AlignVCenter
                    horizontalAlignment: Text.AlignHCenter
                }
                background: Rectangle {
                    color: parent.enabled
                        ? (parent.hovered ? Qt.lighter(root.theme.accent, 1.08) : root.theme.accent)
                        : root.theme.border
                    border.color: parent.activeFocus ? root.theme.textPrimary : "transparent"
                    radius: root.theme.radiusSmall
                }
            }
        }
    }
}
