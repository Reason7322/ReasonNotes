import QtQuick
import QtQuick.Controls

Rectangle {
    id: root
    required property var presenter
    required property var theme
    property bool applyingBody: false

    color: theme.editor

    ScrollView {
        id: editorScrollView
        objectName: "editorScrollView"
        anchors.fill: parent
        anchors.margins: root.theme.spacingLarge
        clip: true
        ScrollBar.vertical.policy: ScrollBar.AsNeeded

        TextArea {
            id: editor
            objectName: "markdownEditor"
            width: editorScrollView.availableWidth
            readOnly: !root.presenter.canEdit
            color: root.theme.textPrimary
            selectionColor: root.theme.selection
            selectedTextColor: root.theme.textPrimary
            placeholderText: root.presenter.activeNoteId === ""
                ? qsTr("Create or select a note")
                : qsTr("Write Markdown…")
            placeholderTextColor: root.theme.textSecondary
            font.family: "monospace"
            font.pixelSize: root.theme.bodySize
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
