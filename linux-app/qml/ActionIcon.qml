import QtQuick
import QtQuick.Window

// Official Lucide paths, displayed via Qt SVG at the current screen density.
// Colored SVG variants avoid a secondary shader pass and preserve thin edges.
Item {
    id: root
    property string kind: "save"
    property string visualState: "normal" // normal, hover, disabled

    implicitWidth: 24
    implicitHeight: 24
    readonly property bool assetReady: lucideSvg.status === Image.Ready
    readonly property url assetSource: lucideSvg.source

    Image {
        id: lucideSvg
        objectName: "lucideSvg"
        anchors.fill: parent
        source: {
            const icon = root.kind === "sync" ? "refresh-cw"
                : root.kind === "delete" ? "trash" : "save"
            const variant = root.visualState === "hover"
                ? (root.kind === "delete" ? "-danger" : "-gold") : ""
            return "icons/lucide/themed/" + icon + variant + ".svg"
        }
        sourceSize.width: Math.ceil(24 * Math.max(1, Screen.devicePixelRatio))
        sourceSize.height: Math.ceil(24 * Math.max(1, Screen.devicePixelRatio))
        fillMode: Image.PreserveAspectFit
        smooth: true
        opacity: root.visualState === "disabled" ? 0.30 : 1.0
    }
}
