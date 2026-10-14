import QtQuick
import QtQuick.Shapes

// Single 24px icon geometry for the sidebar's Save, Sync and Delete actions.
// Unlike font glyphs, its line weight and placement do not depend on fonts.
Item {
    id: root
    property string kind: "save"
    property color iconColor: "#A2A7B2"

    implicitWidth: 24
    implicitHeight: 24

    Shape {
        anchors.centerIn: parent
        width: 24
        height: 24
        antialiasing: true

        ShapePath {
            fillColor: "transparent"
            strokeColor: root.iconColor
            strokeWidth: 1.9
            capStyle: ShapePath.RoundCap
            joinStyle: ShapePath.RoundJoin
            PathSvg {
                path: {
                    if (root.kind === "save")
                        return "M5 3 H16 L21 8 V21 H3 V3 Z M7 3 V9 H16 V3 M7 21 V13 H17 V21"
                    if (root.kind === "sync")
                        return "M20 11 A8 8 0 0 0 5 7 M4 4 V9 H9 M4 13 A8 8 0 0 0 19 17 M20 20 V15 H15"
                    if (root.kind === "delete")
                        return "M4 7 H20 M9 7 V4 H15 V7 M6 7 L7 21 H17 L18 7 M10 11 V17 M14 11 V17"
                    return ""
                }
            }
        }
    }
}
