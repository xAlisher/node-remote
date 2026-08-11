import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// ─────────────────────────────────────────────────────────────────────────────
// Node Remote — pair a phone with this node over a Tor onion service.
//
// Flow: Show QR  →  (onion publishes, ~30-90s)  →  QR + 6-digit code  →  paired
//       →  Disconnect (revokes the device; the onion stops answering it)
//
// Everything goes through node_remote via logos.callModule. Results are
// DOUBLE-JSON wrapped, so every call goes through parse() below — reading
// callModule's return directly yields a string, not an object.
// ─────────────────────────────────────────────────────────────────────────────
Item {
    id: root
    anchors.fill: parent

    // Logos design-system dark palette (DarkTheme.qml / ColorPalette.qml).
    readonly property color bg:        "#171717"   // gray900
    readonly property color surface:   "#1C1C1C"   // gray875
    readonly property color surface2:  "#232323"   // gray360
    readonly property color border:    "#434343"   // gray300
    readonly property color textCol:   "#FFFFFF"
    readonly property color textDim:   "#A4A4A4"   // gray400
    readonly property color accent:    "#ED7B58"   // orange300 / primary
    readonly property color success:   "#49F563"   // green500
    readonly property color danger:    "#FB3748"   // red500

    // F-Droid listing. Placeholder until the app is published.
    readonly property string fdroidUrl: "https://f-droid.org/packages/co.logos.noderemote/"

    property bool  busy:     false
    property bool  ready:    false          // onion descriptor published
    property string onion:   ""
    property string pairUri: ""
    property string sas:     ""
    property var    clients: []
    property string note:    ""

    readonly property bool paired: clients.length > 0

    // callModule returns the module's JSON as a STRING inside another JSON envelope.
    function parse(res) {
        try {
            var once = (typeof res === "string") ? JSON.parse(res) : res;
            return (typeof once === "string") ? JSON.parse(once) : once;
        } catch (e) { return {}; }
    }

    function refresh() {
        var info = parse(logos.callModule("node_remote", "getRemoteInfo", []));
        root.ready   = info.ready === true;
        root.onion   = info.onion || "";
        root.clients = info.clients || [];
        if (info.error) root.note = info.error;
    }

    Component.onCompleted: refresh()

    Timer {
        id: poll
        interval: 2500; repeat: true; running: root.busy || (root.onion !== "" && !root.ready)
        property bool inFlight: false
        onTriggered: {
            if (inFlight) return           // re-entrancy guard: callModule blocks
            inFlight = true
            refresh()
            if (root.ready && root.busy && root.pairUri === "") beginPairing()
            inFlight = false
        }
    }

    function startRemote() {
        root.busy = true
        root.note = "Publishing your onion address — this takes 30–90 seconds."
        var r = parse(logos.callModule("node_remote", "startRemote", []))
        if (r.ok !== true) { root.busy = false; root.note = "Could not start: " + (r.error || "unknown") }
        refresh()
    }

    function beginPairing() {
        var p = parse(logos.callModule("node_remote", "beginPairing", ["phone"]))
        if (p.ok !== true) { root.note = p.error || "pairing failed"; root.busy = false; return }
        root.pairUri = p.uri || ""
        root.sas     = p.sas || ""
        root.busy    = false
        root.note    = ""
        refresh()
    }

    function disconnectAll() {
        for (var i = 0; i < root.clients.length; ++i)
            logos.callModule("node_remote", "revokeClient", [root.clients[i]])
        logos.callModule("node_remote", "stopRemote", [])
        root.pairUri = ""; root.sas = ""; root.onion = ""; root.ready = false
        root.note = "Disconnected. The onion no longer answers that device."
        refresh()
    }

    Rectangle { anchors.fill: parent; color: root.bg }

    ScrollView {
        anchors.fill: parent
        anchors.margins: 24
        contentWidth: availableWidth

        ColumnLayout {
            width: parent.width
            spacing: 20

            // ── Title ────────────────────────────────────────────────────
            ColumnLayout {
                spacing: 4
                Label {
                    text: "Node Remote"
                    color: root.textCol
                    font.pixelSize: 26; font.bold: true
                }
                Label {
                    text: "Watch and control this node from your phone, over a private Tor connection."
                    color: root.textDim
                    font.pixelSize: 13
                    Layout.fillWidth: true
                    wrapMode: Text.WordWrap
                }
            }

            // ── Step 1: get the app ──────────────────────────────────────
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: appCol.implicitHeight + 28
                color: root.surface
                border.color: root.border
                radius: 10

                ColumnLayout {
                    id: appCol
                    anchors.fill: parent
                    anchors.margins: 14
                    spacing: 8

                    Label {
                        text: "1 · Get the Android app"
                        color: root.textCol; font.pixelSize: 15; font.bold: true
                    }
                    Label {
                        text: "Install Node Remote from F-Droid, then scan the code below."
                        color: root.textDim; font.pixelSize: 12
                        Layout.fillWidth: true; wrapMode: Text.WordWrap
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 8

                        TextField {
                            id: linkField
                            Layout.fillWidth: true
                            readOnly: true
                            text: root.fdroidUrl
                            color: root.textDim
                            font.pixelSize: 12
                            background: Rectangle {
                                color: root.surface2; radius: 6
                                border.color: root.border
                            }
                        }
                        Button {
                            id: copyBtn
                            text: copyBtn.copied ? "Copied" : "Copy"
                            property bool copied: false
                            onClicked: {
                                linkField.selectAll()
                                linkField.copy()
                                linkField.deselect()
                                copyBtn.copied = true
                                copyReset.restart()
                            }
                            contentItem: Label {
                                text: copyBtn.text
                                color: copyBtn.copied ? root.success : root.accent
                                font.pixelSize: 12
                                horizontalAlignment: Text.AlignHCenter
                                verticalAlignment: Text.AlignVCenter
                            }
                            background: Rectangle {
                                implicitWidth: 76; implicitHeight: 32
                                color: "transparent"; radius: 6
                                border.color: copyBtn.copied ? root.success : root.accent
                            }
                            Timer {
                                id: copyReset
                                interval: 1600
                                onTriggered: copyBtn.copied = false
                            }
                        }
                    }
                    Label {
                        // Honest: the listing does not exist yet.
                        text: "Not published yet — this link is a placeholder."
                        color: root.textDim; font.pixelSize: 11; font.italic: true
                    }
                }
            }

            // ── Step 2: pair ─────────────────────────────────────────────
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: pairCol.implicitHeight + 28
                color: root.surface
                border.color: root.paired ? root.success : root.border
                radius: 10

                ColumnLayout {
                    id: pairCol
                    anchors.fill: parent
                    anchors.margins: 14
                    spacing: 12

                    RowLayout {
                        Layout.fillWidth: true
                        Label {
                            text: root.paired ? "Connected" : "2 · Pair your phone"
                            color: root.paired ? root.success : root.textCol
                            font.pixelSize: 15; font.bold: true
                            Layout.fillWidth: true
                        }
                        BusyIndicator {
                            running: root.busy
                            visible: root.busy
                            implicitWidth: 22; implicitHeight: 22
                        }
                    }

                    Label {
                        visible: root.note !== ""
                        text: root.note
                        color: root.textDim; font.pixelSize: 12
                        Layout.fillWidth: true; wrapMode: Text.WordWrap
                    }

                    // Paired: show which devices, and how to cut them off.
                    ColumnLayout {
                        visible: root.paired
                        Layout.fillWidth: true
                        spacing: 6
                        Label {
                            text: root.clients.join(", ") +
                                  (root.onion ? "  ·  " + root.onion.substring(0, 12) + "…onion" : "")
                            color: root.textDim; font.pixelSize: 12
                            Layout.fillWidth: true; elide: Text.ElideRight
                        }
                        Label {
                            text: "Only this device can reach your node. Nobody else can connect to it, " +
                                  "look it up, or tell that it exists."
                            color: root.textDim; font.pixelSize: 11
                            Layout.fillWidth: true; wrapMode: Text.WordWrap
                        }
                    }

                    // Not started yet.
                    Button {
                        visible: !root.paired && root.pairUri === "" && !root.busy
                        text: "Show QR"
                        onClicked: root.startRemote()
                        contentItem: Label {
                            text: parent.text; color: "#171717"
                            font.pixelSize: 13; font.bold: true
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                        background: Rectangle {
                            implicitWidth: 120; implicitHeight: 36
                            color: root.accent; radius: 8
                        }
                    }

                    // The QR itself, plus the code the phone must match.
                    ColumnLayout {
                        visible: root.pairUri !== "" && !root.paired
                        Layout.fillWidth: true
                        spacing: 10

                        QrCard {
                            Layout.fillWidth: true
                            Layout.preferredHeight: 320
                            title: "Scan with Node Remote"
                            description: "Valid for 2 minutes"
                            payload: root.pairUri
                            cardBg: root.surface2
                            titleColor: root.textCol
                            descColor: root.textDim
                            accent: root.accent
                            borderColor: root.border
                        }

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 10
                            Label {
                                text: "Confirm this code matches your phone:"
                                color: root.textDim; font.pixelSize: 12
                            }
                            Label {
                                text: root.sas
                                color: root.accent
                                font.pixelSize: 20; font.bold: true
                                font.family: "monospace"
                            }
                        }
                    }

                    Button {
                        visible: root.paired || root.pairUri !== ""
                        text: "Disconnect"
                        onClicked: root.disconnectAll()
                        contentItem: Label {
                            text: parent.text; color: root.danger
                            font.pixelSize: 13
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                        background: Rectangle {
                            implicitWidth: 110; implicitHeight: 34
                            color: "transparent"; radius: 8
                            border.color: root.danger
                        }
                    }
                }
            }

            Item { Layout.fillHeight: true }
        }
    }
}
