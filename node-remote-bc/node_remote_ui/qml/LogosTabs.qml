import QtQuick
import QtQuick.Controls

// Logos-style tabs: text labels with a sliding underline, no button chrome.
//
// This mirrors the design system's LogosTabBar / LogosTabButton
// (refs/logos-design-system/src/qml/Logos/Controls/) rather than inventing a look —
// same 3px rounded indicator, same 200ms OutCubic slide, same active/inactive colours,
// same 40px row height. It is reimplemented in plain QtQuick because a sandboxed ui_qml
// plugin cannot `import Logos.Controls`, not because the original needed improving.
Item {
    id: bar

    property var    labels: []
    property int    currentIndex: 0
    property color  activeColor:   "#ED7B58"   // orange300 / Theme.palette.primary
    property color  inactiveColor: "#A4A4A4"   // gray400 / textTertiary
    property int    indicatorHeight: 3
    property int    animationDuration: 200
    property int    tabSpacing: 20             // Theme.spacing.large, as the 1-click view uses

    implicitHeight: 40
    implicitWidth: row.implicitWidth

    Row {
        id: row
        anchors.left: parent.left
        height: parent.height
        spacing: bar.tabSpacing

        Repeater {
            model: bar.labels
            delegate: Item {
                id: tab
                required property int index
                required property string modelData
                readonly property bool active: bar.currentIndex === index

                width: label.implicitWidth + 16
                height: bar.height

                Label {
                    id: label
                    anchors.centerIn: parent
                    text: tab.modelData
                    color: tab.active ? bar.activeColor : bar.inactiveColor
                    font.pixelSize: 13
                    font.weight: Font.Medium
                }

                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: bar.currentIndex = tab.index
                }
            }
        }
    }

    // The underline. Driven off the live geometry of the selected tab so it stays correct
    // when the labels or the font metrics change, rather than off a computed guess.
    Rectangle {
        id: indicator
        height: bar.indicatorHeight
        radius: height / 2
        color: bar.activeColor
        y: bar.height - height

        readonly property Item target: row.children.length > bar.currentIndex
                                       ? row.children[bar.currentIndex] : null
        x:     target ? target.x + row.x : 0
        width: target ? target.width : 0

        Behavior on x     { NumberAnimation { duration: bar.animationDuration; easing.type: Easing.OutCubic } }
        Behavior on width { NumberAnimation { duration: bar.animationDuration; easing.type: Easing.OutCubic } }
    }

    // Hairline under the whole bar, so the indicator reads as a selection on a rail
    // rather than a floating dash.
    Rectangle {
        anchors.left: parent.left
        anchors.right: parent.right
        y: bar.height - 1
        height: 1
        color: "#343434"                        // gray320
        z: -1
    }
}
