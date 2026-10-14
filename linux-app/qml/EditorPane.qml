import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: root
    required property var presenter
    required property var theme
    property bool applyingBody: false

    color: theme.mainBackground

    function displayTitle(body) {
        const lines = body.split("\n")
        for (let index = 0; index < lines.length; ++index) {
            const value = lines[index].trim()
            if (value.length === 0)
                continue
            const title = value.replace(/^#+\s*/, "").trim()
            return title.length > 0 ? title : qsTr("Untitled")
        }
        return qsTr("Untitled")
    }

    Item {
        id: emptyState
        objectName: "emptyState"
        property string accessibleDescription: qsTr("Create a new note or select one from the sidebar")
        anchors.fill: parent
        visible: root.presenter.activeNoteId === ""

        Column {
            anchors.centerIn: parent
            width: Math.min(460, parent.width - root.theme.spacingXXLarge * 2)
            spacing: root.theme.spacing

            Label {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                text: qsTr("A quiet place for your thoughts")
                color: root.theme.textPrimary
                font.family: root.theme.fontFamily
                font.pixelSize: 30
                font.weight: Font.DemiBold
                wrapMode: Text.Wrap
            }

            Label {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                text: qsTr("Create a new note or choose one from the sidebar to begin writing in Markdown.")
                color: root.theme.textSecondary
                font.family: root.theme.fontFamily
                font.pixelSize: root.theme.editorBodySize
                lineHeightMode: Text.ProportionalHeight
                lineHeight: 1.4
                wrapMode: Text.Wrap
            }

            Button {
                anchors.horizontalCenter: parent.horizontalCenter
                implicitWidth: 156
                implicitHeight: root.theme.primaryButtonHeight
                text: qsTr("Create a note")
                onClicked: root.presenter.createNote()
                contentItem: Label {
                    text: parent.text
                    color: root.theme.accentText
                    font.pixelSize: root.theme.secondarySize
                    font.weight: Font.DemiBold
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
                background: Rectangle {
                    color: parent.hovered ? Qt.lighter(root.theme.accent, 1.08) : root.theme.accent
                    border.color: parent.activeFocus ? root.theme.textPrimary : "transparent"
                    border.width: parent.activeFocus ? 2 : 0
                    radius: root.theme.radiusSmall
                }
            }
        }
    }

    Item {
        id: editorContent
        objectName: "editorContent"
        anchors.fill: parent
        visible: root.presenter.activeNoteId !== ""

        Item {
            id: readingColumn
            objectName: "readingColumn"
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            anchors.horizontalCenter: parent.horizontalCenter
            width: Math.min(root.theme.readingWidth, Math.max(0, parent.width - root.theme.spacingXLarge * 2))

            ColumnLayout {
                anchors.fill: parent
                anchors.topMargin: root.theme.spacingLarge
                anchors.bottomMargin: root.theme.spacingLarge
                spacing: 0

                RowLayout {
                    id: editorStatusBar
                    objectName: "editorStatusBar"
                    Layout.fillWidth: true
                    implicitHeight: 40
                    spacing: root.theme.spacing

                    Label { text: qsTr("Markdown"); color: root.theme.textSecondary; font.pixelSize: root.theme.smallSize }
                    Item { Layout.fillWidth: true }
                    Rectangle {
                        width: 8
                        height: 8
                        radius: 4
                        color: root.presenter.saveState === "Error"
                            ? root.theme.error
                            : (root.presenter.saveState === "Saved" ? root.theme.success : root.theme.accent)
                    }
                    Label {
                        text: qsTr("Local: %1").arg(root.presenter.saveState)
                        color: root.presenter.saveState === "Error" ? root.theme.error : root.theme.textSecondary
                        font.pixelSize: root.theme.smallSize
                    }
                    Rectangle { Layout.leftMargin: root.theme.spacingSmall; width: 1; height: 16; color: root.theme.border }
                    Label {
                        text: qsTr("Phone: %1").arg(root.presenter.activeDeliveryStatus)
                        color: root.presenter.activeDeliveryStatus === "Confirmed" ? root.theme.success : root.theme.textSecondary
                        font.pixelSize: root.theme.smallSize
                    }
                }

                Label {
                    id: currentNoteHeading
                    objectName: "currentNoteHeading"
                    Layout.fillWidth: true
                    Layout.topMargin: root.theme.spacingLarge
                    Layout.bottomMargin: root.theme.spacing
                    text: root.displayTitle(editor.text)
                    color: root.theme.textPrimary
                    font.family: root.theme.fontFamily
                    font.pixelSize: root.theme.noteHeadingSize
                    font.weight: Font.DemiBold
                    elide: Text.ElideRight
                }

                Rectangle { Layout.fillWidth: true; Layout.bottomMargin: root.theme.spacingLarge; implicitHeight: 1; color: root.theme.border }

                ScrollView {
                    id: editorScrollView
                    objectName: "editorScrollView"
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    clip: true
                    ScrollBar.vertical.policy: ScrollBar.AsNeeded

                    TextArea {
                        id: editor
                        objectName: "markdownEditor"
                        width: editorScrollView.availableWidth
                        leftPadding: 0
                        rightPadding: root.theme.spacing
                        topPadding: 0
                        bottomPadding: root.theme.spacingXXLarge
                        readOnly: !root.presenter.canEdit
                        color: root.theme.textPrimary
                        selectionColor: root.theme.accent
                        selectedTextColor: root.theme.accentText
                        placeholderText: qsTr("Write Markdown…")
                        placeholderTextColor: root.theme.textSecondary
                        font.family: root.theme.fontFamily
                        font.pixelSize: root.theme.editorBodySize
                        wrapMode: TextEdit.Wrap
                        selectByMouse: true
                        persistentSelection: true
                        background: null

                        onTextChanged: {
                            if (!root.applyingBody && activeFocus && !readOnly) {
                                root.presenter.editDraft(text)
                                autosaveTimer.restart()
                            }
                        }
                    }
                }
            }
        }
    }

    Timer {
        id: autosaveTimer
        interval: 500
        repeat: false
        onTriggered: root.presenter.autosave()
    }

    Connections {
        target: root.presenter

        function onCurrentBodyChanged() {
            if (editor.text === root.presenter.currentBody)
                return
            root.applyingBody = true
            editor.text = root.presenter.currentBody
            root.applyingBody = false
        }
    }

    Component.onCompleted: {
        root.applyingBody = true
        editor.text = root.presenter.currentBody
        root.applyingBody = false
    }

    function focusEditor() {
        editor.forceActiveFocus()
    }
}
