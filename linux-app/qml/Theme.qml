import QtQuick

QtObject {
    objectName: "theme"

    readonly property color mainBackground: "#15171C"
    readonly property color sidebar: "#1D2026"
    readonly property color surface: "#262931"
    readonly property color surfaceHover: "#2C3038"
    readonly property color textPrimary: "#ECEEF3"
    readonly property color textSecondary: "#A2A7B2"
    readonly property color border: "#353941"
    readonly property color accent: "#DCAF48"
    readonly property color accentText: "#17191E"
    readonly property color selectedNote: "#302C26"
    readonly property color error: "#E58B8B"
    readonly property color warning: "#DCAF48"
    readonly property color success: "#55D68B"

    readonly property color editor: mainBackground
    readonly property color control: surface
    readonly property color controlHover: surfaceHover
    readonly property color selection: selectedNote

    readonly property string fontFamily: Qt.application.font.family
    readonly property int spacingTiny: 4
    readonly property int spacingSmall: 8
    readonly property int spacing: 12
    readonly property int spacingLarge: 24
    readonly property int spacingXLarge: 32
    readonly property int spacingXXLarge: 48
    readonly property int radius: 8
    readonly property int radiusSmall: 6
    readonly property int sidebarMinimum: 250
    readonly property int sidebarInitial: 300
    readonly property int readingWidth: 920
    readonly property int applicationTitleSize: 27
    readonly property int noteHeadingSize: 36
    readonly property int editorBodySize: 18
    readonly property int sidebarTitleSize: 16
    readonly property int secondarySize: 14
    readonly property int smallSize: 13
    readonly property int headingSize: noteHeadingSize
    readonly property int bodySize: editorBodySize
    readonly property int primaryButtonHeight: 50
    readonly property int actionButtonHeight: 42
    readonly property int fieldHeight: 46
}
