<div align="center">
  <img src="node-remote-bc/node_remote_ui/icons/node-remote.png" width="72" alt="Node Remote">
  <h1>Node Remote</h1>
  <p><em>Watch, control and receive notifications from your Logos blockchain node, from your phone, over Tor.</em></p>
</div>

> [!IMPORTANT]
> **Not an official Logos product.** Node Remote is an independent, community-built project
> by [@alisher](https://x.com/alisher). It is not made, endorsed, reviewed or supported by
> Logos, IFT, or any affiliated entity. The Logos name and the modules it talks to belong to
> their authors; this is a third-party client that uses their public interfaces.

---

Running a Logos blockchain node means being at the desktop that runs it. Node Remote pairs
your phone with that node by scanning a QR code, and from then on the phone can see the
node's state — height, peers, balance, blend status, honest errors — and start or stop it.

The link is a **Tor v3 onion service with client authorization**. There is no account, no
server in the middle, and no port to forward.

```
┌─ Desktop (Basecamp) ────────────────┐         ┌─ Android ──────────────┐
│                                     │         │                        │
│  node_remote      (core module)     │         │  Node Remote (Compose) │
│   ├─ HTTP on 127.0.0.1 (loopback)   │         │   ├─ kmp-tor (embedded)│
│   ├─ tor ──► <onion>.onion:80 ──────┼─ Tor ──►│   └─ OkHttp via SOCKS  │
│   │           + v3 client auth      │  onion  │                        │
│   └─ reads the node's REST API      │         │   Status · Blocks      │
│                                     │         │   Proposals · Settings │
│  node_remote_ui   (ui_qml pane)     │         │                        │
│   └─ Pair (QR) · URI · Disconnect   │         └────────────────────────┘
└─────────────────────────────────────┘
```

## What's in here

| Path | What it is |
|---|---|
| [`node-remote-bc/`](node-remote-bc) | The Basecamp side: `node_remote` (core C++ module) and `node_remote_ui` (the QML pane that shows the pairing QR) |
| [`node-remote-android/`](node-remote-android) | The Android app — Kotlin + Jetpack Compose, embedded Tor, no Google Play Services |
| [`docs/`](docs) | [Privacy](docs/PRIVACY.md) · [Signing](docs/SIGNING.md) · [Testing](docs/TESTING.md) |

They live in one repo because they are one product. They share a wire format, a pairing
protocol and a version story, and they fail together when they skew — so a release cuts
both halves at once.

## Install

**Basecamp side.** Install the `node_remote` and `node_remote_ui` modules from the
[apps catalog](https://apps.alisher.xyz), or build them yourself (see below). `node_remote`
needs `blockchain_module` and a system `tor` binary on `PATH`.

**Phone.** Add the F-Droid repo — this link carries the repo path *and* the signing
fingerprint, so F-Droid can verify the repo rather than trust it:

```
https://xalisher.github.io/fdroid/repo?fingerprint=9283C4E3DAB31E68675B643AE38222358541431AD07295B6DF4A4C6D2ACCCF32
```

The bare `https://xalisher.github.io/fdroid` is the human-readable landing page — opening
it in F-Droid does nothing. The Basecamp pane shows this same link as a QR.

## Pairing

1. Open **Node Remote** in Basecamp and press **Show QR**. The onion address publishes
   (~30–90s on a cold start; faster afterwards, the tor cache is persistent).
2. Scan the code with the app. If the camera won't cooperate, the pane also shows the
   **pairing URI** and **token** as text with Copy buttons — the app's *Enter URI* screen
   takes them directly.
3. Confirm the 6-digit code shown on both ends matches. The code is
   `HMAC-SHA256(token, "lgnode/sas/v1|" + onion)` truncated to six digits, so it can only
   match if both ends hold the same token and address.

The pane says **Connected** only once the phone has made a request that passed the bearer
check — not merely because a key was minted for it.

## What it can do

**Read** — height, tip, LIB, slot, sync state, peers, blend (Edge/Core), balance, and the
node's last error mapped to plain language rather than a stack trace. Blocks and proposals
tabs mirror the 1-click Blockchain Node module's data, field for field.

**Control** — start and stop the node; wipe the chain database; regenerate the config.
Wipe refuses while the node is running and keeps `keystore.yaml` and `user_config.yaml`, so
your wallet keys and settings survive. Regenerate backs up the config first — it carries
`consensus.wallet.funding_pk`, and losing that is losing your leader identity.

**Notify** — node stopped, node error, link lost, bootstrapping, sync stalled, no peers,
node started, balance changed, block proposed. Each is a separate toggle, and notifications
can be made private (`VISIBILITY_SECRET`) so the lock screen shows nothing.

**Leader rewards** — a Rewards tab with the claimable-voucher pool, the permanent claims
ledger, and a Claim button, with a count on the tab title when vouchers are waiting. The
ledger is the one the Blockchain node module keeps; the phone reads it rather than computing
a second, disagreeing copy. A claim made from the phone shows up on the desktop too, and vice
versa — see [what the two share](#the-two-halves-share-one-ledger).

Claiming spends a voucher and pays a fee, so the button is gated on having a voucher and
enough balance, disables itself while a claim is in flight, and names the cost before the
press. That is not politeness: an earlier desktop button which did none of it turned one
intended claim into nine, and nine fees.

## What it does not do

It does **not** hide from your internet provider that you use Tor, on either end. See
[docs/PRIVACY.md](docs/PRIVACY.md) for what the onion service does and does not protect —
written against the Tor spec, with the parts we got wrong the first time called out.

It does not let you edit peers from the phone. `generate_user_config` only writes the keys
it is given, so a naive remote "add a peer" silently resets `net_port`, `blend_port` and
`external_address` — and rewrites the file holding your funding key. Peers are read-only
here on purpose.

## The two halves share one ledger

A leader claim leaves no trace on the machine that made it — the node logs nothing — so the
desktop module keeps a `claims-history.json` beside the node's config, reconciled against the
chain. Node Remote reads that file rather than reimplementing the reconciliation, which means
the two surfaces cannot drift into disagreeing about your money.

Claiming from the phone needs the reverse direction, and the obvious answer is wrong: the
desktop rewrites that ledger on a 20s load-mutate-save cycle, so a second writer would lose
rows and could corrupt it. Instead **each side owns exactly one file and reads both**:

| File | Written by | Read by |
|---|---|---|
| `claims-history.json` | the Blockchain node module | both |
| `pending-claims.json` | `node_remote` | both |

There is no lock, because there is never contention. **Neither module requires the other** —
a missing file reads as empty on both sides, so the Blockchain node module behaves exactly as
before for anyone who has not installed Node Remote.

## Build

**Basecamp modules** (nix, via `logos-module-builder`):

```
cd node-remote-bc/node_remote    && nix build .#packages.x86_64-linux.lgx-portable
cd node-remote-bc/node_remote_ui && nix build .#packages.x86_64-linux.lgx-portable
```

**Android:**

```
cd node-remote-android && ./gradlew assembleDebug
```

A distributable release additionally requires the production keystore —
`./gradlew assembleRelease -PassertReleaseSigned` **hard-fails** without it, by design.
See [docs/SIGNING.md](docs/SIGNING.md).

## Status

Pre-alpha, and honest about it. Pairing, status, control, the QR path and the Tor link are
proven end to end on real hardware — a Pixel talking to a node on another machine. As of
0.2.0 that includes leader rewards: a real claim was submitted from the phone and appeared on
both surfaces, against a live node. 0.2.1 aligns the phone with the desktop's **verified**
rewards model (chain-verdict vocabulary, the stale-state alarm, wall-clock epoch fields,
funding-key balance) — verified live against a blockchain_module 0.2.3 node.

Some notification events have never fired live ([#10](https://github.com/xAlisher/node-remote/issues/10)).
The claimable-voucher count trails a claim by 10–15 seconds
([#13](https://github.com/xAlisher/node-remote/issues/13)) — the number is correct, just late.

## Licence

MIT. The vendored QR encoder is [Project Nayuki's](https://www.nayuki.io/page/qr-code-generator-library),
MIT, in `node-remote-bc/node_remote/src/vendor/`.

**Not an official Logos product** — see the notice at the top. Independent project by
[@alisher](https://x.com/alisher), unaffiliated with Logos or IFT.
