import QtQuick

QtObject {
    objectName: "theme"
    property string mode: "dark"
    readonly property bool isLight: mode === "light"

    readonly property color mainBackground: isLight ? "#FAFAFC" : "#15171C"
    readonly property color sidebar: isLight ? "#EFF1F5" : "#1D2026"
    readonly property color surface: isLight ? "#FFFFFF" : "#262931"
    readonly property color surfaceHover: isLight ? "#E3E8EF" : "#2C3038"
    readonly property color textPrimary: isLight ? "#1D2430" : "#ECEEF3"
    readonly property color textSecondary: isLight ? "#596476" : "#A2A7B2"
    readonly property color border: isLight ? "#D5DAE3" : "#353941"
    readonly property color accent: isLight ? "#DCAF48" : "#DCAF48"
    readonly property color accentText: isLight ? "#17191E" : "#17191E"
    readonly property color selectedNote: isLight ? "#F4E8CB" : "#302C26"
    readonly property color error: isLight ? "#AC3232" : "#E58B8B"
    readonly property color warning: isLight ? "#8E570C" : "#DCAF48"
    readonly property color success: isLight ? "#277349" : "#55D68B"

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
