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
    // Dolphin-inspired Qt Quick rendering of the user's actual Kvantum
    // LiquidGlass material, without requiring QWidget or compositor changes.
    // Dark/Light and non-blurred glass are uniform, as before.
    gradient: Gradient {
        objectName: "dolphinEditorMaterial"
        GradientStop { position: 0.0; color: root.theme.isBlurredGlass ? root.theme.dolphinEditorTop : root.theme.mainBackground }
        GradientStop { position: 0.25; color: root.theme.isBlurredGlass ? root.theme.dolphinEditorMiddle : root.theme.mainBackground }
        GradientStop { position: 1.0; color: root.theme.mainBackground }
    }
    // Retain a restrained top-edge reflection above the Kvantum material.
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
        id: editorContent
        objectName: "editorContent"
        anchors.fill: parent
        // The new-note editor is ready immediately, without any history row
        // until the first meaningful draft is saved.
        visible: true

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
                    // Keep wheel, touchpad and keyboard scrolling for long
                    // Markdown documents without the distracting bright
                    // native scrollbar thumb at the viewport edge.
                    ScrollBar.vertical: ScrollBar {
                        objectName: "editorVerticalScrollBar"
                        policy: ScrollBar.AlwaysOff
                    }

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
        objectName: "autosaveTimer"
        // Coalesce ordinary typing into a single immutable revision after
        // a meaningful idle period. Explicit Save, switching, and closing
        // still flush dirty drafts immediately.
        interval: 2500
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
            // A successful switch has already flushed the old draft.
            autosaveTimer.stop()
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

    Component.onCompleted: {
        root.loadDocumentFields()
        // The window should accept typing without requiring an extra click.
        // Only do this for the blank startup document; never steal focus from
        // search, dialogs, or already-open notes.
        if (root.presenter.activeNoteId === "")
            Qt.callLater(root.focusEditor)
    }

    function focusEditor() {
        editor.forceActiveFocus()
    }
}
