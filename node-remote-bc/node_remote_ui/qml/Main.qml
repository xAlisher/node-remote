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
    readonly property color accent:    "#ED7B58"   // orange300 / primary — tabs, links, highlights
    readonly property color cta:       "#F55702"   // orange500 / primaryHover — the same CTA
                                                   // orange 1-click uses, with WHITE copy
    readonly property color success:   "#49F563"   // green500
    readonly property color danger:    "#FB3748"   // red500

    // Two ways to get the app. Both are shown as a scannable code, but only ONE at a
    // time: two QRs side by side is an invitation to scan the wrong one, and neither is
    // labelled once it is in a camera viewfinder.
    // The URL that ADDS THE REPO in the F-Droid app — matching what peers.tech links to.
    // Two parts are load-bearing and were both missing before:
    //   /repo         — the bare .../fdroid is a human landing page; F-Droid needs the repo
    //                   path, and scanning the landing page simply does nothing.
    //   ?fingerprint= — pins the repo's signing key, so a substituted repo is rejected
    //                   rather than silently trusted. Without it F-Droid cannot verify who
    //                   it is talking to, which for a QR someone scans off a screen is the
    //                   whole point.
    readonly property string fdroidUrl:
        "https://xalisher.github.io/fdroid/repo?fingerprint=" +
        "9283C4E3DAB31E68675B643AE38222358541431AD07295B6DF4A4C6D2ACCCF32"
    readonly property string githubUrl: "https://github.com/xAlisher/node-remote/releases/latest"
    property int sourceTab: 0        // 0 = F-Droid, 1 = GitHub

    readonly property string sourceUrl:  sourceTab === 0 ? fdroidUrl : githubUrl
    readonly property string sourceHint: sourceTab === 0
        ? "Scan in the F-Droid app to add the repo, then install Node Remote. Updates arrive automatically."
        : "Scan to open the latest release, then download the APK. No automatic updates."

    property bool  busy:     false
    property bool  ready:    false          // onion descriptor published
    property string onion:   ""
    property string pairUri: ""
    property string sas:     ""
    property string token:   ""
    property int    expiresAt: 0        // unix seconds, from beginPairing
    property int    secsLeft:  0
    property var    clients: []
    property string note:    ""
    property bool   connected:     false    // a phone is talking to us RIGHT NOW
    property bool   everConnected: false    // a phone has authenticated at least once
    property int    lastSeenSecs:  -1       // -1 = never

    // "Paired" must mean a device SPOKE to us, not that a key exists for one — the
    // client-auth key is written when the QR is rendered, so clients.length > 0 is true the
    // instant the code appears, which hid the QR before it could be scanned.
    //
    // It uses everConnected, NOT connected: once pairing has succeeded the pane must stop
    // showing the QR permanently. Whether the phone is reachable this second is a separate
    // question, answered by `connected` in the label below.
    readonly property bool paired: root.everConnected

    function seenAgo() {
        if (root.lastSeenSecs < 0) return ""
        if (root.lastSeenSecs < 60) return root.lastSeenSecs + "s ago"
        if (root.lastSeenSecs < 3600) return Math.floor(root.lastSeenSecs / 60) + " min ago"
        return Math.floor(root.lastSeenSecs / 3600) + " h ago"
    }

    // callModule returns the module's JSON as a STRING inside another JSON envelope.
    function parse(res) {
        try {
            var once = (typeof res === "string") ? JSON.parse(res) : res;
            return (typeof once === "string") ? JSON.parse(once) : once;
        } catch (e) { return {}; }
    }

    function refresh() {
        var info = parse(logos.callModule("node_remote", "getRemoteInfo", []));
        root.ready     = info.ready === true;
        root.onion     = info.onion || "";
        root.clients   = info.clients || [];
        root.connected     = info.connected === true;
        root.everConnected = info.everConnected === true;
        root.lastSeenSecs  = (info.lastSeenSecs === undefined) ? -1 : info.lastSeenSecs;
        if (info.error) root.note = info.error;
    }

    function tick() {
        if (root.expiresAt <= 0) { root.secsLeft = 0; return }
        var left = root.expiresAt - Math.floor(Date.now() / 1000)
        root.secsLeft = left > 0 ? left : 0
    }

    // Derived from the absolute expiry each second, never decremented — a timer that
    // stalls or gets throttled must not leave a code looking valid after it has expired.
    Timer {
        interval: 1000; repeat: true
        running: root.expiresAt > 0 && !root.connected
        onTriggered: root.tick()
    }

    Component.onCompleted: refresh()

    Timer {
        id: poll
        interval: 2500; repeat: true
        // Third clause: keep polling while a code is on screen, or we would never notice
        // the phone arriving — the desktop learns that only from an authenticated request.
        running: root.busy
                 || (root.onion !== "" && !root.ready)
                 || (root.pairUri !== "" && !root.everConnected)
                 || root.everConnected      // keep the live/idle line honest while paired
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
        root.token   = p.token || ""
        root.expiresAt = p.expiresAt || 0
        root.tick()
        root.busy    = false
        root.note    = ""
        refresh()
    }

    function disconnectAll() {
        for (var i = 0; i < root.clients.length; ++i)
            logos.callModule("node_remote", "revokeClient", [root.clients[i]])
        logos.callModule("node_remote", "stopRemote", [])
        root.pairUri = ""; root.sas = ""; root.token = ""; root.onion = ""; root.ready = false
        root.expiresAt = 0; root.secsLeft = 0
        root.connected = false
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
                    text: "Watch, control and receive notifications from the node on your phone over Tor connection."
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
                        text: "1. Get Node Remote app"
                        color: root.textCol; font.pixelSize: 15; font.bold: true
                    }

                    // Logos tabs — label + sliding underline, no button chrome. Matches
                    // the design system's LogosTabBar, which is what the 1-click node view
                    // uses for Node / Operations / Explorer.
                    LogosTabs {
                        Layout.fillWidth: true
                        labels: ["F-Droid", "GitHub"]
                        currentIndex: root.sourceTab
                        onCurrentIndexChanged: root.sourceTab = currentIndex
                        activeColor: root.accent
                        inactiveColor: root.textDim
                    }

                    Label {
                        text: root.sourceHint
                        color: root.textDim; font.pixelSize: 12
                        Layout.fillWidth: true; wrapMode: Text.WordWrap
                    }

                    SecretRow {
                        Layout.fillWidth: true
                        label: root.sourceTab === 0 ? "F-Droid repo" : "Releases"
                        value: root.sourceUrl
                        fieldBg: root.surface2
                        borderCol: root.border
                        textDim: root.textDim
                        accentCol: root.accent
                        okCol: root.success
                    }

                    QrCard {
                        Layout.fillWidth: true
                        payload: root.sourceUrl
                        frameSize: 0            // auto — see QrCard.effectiveFrame
                        showSaveButton: false
                        cardBg: root.surface2
                        titleColor: root.textCol
                        descColor: root.textDim
                        accent: root.accent
                        borderColor: root.border
                    }

                    Label {
                        // Honest while it is true; the F-Droid listing is not live yet.
                        text: "Not published yet — these links go live with the first release."
                        color: root.textDim; font.pixelSize: 11; font.italic: true
                        Layout.fillWidth: true; wrapMode: Text.WordWrap
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
                            text: !root.paired ? "2. Pair your phone"
                                               : (root.connected ? "Connected" : "Paired — not connected")
                            color: !root.paired ? root.textCol
                                                : (root.connected ? root.success : root.textDim)
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
                            // The module cannot see the app's state — the phone pushes no
                            // disconnect — so say when we last HEARD from it rather than
                            // implying a live link we cannot observe.
                            text: root.clients.join(", ") +
                                  (root.connected ? "" : (root.lastSeenSecs >= 0
                                       ? "  ·  last seen " + root.seenAgo()
                                       : "  ·  never connected")) +
                                  (root.onion ? "  ·  " + root.onion.substring(0, 12) + "…onion" : "")
                            color: root.textDim; font.pixelSize: 12
                            Layout.fillWidth: true; elide: Text.ElideRight
                        }
                        // Checked against rend-spec-v3 before writing. A v3 address IS the
                        // ed25519 identity key — base32(PUBKEY|CHECKSUM|VERSION) — and the
                        // descriptor's OUTER layer is encrypted to a credential derived from
                        // that key. So anyone holding the address can fetch the descriptor and
                        // decrypt the outer layer, which is enough to confirm the service
                        // exists. Only the INNER layer, holding the introduction points, needs
                        // an authorized client key. The previous copy claimed nobody could
                        // "tell that it exists", which is false for anyone with the address —
                        // and we print that address here with a Copy button.
                        Label {
                            text: "Only paired devices can reach your node from outside this " +
                                  "computer: without the key from the pairing code, nobody can " +
                                  "connect. The address is not listed anywhere to look up, but " +
                                  "anyone who does learn it can tell the service exists."
                            color: root.textDim; font.pixelSize: 11
                            Layout.fillWidth: true; wrapMode: Text.WordWrap
                        }
                    }

                    // Not started yet.
                    Button {
                        // Also shown once the code expires — otherwise the pane strands the
                        // user with a dead QR and no way to ask for another.
                        visible: !root.paired && !root.busy
                                 && (root.pairUri === "" || root.secsLeft === 0)
                        text: root.pairUri === "" ? "Show QR" : "New code"
                        // The onion is already up on a retry; only the code needs reminting.
                        onClicked: root.ready ? root.beginPairing() : root.startRemote()
                        contentItem: Label {
                            text: parent.text; color: "#FFFFFF"
                            font.pixelSize: 13; font.bold: true
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                        background: Rectangle {
                            implicitWidth: 120; implicitHeight: 36
                            color: root.cta; radius: 8
                        }
                    }

                    // The QR itself, plus the code the phone must match.
                    ColumnLayout {
                        visible: root.pairUri !== "" && !root.paired
                        Layout.fillWidth: true
                        spacing: 10

                        QrCard {
                            Layout.fillWidth: true
                            frameSize: 0    // auto
                            // An expired code still scans — dim it so it reads as dead.
                            opacity: root.secsLeft > 0 ? 1.0 : 0.35
                            title: "Scan with Node Remote"
                            description: root.secsLeft > 0
                                         ? "Expires in " + root.secsLeft + "s"
                                         : "Expired — press Show QR for a new code"
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

                        // The same pairing data in text form. The app's "Enter URI" screen
                        // takes this, so a phone whose camera will not focus — or a desktop
                        // being driven over a remote session — can still pair.
                        SecretRow {
                            Layout.fillWidth: true
                            label: "Pairing URI"
                            value: root.pairUri
                            fieldBg: root.surface2
                            borderCol: root.border
                            textDim: root.textDim
                            accentCol: root.accent
                            okCol: root.success
                        }
                        SecretRow {
                            Layout.fillWidth: true
                            label: "Token"
                            value: root.token
                            fieldBg: root.surface2
                            borderCol: root.border
                            textDim: root.textDim
                            accentCol: root.accent
                            okCol: root.success
                        }
                        Label {
                            text: "Treat these like a password — the URI carries the key that " +
                                  "lets a phone reach this node."
                            color: root.textDim; font.pixelSize: 11
                            Layout.fillWidth: true; wrapMode: Text.WordWrap
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
