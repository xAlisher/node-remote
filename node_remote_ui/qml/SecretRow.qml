import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// A labelled, read-only, selectable value with a Copy button.
//
// Used for the pairing URI and token. Read-only rather than a plain Label so the text can
// be selected and copied by hand as well — a value you cannot select is a value you cannot
// use when the button misbehaves.
ColumnLayout {
    id: row

    property string label:     ""
    property string value:     ""
    property color  fieldBg:   "#232323"
    property color  borderCol: "#434343"
    property color  textDim:   "#A4A4A4"
    property color  accentCol: "#ED7B58"
    property color  okCol:     "#49F563"

    spacing: 4

    Label {
        text: row.label
        color: row.textDim
        font.pixelSize: 11
    }

    RowLayout {
        Layout.fillWidth: true
        spacing: 8

        TextField {
            id: field
            Layout.fillWidth: true
            readOnly: true
            text: row.value
            color: row.textDim
            font.pixelSize: 11
            font.family: "monospace"
            background: Rectangle {
                color: row.fieldBg; radius: 6
                border.color: row.borderCol
            }
        }

        Button {
            id: btn
            property bool copied: false
            text: copied ? "Copied" : "Copy"
            onClicked: {
                field.selectAll()
                field.copy()
                field.deselect()
                btn.copied = true
                reset.restart()
            }
            contentItem: Label {
                text: btn.text
                color: btn.copied ? row.okCol : row.accentCol
                font.pixelSize: 11
                horizontalAlignment: Text.AlignHCenter
                verticalAlignment: Text.AlignVCenter
            }
            background: Rectangle {
                implicitWidth: 70; implicitHeight: 30
                color: "transparent"; radius: 6
                border.color: btn.copied ? row.okCol : row.accentCol
            }
            Timer { id: reset; interval: 1600; onTriggered: btn.copied = false }
        }
    }
}
