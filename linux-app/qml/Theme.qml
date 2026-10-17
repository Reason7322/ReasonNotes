import QtQuick

QtObject {
    objectName: "theme"
    property string mode: "dark"
    readonly property bool isLight: mode === "light"
    readonly property bool isGlass: mode === "glass"
    readonly property bool isBlurredGlass: isGlass && blurState.active

    // Read-only live Kvantum colors via the Noctalia-generated palette.
    // Qt Quick does not render QWidget/Kvantum SVG assets directly.
    function frosted(color, opacity) {
        return Qt.rgba(color.r, color.g, color.b, opacity)
    }
    // Edge reflection is confined to a small top strip. Do not interpolate
    // translucent colors across an entire full-screen pane.
    readonly property color glassTop: frosted(glassPalette.textColor, 0.075)
    readonly property color glassBottom: frosted(glassPalette.textColor, 0.0)
    readonly property color glassHighlight: frosted(glassPalette.accentColor, 0.14)

    // Qt Quick does not paint Kvantum's QWidget SVG elements. Reconstruct
    // Dolphin's spatial glass material from the ACTIVE LiquidGlass palette,
    // calibrated against the user's real floating Dolphin reference:
    // cool, gently luminous top; navy blue-gray side panel; smoky base.
    // The tint is recomputed if Noctalia regenerates the Kvantum palette.
    // The Kvantum window-normal SVG carries a 0.72 alpha: preserve that
    // optical translucency instead of hiding the compositor blur under
    // almost-opaque paint. Fall back to solid color when blur is unavailable.
    readonly property real dolphinGlassOpacity: 0.72

    function material(color, red, green, blue, opacity) {
        return Qt.rgba(
            Math.max(0, Math.min(1, color.r + red)),
            Math.max(0, Math.min(1, color.g + green)),
            Math.max(0, Math.min(1, color.b + blue)),
            opacity
        )
    }

    readonly property color dolphinSidebarTop: material(glassPalette.lightColor, 0.028, 0.043, 0.075, dolphinGlassOpacity)
    readonly property color dolphinSidebarUpper: material(glassPalette.buttonColor, -0.043, -0.025, 0.018, dolphinGlassOpacity)
    readonly property color dolphinSidebarMiddle: material(glassPalette.windowColor, 0.0, 0.018, 0.055, dolphinGlassOpacity)
    readonly property color dolphinSidebarInterlude: material(glassPalette.windowColor, 0.016, 0.031, 0.075, dolphinGlassOpacity)
    readonly property color dolphinSidebarLower: material(glassPalette.windowColor, -0.034, -0.037, -0.028, dolphinGlassOpacity)
    readonly property color dolphinSidebarDeep: material(glassPalette.windowColor, -0.020, -0.020, -0.008, dolphinGlassOpacity)
    readonly property color dolphinSidebarBottom: material(glassPalette.windowColor, 0.015, 0.020, 0.055, dolphinGlassOpacity)

    readonly property color dolphinEditorTop: material(glassPalette.lightColor, -0.018, 0.004, 0.028, dolphinGlassOpacity)
    readonly property color dolphinEditorMiddle: material(glassPalette.buttonColor, 0.007, 0.018, 0.028, dolphinGlassOpacity)
    readonly property color dolphinEditorBottom: material(glassPalette.baseColor, -0.002, 0.004, 0.012, dolphinGlassOpacity)

    // Ordinary Dark and Light and the no-native-blur fallback are unchanged.
    readonly property color mainBackground: isGlass ? (isBlurredGlass ? dolphinEditorBottom : frosted(glassPalette.windowColor, 1.0)) : (isLight ? "#FAFAFC" : "#15171C")
    readonly property color sidebar: isGlass ? (isBlurredGlass ? dolphinSidebarMiddle : frosted(glassPalette.buttonColor, 1.0)) : (isLight ? "#EFF1F5" : "#1D2026")
    readonly property color surface: isGlass ? (isBlurredGlass ? material(glassPalette.baseColor, 0.0, 0.014, 0.022, dolphinGlassOpacity) : frosted(glassPalette.baseColor, 1.0)) : (isLight ? "#FFFFFF" : "#262931")
    readonly property color surfaceHover: isGlass ? (isBlurredGlass ? material(glassPalette.lightColor, 0.0, 0.010, 0.020, 0.85) : frosted(glassPalette.lightColor, 0.95)) : (isLight ? "#E3E8EF" : "#2C3038")
    readonly property color textPrimary: isGlass ? glassPalette.textColor : (isLight ? "#1D2430" : "#ECEEF3")
    readonly property color textSecondary: isGlass ? frosted(glassPalette.textColor, 0.72) : (isLight ? "#596476" : "#A2A7B2")
    readonly property color border: isGlass ? frosted(glassPalette.midColor, isBlurredGlass ? 0.37 : 0.56) : (isLight ? "#D5DAE3" : "#353941")
    readonly property color accent: isGlass ? glassPalette.accentColor : (isLight ? "#DCAF48" : "#DCAF48")
    readonly property color accentText: isGlass ? glassPalette.accentTextColor : (isLight ? "#17191E" : "#17191E")
    readonly property color selectedNote: isGlass ? frosted(glassPalette.accentColor, 0.21) : (isLight ? "#F4E8CB" : "#302C26")
    readonly property color error: isGlass ? "#E58B8B" : (isLight ? "#AC3232" : "#E58B8B")
    readonly property color warning: isGlass ? glassPalette.accentColor : (isLight ? "#8E570C" : "#DCAF48")
    readonly property color success: isGlass ? "#71c9a4" : (isLight ? "#277349" : "#55D68B")

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
