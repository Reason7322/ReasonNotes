import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: root
    required property var presenter
    required property var theme

    color: theme.editor

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: root.theme.spacingLarge
        spacing: root.theme.spacing

        Label {
            Layout.fillWidth: true
            text: qsTr("This note has conflicting revisions")
            color: root.theme.textPrimary
            font.pixelSize: root.theme.headingSize
            font.bold: true
        }

        Label {
            Layout.fillWidth: true
            text: qsTr("Choose an exact revision to keep. Superseded revisions remain recoverable from immutable history.")
            color: root.theme.textSecondary
            wrapMode: Text.Wrap
        }

        ListView {
            id: conflictList
            Layout.fillWidth: true
            Layout.fillHeight: true
            orientation: ListView.Horizontal
            spacing: root.theme.spacing
            clip: true
            model: root.presenter.conflictModel

            delegate: Rectangle {
                required property string revisionId
                required property string originDevice
                required property bool deleted
                required property string body

                width: Math.max(320, (conflictList.width - root.theme.spacing) / 2)
                height: conflictList.height
                color: root.theme.sidebar
                border.color: root.theme.border
                radius: root.theme.radius

                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: root.theme.spacing
                    spacing: root.theme.spacing

                    Label {
                        Layout.fillWidth: true
                        text: qsTr("%1 · %2").arg(originDevice).arg(revisionId.slice(0, 10))
                        color: root.theme.textPrimary
                        font.bold: true
                        elide: Text.ElideRight
                    }

                    ScrollView {
                        Layout.fillWidth: true
                        Layout.fillHeight: true

                        TextArea {
                            readOnly: true
                            text: deleted ? qsTr("[Deleted note]") : body
                            color: deleted ? root.theme.warning : root.theme.textPrimary
                            background: null
                            wrapMode: TextEdit.Wrap
                            selectByMouse: true
                            font.family: "monospace"
                        }
                    }

                    Button {
                        Layout.fillWidth: true
                        text: originDevice === "linux"
                            ? qsTr("Keep Linux revision")
                            : qsTr("Keep Android revision")
                        enabled: originDevice === "linux" || originDevice === "android"
                        onClicked: root.presenter.resolveConflict(
                            originDevice === "linux" ? "keep-linux" : "keep-android",
                            revisionId
                        )
                    }
                }
            }

            ScrollBar.horizontal: ScrollBar {}
        }

        Button {
            text: qsTr("Skip for now")
            onClicked: root.presenter.skipConflict()
        }
    }
}
