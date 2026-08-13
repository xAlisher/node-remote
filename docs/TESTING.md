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
- **Balance, on screen.** Previously listed as unproven twice over. The figure now arrives
  on the phone against a live node — observed, with the desktop showing the same number.
- **Node control, both directions.** Stop and Start driven from the phone; the desktop
  followed and the control flipped to match, and the reverse (stopped on the desktop, read
  correctly on the phone) held too. This is what the shared intent latch exists for.
- **A stopped node reads as stopped, not as a failure.** On both surfaces — the desktop no
  longer shows a red error card for a deliberate stop, and the phone shows a neutral pill.
- **Pairing confirmation.** The 6-digit SAS is shown on its own screen after scanning and
  matched the desktop's; confirming it completed the pairing and data followed.

## Not proven

Say so rather than implying coverage:

- **Blend `Inactive`.** The Bootstrapping case now reports Inactive rather than Unknown,
  following 1-click. Deployed, not yet seen on screen.
- **Three notification events** — Bootstrapping, Balance changed, Block proposed — are wired
  but have never fired live.
- **The honest-error table** is a faithful port of `logos_node_1click`'s mapping but has not
  been triggered at runtime for every branch.
- **KNOWN CRASH — node_remote takes SIGSEGV during IBD.** Observed once: module loaded
  01:28:01, crashed with signal 11 at 01:29:21 while the node was mid initial block
  download. The evidence is timing, not a decoded stack: the balance timer ticks at +78s
  and the crash was at +80s, and `refreshBalance()` is the only code making SYNCHRONOUS
  blockchain_module IPC from a timer — while `onNewBlock` was firing continuously. A sync
  IPC call runs a nested event loop, so a block callback can land inside one.
  Since then the actual defect class was found and fixed in two places: the balance strings
  and `BlockStore` were both unguarded containers shared between the HTTP handler thread and
  the module's timers, and both now hold a `QMutex`. **Still not proven fixed** — no crash
  has been seen since, but nobody has run the soak that would exercise it (node in catch-up,
  phone polling `/v1/blocks`, 5+ minutes). Absence of a crash is not evidence; a previous
  mitigation in this same area was called a fix and then crashed again 77s later.
  The pane now says "Node Remote stopped responding" instead of silently reverting to the
  Show QR button, which is how this presented and why it took a session to notice.
  To reproduce: pair, start a node that needs IBD, leave the pane open ~90s.
- **Doze survival.** How long the foreground service keeps Tor alive under aggressive
  battery management is unmeasured.
- **The cold-start fix (0.1.1) is UNPROVEN.** A first run with no Tor cache was measured
  taking 125s to bootstrap, and the old 120s publish timeout gave up five seconds short —
  leaving no QR and no way forward. The timeout is now split so the clock for publishing
  only starts once Tor reports Bootstrapped 100%. But the attempt to reproduce it wiped the
  cache and got a 6-second bootstrap, so the slow path was never re-entered. The fix is
  reasoned, not demonstrated. Tracked as #6.

## Proven headlessly

Not hardware proof, but not inference either — these run against a real tor and a real
onion, with no phone involved:

- **A pairing survives.** `pairing_stability.sh` 10/10. The key one: once a device has
  actually authenticated, issuing a new code is REFUSED and the on-disk key is compared
  before and after to prove it did not move. This is a real field failure — a poll timer
  rotated the key out from under a paired phone, and because an un-authorized tor client is
  indistinguishable from no client, nothing reported an error at either end.
- **Revocation is not merely token-deep.** A revoked key gets `000` — no response at all —
  not a `401`. The distinction matters: a `401` confirms the onion exists and is up.
- **Pairing republishes in 2–4s**, because adding a client sends tor a SIGHUP instead of
  restarting it. A restart cost 2m20s of unreachable onion, during which the app showed
  "Connected" and no data.
- **The state machine.** `lifecycle_test.sh` 4/4 covers a stale fatal log on a stopped node,
  a real error, a replay with its block count, and a quiet start.
- Also green: `pairing_e2e.sh` 5/5, `headless_test.sh` 11/11 (1 skipped by design),
  `NodeStateTest` 7/7.

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

### v0.1.1 — pairing UI hotfixes

Everything here was reported from a live pane and fixed the same day. The Android app is
unchanged in behaviour; it carries 0.1.1 because the two halves share a pairing protocol and
are versioned together.

- **Press Pair and watch the code appear.** It should show up immediately, with no blank gap
  where the card is present but the QR is missing. That gap was a readiness check that dipped
  every time a code was minted.
- **Restart Basecamp while a code is on screen, then reopen the pane.** It should say
  *Pairing unfinished* and offer **Pair** — not claim you are paired, and not leave Unpair as
  the only button. The code lives in memory; the key outlives it.
- **Check the header never says Paired until your phone has actually connected.** It used to
  go green the instant the QR was drawn, before anyone had scanned anything.
- **After a restart, read the line under Connecting…** It should tell you the reconnect takes
  about a minute. If it takes materially longer than that, say so — the number is a claim.
- **First run on a machine that has never run Tor** is the case worth reporting: the QR should
  still appear even if Tor takes minutes to bootstrap. See the caveat below.

### v0.1.0 — first release

- **Pair from scratch.** Scan the QR. If your camera struggles, use **Enter URI** with the
  URI the pane shows — both paths should reach the same place.
- **Check the status pill against reality.** A bootstrapping node should read
  *Bootstrapping* and offer **Stop node** — not *Start node*. That was a real bug.
- **Pull the plug.** Turn off Wi-Fi. The transport line should say it lost the link; the
  status should not claim the node stopped, because you don't know that.
- **Restart Basecamp while paired.** The phone should keep working without re-pairing — the
  bearer token is persisted for exactly this.
- **Pair, then ask the desktop for another code.** It should refuse while your phone is
  paired, and say so. To move to a different phone: Unpair first, then Pair.
- **Watch how long pairing takes.** From confirming the code to seeing data should be
  seconds. If it sits on "Connecting…" for a minute, that is a regression worth reporting.
- **Read the privacy page** and tell me if any claim there overstates what you observe.
