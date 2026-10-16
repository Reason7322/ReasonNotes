import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: root
    objectName: "settingsPane"
    required property var theme
    required property var i18n
    required property var preferences
    signal backRequested()

    color: root.theme.mainBackground
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

    ScrollView {
        anchors.fill: parent
        clip: true
        ScrollBar.horizontal.policy: ScrollBar.AlwaysOff

        Item {
            width: Math.max(0, root.width)
            implicitHeight: content.implicitHeight + root.theme.spacingXXLarge * 2

            ColumnLayout {
                id: content
                anchors.top: parent.top
                anchors.topMargin: root.theme.spacingXXLarge
                anchors.horizontalCenter: parent.horizontalCenter
                width: Math.min(680, Math.max(0, parent.width - root.theme.spacingXLarge * 2))
                spacing: root.theme.spacingLarge

                Button {
                    objectName: "backToNotesButton"
                    text: root.i18n.t("Back to notes")
                    implicitHeight: 40
                    onClicked: root.backRequested()
                    contentItem: Label {
                        text: parent.text
                        color: root.theme.textSecondary
                        verticalAlignment: Text.AlignVCenter
                        font.pixelSize: root.theme.secondarySize
                    }
                    background: Rectangle {
                        color: parent.hovered ? root.theme.surfaceHover : "transparent"
                        radius: root.theme.radiusSmall
                    }
                }

                Label {
                    Layout.fillWidth: true
                    text: root.i18n.t("Settings")
                    color: root.theme.textPrimary
                    font.family: root.theme.fontFamily
                    font.pixelSize: root.theme.noteHeadingSize
                    font.weight: Font.DemiBold
                }

                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: 1
                    color: root.theme.border
                }

                Label {
                    Layout.fillWidth: true
                    text: root.i18n.t("Appearance")
                    color: root.theme.textPrimary
                    font.pixelSize: 22
                    font.weight: Font.DemiBold
                }

                Label {
                    Layout.fillWidth: true
                    text: root.i18n.t("Choose the appearance of ReasonNotes.")
                    color: root.theme.textSecondary
                    font.pixelSize: root.theme.secondarySize
                }

                RowLayout {
                    Layout.fillWidth: true
                    spacing: root.theme.spacing

                    Button {
                        id: darkThemeButton
                        objectName: "darkThemeButton"
                        Layout.fillWidth: true
                        Layout.preferredWidth: 1
                        implicitHeight: 52
                        checkable: true
                        checked: root.preferences.themeMode === "dark"
                        text: root.i18n.t("Dark")
                        onClicked: root.preferences.themeMode = "dark"
                        contentItem: Label {
                            text: parent.text
                            color: parent.checked ? root.theme.accentText : root.theme.textPrimary
                            font.pixelSize: root.theme.sidebarTitleSize
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                        background: Rectangle {
                            color: darkThemeButton.checked ? root.theme.accent : root.theme.surface
                            border.color: darkThemeButton.activeFocus ? root.theme.textPrimary : root.theme.border
                            radius: root.theme.radius
                        }
                    }

                    Button {
                        id: lightThemeButton
                        objectName: "lightThemeButton"
                        Layout.fillWidth: true
                        Layout.preferredWidth: 1
                        implicitHeight: 52
                        checkable: true
                        checked: root.preferences.themeMode === "light"
                        text: root.i18n.t("Light")
                        onClicked: root.preferences.themeMode = "light"
                        contentItem: Label {
                            text: parent.text
                            color: parent.checked ? root.theme.accentText : root.theme.textPrimary
                            font.pixelSize: root.theme.sidebarTitleSize
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                        background: Rectangle {
                            color: lightThemeButton.checked ? root.theme.accent : root.theme.surface
                            border.color: lightThemeButton.activeFocus ? root.theme.textPrimary : root.theme.border
                            radius: root.theme.radius
                        }
                    }

                    Button {
                        id: glassThemeButton
                        objectName: "glassThemeButton"
                        Layout.fillWidth: true
                        Layout.preferredWidth: 1
                        implicitHeight: 52
                        checkable: true
                        checked: root.preferences.themeMode === "glass"
                        text: "Liquid Glass"
                        onClicked: root.preferences.themeMode = "glass"
                        contentItem: Label {
                            text: parent.text
                            color: parent.checked ? root.theme.accentText : root.theme.textPrimary
                            font.pixelSize: root.theme.sidebarTitleSize
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                        background: Rectangle {
                            color: glassThemeButton.checked ? root.theme.accent : root.theme.surface
                            border.color: glassThemeButton.activeFocus ? root.theme.textPrimary : root.theme.border
                            radius: root.theme.radius
                        }
                        }
                }

                Rectangle {
                    Layout.fillWidth: true
                    Layout.topMargin: root.theme.spacingSmall
                    implicitHeight: 1
                    color: root.theme.border
                }

                Label {
                    Layout.fillWidth: true
                    text: root.i18n.t("Language")
                    color: root.theme.textPrimary
                    font.pixelSize: 22
                    font.weight: Font.DemiBold
                }

                Label {
                    Layout.fillWidth: true
                    text: root.i18n.t("Choose the language used in menus and controls.")
                    color: root.theme.textSecondary
                    font.pixelSize: root.theme.secondarySize
                }

                RowLayout {
                    Layout.fillWidth: true
                    spacing: root.theme.spacing

                    Button {
                        id: englishLanguageButton
                        objectName: "englishLanguageButton"
                        Layout.fillWidth: true
                        Layout.preferredWidth: 1
                        implicitHeight: 52
                        checkable: true
                        checked: root.preferences.language === "en"
                        text: "English"
                        onClicked: root.preferences.language = "en"
                        contentItem: Label {
                            text: parent.text
                            color: parent.checked ? root.theme.accentText : root.theme.textPrimary
                            font.pixelSize: root.theme.sidebarTitleSize
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                        background: Rectangle {
                            color: englishLanguageButton.checked ? root.theme.accent : root.theme.surface
                            border.color: englishLanguageButton.activeFocus ? root.theme.textPrimary : root.theme.border
                            radius: root.theme.radius
                        }
                    }

                    Button {
                        id: polishLanguageButton
                        objectName: "polishLanguageButton"
                        Layout.fillWidth: true
                        Layout.preferredWidth: 1
                        implicitHeight: 52
                        checkable: true
                        checked: root.preferences.language === "pl"
                        text: "Polski"
                        onClicked: root.preferences.language = "pl"
                        contentItem: Label {
                            text: parent.text
                            color: parent.checked ? root.theme.accentText : root.theme.textPrimary
                            font.pixelSize: root.theme.sidebarTitleSize
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                        background: Rectangle {
                            color: polishLanguageButton.checked ? root.theme.accent : root.theme.surface
                            border.color: polishLanguageButton.activeFocus ? root.theme.textPrimary : root.theme.border
                            radius: root.theme.radius
                        }
                    }
                }

                Label {
                    Layout.fillWidth: true
                    Layout.topMargin: root.theme.spacing
                    text: root.i18n.t("Settings are saved automatically.")
                    color: root.theme.textSecondary
                    font.pixelSize: root.theme.smallSize
                }

                Label {
                    Layout.fillWidth: true
                    text: root.i18n.t("Your notes stay in their original language.")
                    color: root.theme.textSecondary
                    font.pixelSize: root.theme.smallSize
                    wrapMode: Text.Wrap
                }
            }
        }
    }
}
