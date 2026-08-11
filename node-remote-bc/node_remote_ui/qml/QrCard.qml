import QtQuick
import QtQuick.Layouts
import QtQuick.Controls

// ─────────────────────────────────────────────────────────────────────────────
// QrCard — drop-in QR card component for ui_qml modules.
//
// Copy this file into your plugin's qml/ directory and instantiate:
//
//     QrCard {
//         title:       "My Chat ID"
//         description: "Scan to start a private chat"
//         payload:     someStringToEncode      // auto-generates when it changes
//     }
//
// Encoding is done by node_remote's own generateQr — no separate `qr` module needed.
//
// Override the theme.* colors to match your module's palette (see #8). Defaults = dark.
// The card calls qr.generateCard(title, description, data) and renders title + description
// + the QR, with a "Save as image" button (writes the PNG itself, no module needed).
// ─────────────────────────────────────────────────────────────────────────────
Item {
    id: card

    // ── Public API ────────────────────────────────────────────────────────
    property string title:       ""
    property string description:  ""
    property string payload:     ""    // data to encode; auto-generates on change
                                       // (named `payload`, not `data` — `data` is a
                                       //  reserved default property on QML Item)
    property bool   showSaveButton: true
    // Plain path, not StandardPaths — see the dropped Qt.labs.platform import.
    property string saveDir: "/tmp"
    // Frame edge in px. 320 fits a 57-module pairing URI at 5 physical px per module;
    // short URLs encode smaller and read fine in less space.
    property int    frameSize: 320

    // ── Theme (override to match the host module) ─────────────────────────
    property color cardBg:      "#171717"
    property color titleColor:  "#FFFFFF"
    property color descColor:   "#A4A4A4"
    property color accent:      "#FF5000"
    property color borderColor: "#383838"
    property color qrBg:        "#FFFFFF"
    property color qrFg:        "#000000"
    property color errorColor:  "#FB3748"
    property color okColor:     "#22C55E"

    // ── Internal state ────────────────────────────────────────────────────
    property int    _n: 0
    property var    _cells: []
    property string _err: ""
    property string _saveMsg: ""
    property bool   _saveOk: false

    implicitWidth: 360
    implicitHeight: outer.implicitHeight

    // Payload only: title/description are drawn by this card, not encoded into the
    // matrix, so re-encoding when they change is pure waste — and with a live
    // countdown in the description it meant a blocking call every second.
    onPayloadChanged: regenerate()
    Component.onCompleted: if (payload.length > 0) regenerate()

    // logos.callModule returns a double-JSON-encoded string; unwrap to an object.
    function callModuleParse(raw) {
        try { var v = JSON.parse(raw); if (typeof v === "string") v = JSON.parse(v); return v }
        catch (e) { return null }
    }

    function regenerate() {
        _err = ""; _saveMsg = ""; _n = 0; _cells = []
        if (!payload || payload.length === 0) return
        if (typeof logos === "undefined") { _err = "Module bridge unavailable."; return }
        // Encoded by node_remote's own bundled encoder. This used to call
        // qr.generateCard on the separate `qr` core module, and that cross-module hop
        // failed on a machine where `qr` was installed AND loaded — leaving the pairing
        // code unrenderable with no way to tell why. The encoder now ships in the module
        // we are already talking to, so there is no second module to be missing.
        var res = callModuleParse(logos.callModule("node_remote", "generateQr", [payload]))
        if (!res || !res.ok) {
            _err = (res && res.error) ? res.error : "Could not encode the pairing code."
            return
        }
        _n = res.n; _cells = res.cells
    }

    // Grab the card (title + description + QR — NOT the Save button) and save via qr core.
    function saveImage() {
        _saveMsg = ""
        if (_n <= 0) return
        // grabToImage writes the PNG directly, so saving needs no module at all.
        var name = "qr-" + Qt.formatDateTime(new Date(), "yyyyMMdd-hhmmss") + ".png"
        var dest = card.saveDir + "/" + name
        captureCard.grabToImage(function(result) {
            if (result.saveToFile(dest)) { card._saveOk = true;  card._saveMsg = "Saved: " + dest }
            else                         { card._saveOk = false; card._saveMsg = "Could not save the image." }
        })
    }

    Column {
        id: outer
        width: parent.width
        spacing: 10

        // ── Captured card: title + description + QR (this is what Save grabs) ──
        Rectangle {
            id: captureCard
            width: parent.width
            height: innerCol.implicitHeight + 32
            radius: 12
            color: card.cardBg

            ColumnLayout {
                id: innerCol
                x: 16; y: 16
                width: parent.width - 32
                spacing: 12

                Label {
                    text: card.title
                    visible: card.title.length > 0
                    color: card.titleColor
                    font.pixelSize: 18; font.bold: true
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.WordWrap
                }
                Label {
                    text: card.description
                    visible: card.description.length > 0
                    color: card.descColor
                    font.pixelSize: 13
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.WordWrap
                }

                // QR frame — fixed square, matrix centred (symmetric quiet zone).
                Rectangle {
                    id: frame
                    visible: card._n > 0
                    Layout.alignment: Qt.AlignHCenter
                    Layout.preferredWidth: card.frameSize
                    Layout.preferredHeight: card.frameSize
                    radius: 8
                    color: card.qrBg
                    readonly property int cell: card._n > 0 ? Math.max(1, Math.floor((width - 32) / card._n)) : 1
                    Grid {
                        anchors.centerIn: parent
                        columns: card._n; rows: card._n
                        Repeater {
                            model: card._cells
                            delegate: Rectangle {
                                width:  frame.cell; height: frame.cell
                                color: modelData ? card.qrFg : card.qrBg
                            }
                        }
                    }
                }

                Label {
                    text: card._err
                    visible: card._err.length > 0
                    color: card.errorColor; font.pixelSize: 12
                    Layout.fillWidth: true; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.WordWrap
                }
            }
        }

        // ── Save controls (deliberately OUTSIDE captureCard — not in the saved image) ──
        Button {
            id: saveBtn
            width: parent.width; height: 38
            visible: card.showSaveButton && card._n > 0
            text: "Save as image"
            onClicked: card.saveImage()
            contentItem: Text {
                text: saveBtn.text; color: card.titleColor; font.pixelSize: 13
                horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
            }
            background: Rectangle {
                radius: 8; color: "transparent"
                border.color: saveBtn.hovered ? card.accent : card.borderColor; border.width: 1
            }
        }
        Label {
            width: parent.width
            text: card._saveMsg
            visible: card._saveMsg.length > 0
            color: card._saveOk ? card.okColor : card.errorColor
            font.pixelSize: 11
            horizontalAlignment: Text.AlignHCenter; wrapMode: Text.WrapAnywhere
        }
    }
}
