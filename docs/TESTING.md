# Testing

> **Not an official Logos product.** Independent project by [@alisher](https://x.com/alisher).

What is proven, what is not, and what to test in each release.

## Proven on real hardware

These were observed end to end — a Pixel over Tor to a node running in Basecamp on a
separate machine — not inferred from a build succeeding.

- **Pairing.** QR scanned, client-auth key installed in the phone's tor, onion reached,
  `/v1/status` returned `200` with live node data.
- **Client authorization.** A client without the key cannot complete the circuit — no
  response at all, not a rejected request.
- **Chain data over HTTP.** Height, tip, LIB, slot, peers and sync state, read from the
  node's own REST API and matching the desktop's 1-click module field for field.
- **QR encoding.** A full 192-character pairing URI encodes to a 57×57 matrix that an
  independent decoder (zbar) reads back byte-identical.
- **Signing gate, both directions.** Without the keystore, `assembleRelease
  -PassertReleaseSigned` fails and produces no APK. With it, the release APK is signed
  `CN=Node Remote` / `6d80db34…51ae74` — confirmed with apksigner against the pinned digest,
  after the same build had once come out signed `CN=Peers`.
- **Pairing survives a Basecamp restart.** After a full restart with nobody pressing
  Show QR, tor came back on the SAME onion, `/v1/status` answered `200` with the persisted
  bearer token, and `last_seen` was rewritten — so the phone reconnected on its own.
- **The `Starting` state, on real data.** A node replaying its database reported
  `{"status":"Starting","notice":"The node is replaying stored blocks to catch up…"}`
  instead of an error with a Start button.
- **Blocks.** The `onNewBlock` subscription delivers; `/v1/blocks` returned 100 blocks with
  the full field set (blockRoot, entropy, leaderKey, parentBlock…) against a live node.

## Not proven

Say so rather than implying coverage:

- **Balance.** The merging code was never wired to `/v1/status` (fixed, not yet observed
  working). It needs `node_remote` running inside Basecamp *beside* the node: the wallet
  RPCs are instance-bound and only answer in the `blockchain_module` that is actually
  running the node, so this cannot be exercised in a headless harness.
- **Blend `Inactive`.** The Bootstrapping case now reports Inactive rather than Unknown,
  following 1-click. Deployed, not yet seen on screen.
- **Balance.** Now cached off the request path; the figure itself still has not been
  observed on the phone.
- **Three notification events** — Bootstrapping, Balance changed, Block proposed — are wired
  but have never fired live.
- **The honest-error table** is a faithful port of `logos_node_1click`'s mapping but has not
  been triggered at runtime for every branch.
- **Doze survival.** How long the foreground service keeps Tor alive under aggressive
  battery management is unmeasured.

## Manual smoke test

1. Basecamp → **Node Remote** → **Show QR**. The code appears within ~90s of a cold start.
2. App → **Scan QR**. Confirm the 6-digit code matches the desktop.
3. Status tab: height climbs, peers non-zero, transport line reads **Connected via Tor**.
4. Stop the node from the phone; confirm the desktop agrees and the button flips to
   **Start node**. Start it again.
5. Background the app for 30 minutes; confirm status still updates.
6. Settings → **Disconnect**. Confirm the onion stops answering that device.

## What to test in this release

<!-- Newest first. One block per release; add a new ### section at the top. -->

### v0.1.0 — first release

- **Pair from scratch.** Scan the QR. If your camera struggles, use **Enter URI** with the
  URI the pane shows — both paths should reach the same place.
- **Check the status pill against reality.** A bootstrapping node should read
  *Bootstrapping* and offer **Stop node** — not *Start node*. That was a real bug.
- **Pull the plug.** Turn off Wi-Fi. The transport line should say it lost the link; the
  status should not claim the node stopped, because you don't know that.
- **Restart Basecamp while paired.** The phone should keep working without re-pairing — the
  bearer token is persisted for exactly this.
- **Read the privacy page** and tell me if any claim there overstates what you observe.
