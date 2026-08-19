# Project knowledge — Node Remote

Accumulated wisdom for this repo. Patterns, pitfalls and proven facts, not a changelog.
Platform-wide lessons live in `~/basecamp/basecamp-skills/skills/`; process lessons in
`~/fieldcraft/protocols/`. Raw captures go to `docs/retro-log.md` **during** the work.

---

## Architecture invariants

**The blockchain node publishes nothing.** `node_remote` runs the onion service, reads the
node's REST API over loopback, and re-serves it. The node does not know Tor exists. Copy
that says otherwise sends a debugging user to the node's logs, where there is nothing.

**`/v1/ping` is unauthenticated on purpose** — it lets the phone distinguish "onion
unreachable" from "token wrong". Consequence: **never use an authenticated route as a
liveness probe.** Curling `/v1/status` with the device token stamps last-seen and makes the
desktop pane report a connected phone — for your shell.

**Every request arrives over loopback**, whether tor forwarded it or a local process sent
it. The module cannot distinguish them, so "Connected" means *something authenticated with
the device token*, not *the phone is here*.

**Instance-bound RPCs.** `wallet_*` and `onNewBlock` answer only in the `blockchain_module`
instance actually running the node. Chain data (height/tip/peers) comes over HTTP and works
regardless. This is why Balance and Blocks cannot be tested in a headless harness.

## The crash that cost a session (fixed 2026-08-12)

`refreshBalance()` (module timer) and `getNodeStatus()` (HTTP handler) run on **different
threads** and shared four `QString` members. QString is implicitly shared and its refcount is
not thread-safe → torn data block → SIGSEGV ~20s later in unrelated code, which is why three
backtraces pointed nowhere. Fixed with a `QMutex`: slow IPC into locals, publish under the
lock at the end. See `module-http-surface-thread-safety` in basecamp-skills.

**Anything else that caches module state for the HTTP path needs the same treatment.**
`BlockStore` is the next candidate: `onNewBlock` appends while `/v1/blocks` reads.

## Two states that look identical and are not

| Pair | Distinction |
|---|---|
| `everConnected` vs `connected` | Latching ("pairing worked, stop showing the QR") vs recency ("a phone is here now", 90s window). Conflating them broke it in **both** directions: the QR hid before it could be scanned, and "Connected" outlived a switched-off phone. |
| `answered` vs `reachable` | The desktop replied vs the desktop reached the node. Keying the transport line off `reachable` made the app say "Connecting via Tor" while Tor was fine and the node was down. |
| Replaying vs stopped | A node replaying its DB is **alive**. It arrives through the same error-table function as real failures, so it needs its own channel (`NOTICE:` sentinel → `status: Starting` + `notice`). Everything else in that table means the node is genuinely down. |

## Leader rewards

**`logos_node_1click` is `ui_qml`, so its `getLeaderClaims()` cannot be called.** UI plugins
are never QRO sources — only core modules are. The ledger therefore comes from the file it
persists, `claims-history.json` beside the node's `user_config.yaml`, which `node_probe`
already resolves. The ~700 lines of chain reconciliation behind those rows are deliberately
NOT duplicated here. Consequence to state honestly: the file only advances while the desktop's
Blockchain pane is loaded, so `/v1/rewards` reports `lastScannedSlot` and `slotsBehind` rather
than implying it is live.

**The live halves come straight from `blockchain_module`** (a core module, already a dep):
`wallet_get_claimable_vouchers()` for the ready pool and `leader_claim()` for claiming.

**`StdLogosResult::value` is `nlohmann::json` holding ANY JSON value, and this module returns a
STRING containing the JSON.** So `.dump()` re-encodes it quoted-and-escaped and parsing that
yields neither array nor object. Unwrap with `is_string() ? get<std::string>() : dump()`.
Cost a live debugging round on `/v1/rewards`, which first reported "unrecognised" against a
perfectly healthy wallet. `refreshBalance()` meets the same thing and strips quotes by hand.

**`scanCaughtUp` is almost never true on a running node.** It is `lastScanned >= libSlot`, and
LIB advances ~1 slot/s while the desktop scan runs every 20s — measured 39 slots behind. Read
literally it puts "totals are still partial" on screen permanently. The summary therefore also
carries `slotsBehind`, and the phone phrases from that with a ~200-slot tolerance; the shared
`scanCaughtUp` field keeps the desktop's exact semantics so the two never disagree.

**An unknown fee must render as unknown, never 0.** On this machine 10 of 24 settled rows have
no computable fee — the chain records only the spent note's id. `net` is then a ceiling, not a
total, and `feesComplete` says which.

## Node lifecycle

**Stop the node before restarting Basecamp.** SIGTERM to Basecamp tears the module host out
from under a running node, which never gets asked to stop: ~22MB unflushed RocksDB WAL, and
the replay on restart overruns Cryptarchia's **60-second** service-start budget — the node
then sits in "Starting" forever with `Wallet`/`Cryptarchia: ServiceStatus::Starting` in the
log and no crash. A clean stop leaves ~70KB. `tools/dev-install-khidr.sh` enforces this and
refuses rather than forcing.

**The 1-click error table is the reference** for what a node failure means. Ported verbatim
into `node_probe::lastNodeError()`; recovery is checked first because it is not a failure.

## Signing (see docs/SIGNING.md)

`~/.gradle/gradle.properties` sets a **global** unprefixed `RELEASE_STORE_FILE` pointing at
the **Peers** keystore, so Node Remote's credentials must stay `NODE_REMOTE_`-prefixed. An
unprefixed name silently produces a `CN=Peers`-signed APK that **passes** the signing gate.
Always run `tools/verify-signing.fish`, which pins the digest
`6d80db3476bcada78fefa3a16b2309fd1cf830e5fc836f3e9c6c48678051ae74`. Verify the **digest**,
never the subject name — anyone can mint a key called `CN=Node Remote`.

## Privacy claims — checked, not assumed

A v3 `.onion` address **is** the ed25519 identity key (`base32(PUBKEY|CHECKSUM|VERSION)`),
and the descriptor's superencrypted outer layer is encrypted to a credential derived from
it. So **anyone holding the address can confirm the service exists**; only the inner layer
(introduction points) needs a client key. The pane previously claimed nobody could "tell
that it exists" — false, and we print that address with a Copy button.

Any new privacy claim gets checked against rend-spec-v3 before shipping. Note the first spec
lookup returned a contradictory answer; a second lookup on address encoding settled it.

## Android

Pairing must be **persisted** (`SharedPreferences`) — it lived in Compose `remember` only,
so backgrounding under memory pressure dropped a fully-paired phone to the welcome screen.
Save *before* the network work: every path funnels through `connect()`, and a pairing saved
only on success is lost if the activity is reclaimed while the circuit builds.

`POST_NOTIFICATIONS` is a **runtime** permission from API 33 (we target 36). Declaring it in
the manifest is not enough — without the grant every `notify()` is silently dropped while
the service logs success.

**The chain's raw u64 IS LGO. There is no sub-unit — nothing is divided.** `gas.rs` cites the
spec as `P_STR(0) = 1 LGO/gas` and implements it as `GasPrice::new(1)`. This file used to say
`1 LGO = 10⁴`, and the app divided by it, so every balance it ever showed read 10,000× too
small (issue #12, fixed). The desktop removed the same division; no client anywhere divides.

Amounts format from `amounts.js`: anything under 1e6 in full with separators, so a reward
reads `9,664 LGO` and not `9.66K`; only a balance is ever large enough to abbreviate. The one
deliberate divergence is the empty string, where JS `Number("")` is 0 and we print `— LGO`.

## Tooling facts

- `qInfo`/`qWarning` from a module is **never** captured by `logos_host`.
- `QStringLiteral` is UTF-16 — verify a build with `strings -el`, not `strings`.
- Nix flakes see only git-tracked files; a new untracked source fails as a CMake
  "Cannot find source file", not a silent stale build.
- Select adb devices by `ro.product.model` and fail closed. Never `head -1`.
