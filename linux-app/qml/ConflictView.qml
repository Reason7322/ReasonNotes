import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: root
    required property var presenter
    required property var theme
    required property var i18n

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

    ColumnLayout {
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.horizontalCenter: parent.horizontalCenter
        width: Math.min(1180, Math.max(0, parent.width - root.theme.spacingXLarge * 2))
        anchors.topMargin: root.theme.spacingLarge
        anchors.bottomMargin: root.theme.spacingLarge
        spacing: root.theme.spacing

        Label {
            id: conflictHeading
            objectName: "conflictHeading"
            Layout.fillWidth: true
            text: root.i18n.t("Choose the version to keep")
            color: root.theme.textPrimary
            font.family: root.theme.fontFamily
            font.pixelSize: root.theme.noteHeadingSize
            font.weight: Font.DemiBold
        }

        Label {
            Layout.fillWidth: true
            text: root.i18n.t("This note changed independently on more than one device. Review each exact revision before deciding.")
            color: root.theme.textSecondary
            font.pixelSize: root.theme.secondarySize
            wrapMode: Text.Wrap
        }

        Rectangle {
            Layout.fillWidth: true
            implicitHeight: 1
            Layout.topMargin: root.theme.spacing
            Layout.bottomMargin: root.theme.spacing
            color: root.theme.border
        }

        ListView {
            id: conflictList
            objectName: "conflictList"
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

                width: Math.max(340, Math.min(570, (conflictList.width - root.theme.spacing) / 2))
                height: conflictList.height
                color: root.theme.surface
                border.color: root.theme.border
                radius: root.theme.radius

                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: root.theme.spacingLarge
                    spacing: root.theme.spacing

                    Label {
                        Layout.fillWidth: true
                        text: originDevice === "linux" ? root.i18n.t("Linux version") : root.i18n.t("Android version")
                        color: root.theme.textPrimary
                        font.pixelSize: 20
                        font.weight: Font.DemiBold
                    }

                    Label {
                        Layout.fillWidth: true
                        text: root.i18n.t("Revision %1").arg(revisionId.slice(0, 12))
                        color: root.theme.textSecondary
                        font.pixelSize: root.theme.smallSize
                        elide: Text.ElideRight
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        implicitHeight: 1
                        color: root.theme.border
                    }

                    ScrollView {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        clip: true

                        TextArea {
                            readOnly: true
                            text: deleted ? root.i18n.t("[Deleted note]") : body
                            color: deleted ? root.theme.warning : root.theme.textPrimary
                            background: null
                            wrapMode: TextEdit.Wrap
                            selectByMouse: true
                            font.family: "monospace"
                            font.pixelSize: 16
                            leftPadding: 0
                            rightPadding: root.theme.spacing
                        }
                    }

                    Button {
                        Layout.fillWidth: true
                        implicitHeight: 46
                        text: originDevice === "linux"
                            ? root.i18n.t("Keep Linux revision")
                            : root.i18n.t("Keep Android revision")
                        enabled: originDevice === "linux" || originDevice === "android"
                        onClicked: root.presenter.resolveConflict(
                            originDevice === "linux" ? "keep-linux" : "keep-android",
                            revisionId
                        )
                        contentItem: Label { text: parent.text; color: root.theme.accentText; opacity: parent.enabled ? 1 : 0.5; font.pixelSize: root.theme.secondarySize; font.weight: Font.DemiBold; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                        background: Rectangle { color: parent.enabled ? (parent.hovered ? Qt.lighter(root.theme.accent, 1.08) : root.theme.accent) : root.theme.border; border.color: parent.activeFocus ? root.theme.textPrimary : "transparent"; border.width: parent.activeFocus ? 2 : 0; radius: root.theme.radiusSmall }
                    }
                }
            }

            ScrollBar.horizontal: ScrollBar {}
        }

        Label {
            id: conflictHistoryNote
            objectName: "conflictHistoryNote"
            Layout.fillWidth: true
            text: root.i18n.t("Superseded revisions remain recoverable from immutable history.")
            color: root.theme.textSecondary
            font.pixelSize: root.theme.smallSize
            wrapMode: Text.Wrap
        }

        Button {
            text: root.i18n.t("Skip for now")
            implicitWidth: 124
            implicitHeight: 42
            onClicked: root.presenter.skipConflict()
            contentItem: Label { text: parent.text; color: root.theme.textPrimary; font.pixelSize: root.theme.secondarySize; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
            background: Rectangle { color: parent.hovered ? root.theme.surfaceHover : "transparent"; border.color: parent.activeFocus ? root.theme.accent : root.theme.border; radius: root.theme.radiusSmall }
        }
    }
}
