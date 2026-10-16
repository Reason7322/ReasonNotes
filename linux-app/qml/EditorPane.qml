import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: root
    required property var presenter
    required property var theme
    required property var i18n
    property bool applyingBody: false
    // Only reposition on a new note, never for edits or background saves.
    property string loadedNoteId: ""
    property bool scrollToEndPending: false

    color: theme.mainBackground
    // Kitty uses an edge-lighting effect, not a full-surface striped
    // gradient. The main reading surface remains uniform and fully opaque.
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


    Item {
        id: emptyState
        objectName: "emptyState"
        property string accessibleDescription: root.i18n.t("Create a new note or select one from the sidebar")
        anchors.fill: parent
        visible: root.presenter.activeNoteId === ""

        Column {
            anchors.centerIn: parent
            width: Math.min(460, parent.width - root.theme.spacingXXLarge * 2)
            spacing: root.theme.spacing

            Label {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                text: root.i18n.t("A quiet place for your thoughts")
                color: root.theme.textPrimary
                font.family: root.theme.fontFamily
                font.pixelSize: 30
                font.weight: Font.DemiBold
                wrapMode: Text.Wrap
            }

            Label {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                text: root.i18n.t("Create a new note or choose one from the sidebar to begin writing in Markdown.")
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
                text: root.i18n.t("Create a note")
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
                        text: root.i18n.t("Local: %1").arg(root.i18n.status(root.presenter.saveState))
                        color: root.presenter.saveState === "Error" ? root.theme.error : root.theme.textSecondary
                        font.pixelSize: root.theme.smallSize
                    }
                    Rectangle { Layout.leftMargin: root.theme.spacingSmall; width: 1; height: 16; color: root.theme.border }
                    Label {
                        text: root.i18n.t("Phone: %1").arg(root.i18n.status(root.presenter.activeDeliveryStatus))
                        color: root.presenter.activeDeliveryStatus === "Confirmed" ? root.theme.success : root.theme.textSecondary
                        font.pixelSize: root.theme.smallSize
                    }
                }

                TextField {
                    id: currentNoteHeading
                    objectName: "currentNoteHeading"
                    Layout.fillWidth: true
                    Layout.topMargin: root.theme.spacingLarge
                    Layout.bottomMargin: root.theme.spacing
                    text: ""
                    placeholderText: root.i18n.t("Untitled")
                    placeholderTextColor: root.theme.textSecondary
                    color: root.theme.textPrimary
                    font.family: root.theme.fontFamily
                    font.pixelSize: root.theme.noteHeadingSize
                    font.weight: Font.DemiBold
                    readOnly: !root.presenter.canEdit
                    selectByMouse: true
                    leftPadding: 0
                    rightPadding: 0
                    background: null
                    onTextEdited: {
                        if (!root.applyingBody && !readOnly) {
                            root.presenter.editTitle(text)
                            autosaveTimer.restart()
                        }
                    }
                    onEditingFinished: {
                        if (text.trim().length === 0 && root.presenter.activeNoteId !== "")
                            text = root.presenter.currentTitle
                    }
                }

                Rectangle { Layout.fillWidth: true; Layout.bottomMargin: root.theme.spacingLarge; implicitHeight: 1; color: root.theme.border }

                ScrollView {
                    id: editorScrollView
                    objectName: "editorScrollView"
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    clip: true
                    ScrollBar.vertical.policy: ScrollBar.AsNeeded

                    // A wrapped TextArea can finish calculating height after
                    // the note-selection signal. Only honor pending loads.
                    onContentHeightChanged: {
                        if (root.scrollToEndPending)
                            Qt.callLater(root.finishOpeningNoteAtEnd)
                    }

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
                        font.family: root.theme.fontFamily
                        font.pixelSize: root.theme.editorBodySize
                        wrapMode: TextEdit.Wrap
                        selectByMouse: true
                        persistentSelection: true
                        background: null

                        onTextChanged: {
                            if (!root.applyingBody && activeFocus && !readOnly) {
                                root.presenter.editContent(text)
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

    function finishOpeningNoteAtEnd() {
        if (!root.scrollToEndPending)
            return
        const flickable = editorScrollView.contentItem
        if (!flickable || flickable.height <= 0)
            return
        const maximum = Math.max(0, flickable.contentHeight - flickable.height)
        flickable.contentY = maximum
        root.scrollToEndPending = false
    }

    function loadDocumentFields() {
        const newNoteId = root.presenter.activeNoteId
        const switched = newNoteId !== root.loadedNoteId
        root.applyingBody = true
        if (currentNoteHeading.text !== root.presenter.currentTitle)
            currentNoteHeading.text = root.presenter.currentTitle
        if (editor.text !== root.presenter.currentContent)
            editor.text = root.presenter.currentContent
        root.applyingBody = false
        if (switched) {
            root.loadedNoteId = newNoteId
            root.scrollToEndPending = newNoteId !== ""
            if (root.scrollToEndPending)
                Qt.callLater(root.finishOpeningNoteAtEnd)
        }
        if (newNoteId !== ""
                && root.presenter.canEdit
                && root.presenter.currentTitle === "")
            currentNoteHeading.forceActiveFocus()
    }

    Connections {
        target: root.presenter

        function onCurrentBodyChanged() {
            root.loadDocumentFields()
        }
    }

    Component.onCompleted: root.loadDocumentFields()

    function focusEditor() {
        editor.forceActiveFocus()
    }
}
