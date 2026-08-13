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

    // "Get the app" matters once. After the first successful pairing it is just noise above
    // the thing you actually came for, so it collapses itself — once. The latch means a user
    // who re-opens it keeps it open; auto-collapsing on every pairing would fight them.
    property bool appOpen: true
    property bool appAutoCollapsed: false
    onPairedChanged: {
        if (root.paired && !root.appAutoCollapsed) {
            root.appOpen = false
            root.appAutoCollapsed = true
        }
    }

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

    // THREE INDEPENDENT FACTS, deliberately not collapsed into one:
    //
    //   paired     an authorised client key exists ON DISK. Ground truth for "is a device
    //              allowed in", and the only thing [Pair]/[Unpair] act on.
    //   connected  a phone authenticated inside the staleness window. Live link, now.
    //   everConnected  one has authenticated at least once this session.
    //
    // paired used to be derived from everConnected, because keying it off the key file hid
    // the QR the instant the key was minted. That coupling produced a state the pane could
    // not escape: after a revoke, authorized_clients was empty (NOT paired) while
    // lastAuthedAt was still recent (paired), so the pane claimed paired, hid the Pair
    // control, and offered no way back — no QR, no error, nothing.
    //
    // With pairing an EXPLICIT action, the original problem disappears: the QR is shown
    // because a pairing is in progress (pairUri set), not because we are "not paired".
    readonly property bool paired: root.clients.length > 0

    function seenAgo() {
        if (root.lastSeenSecs < 0) return ""
        if (root.lastSeenSecs < 60) return root.lastSeenSecs + "s ago"
        if (root.lastSeenSecs < 3600) return Math.floor(root.lastSeenSecs / 60) + " min ago"
        return Math.floor(root.lastSeenSecs / 3600) + " h ago"
    }

    property bool moduleDead: false

    // callModule returns the module's JSON as a STRING inside another JSON envelope.
    //
    // An EMPTY or unparseable result means the module process is not answering — it has
    // crashed or been unloaded. That has to be said out loud: silently returning {} made
    // every field reset to its default, so a crashed module looked exactly like "never
    // paired" and the pane quietly went back to the Show QR button with no explanation.
    function parse(res) {
        if (res === undefined || res === null || res === "") { root.moduleDead = true; return {} }
        try {
            var once = (typeof res === "string") ? JSON.parse(res) : res;
            var v = (typeof once === "string") ? JSON.parse(once) : once;
            root.moduleDead = false
            return v;
        } catch (e) { root.moduleDead = true; return {}; }
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
        root.note = "Unpaired. The onion no longer answers that device."
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

                    // Disclosure header: the whole row toggles, not just the triangle —
                    // a 12px hit target is a miss waiting to happen.
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 8
                        Label {
                            text: root.appOpen ? "\u25BE" : "\u25B8"   // ▾ open, ▸ collapsed
                            color: root.textDim
                            font.pixelSize: 13
                        }
                        Label {
                            text: "1. Get Node Remote app"
                            color: root.textCol; font.pixelSize: 15; font.bold: true
                            Layout.fillWidth: true
                        }
                        MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            onClicked: root.appOpen = !root.appOpen
                        }
                    }

                    // The body. ONE wrapper with visible bound to appOpen, so the parent
                    // Rectangle's implicitHeight (appCol.implicitHeight + 28) shrinks with
                    // it — hiding children individually would leave the card its full height.
                    ColumnLayout {
                    visible: root.appOpen
                    Layout.fillWidth: true
                    spacing: 8

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
                    }   // end collapsible body
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
                            text: !root.paired ? "2. Pair your phone" : "Paired"
                            color: root.textCol
                            font.pixelSize: 15; font.bold: true
                            Layout.fillWidth: true
                        }
                        BusyIndicator {
                            running: root.busy
                            visible: root.busy
                            implicitWidth: 22; implicitHeight: 22
                        }
                    }

                    // CONNECTION is a separate line from PAIRING, because they are separate
                    // facts: a paired device can be switched off, and an unpaired one cannot
                    // be connected at all. Collapsing them is what produced a pane that said
                    // "paired" while the key had been revoked.
                    Label {
                        visible: root.paired
                        text: root.connected
                                  ? "Connected"
                                  : (root.lastSeenSecs >= 0
                                        ? "Connecting…  ·  last seen " + root.seenAgo()
                                        : "Connecting…")
                        color: root.connected ? root.success : root.textDim
                        font.pixelSize: 13
                        Layout.fillWidth: true
                    }

                    // The module is gone. Say so instead of showing a pairing flow that
                    // cannot work — every button here calls a process that is not there.
                    Rectangle {
                        visible: root.moduleDead
                        Layout.fillWidth: true
                        implicitHeight: deadCol.implicitHeight + 20
                        color: "#2A1416"
                        border.color: root.danger
                        radius: 8
                        ColumnLayout {
                            id: deadCol
                            anchors.fill: parent
                            anchors.margins: 10
                            spacing: 4
                            Label {
                                text: "node_remote module crashed — please restart the app"
                                color: root.danger; font.pixelSize: 13; font.bold: true
                                Layout.fillWidth: true; wrapMode: Text.WordWrap
                            }
                            Label {
                                text: "Your pairing is kept on disk — the phone does not need a new QR."
                                color: root.textDim; font.pixelSize: 11
                                Layout.fillWidth: true; wrapMode: Text.WordWrap
                            }
                        }
                    }

                    // Say what the wait is for, rather than showing an empty gap between
                    // pressing Pair and the code appearing.
                    Label {
                        visible: root.pairUri !== "" && !root.ready && !root.connected
                        text: "Publishing your onion address — the code appears in a moment."
                        color: root.textDim; font.pixelSize: 12
                        Layout.fillWidth: true; wrapMode: Text.WordWrap
                    }

                    Label {
                        visible: root.note !== "" && !root.moduleDead
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
                            // Device + address only. "last seen" lives on the connection
                            // line above; printing it twice made one fact look like two.
                            text: root.clients.join(", ") +
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
                        // Pair and Unpair are mutually exclusive: showing both at once asks
                        // the user to work out which applies. Re-pairing is Unpair then Pair.
                        //
                        // The second clause keeps "New code" reachable DURING a pairing: the
                        // key is written the moment the QR is drawn, so `paired` is already
                        // true while the code is on screen — without it an expired code would
                        // leave no way to mint another.
                        visible: !root.moduleDead && !root.busy
                                 && (!root.paired
                                     || (root.pairUri !== "" && root.secsLeft === 0))
                        // "Pair" is now an explicit action rather than something the pane
                        // infers. "New code" only while a code is on screen and has expired.
                        text: root.pairUri === "" ? "Pair" : "New code"
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
                        // Gate on CONNECTED, not paired. `paired` means "a key exists on
                        // disk", and beginPairing() writes that key the instant the code is
                        // drawn — so gating on !paired hid the QR before it could be
                        // scanned. That was the original bug; redefining `paired` for the
                        // Pair/Unpair split reintroduced it here, because only one side of
                        // the pair was updated.
                        //
                        // The code stays up until a phone actually authenticates, which is
                        // the only event that means the pairing WORKED.
                        // ALSO gated on `ready`. beginPairing() calls reload(), which
                        // RESTARTS tor so it picks up the new authorized_clients entry — and
                        // a restarted onion needs ~30-60s to republish its descriptor. The QR
                        // used to appear the instant the key was minted, so every scan landed
                        // in the window where the onion is unreachable; the phone then failed
                        // with "SOCKS server general failure", and with a 90s connect timeout
                        // it looked like nothing was happening at all.
                        visible: root.pairUri !== "" && !root.connected && root.ready
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
                        // Named for what it does to the PAIRING (revokes the key), not for
                        // what it does to the connection. Only shown when a key exists.
                        visible: root.paired
                        text: "Unpair"
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
