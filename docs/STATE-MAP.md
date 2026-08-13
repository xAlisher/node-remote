# Node Remote — state map

Every arbitration bug found in this system has had the same shape: **one value standing in
for three independent facts**. A phone that is switched off is not a node that has stopped.
A key that exists is not a device that is using it. A tor process that is running is not an
onion that can be reached.

This document maps the three axes separately, names the single source of truth for each,
and records which failure modes are now held down by a test.

---

## The three axes

They are orthogonal. Any combination is reachable, and a UI that cannot render a
combination will invent one — that is the bug generator.

| Axis | Question | Owner | Persisted as |
|---|---|---|---|
| **Node** | What is the blockchain node doing? | `node_probe.cpp` | `nodeIntent` in `QSettings("Logos","BlockchainUI")` + live probe |
| **Link** | Can the phone reach the desktop? | phone `MainActivity` / desktop `lastAuthedAt()` | `last_seen` file |
| **Pairing** | Does a credential exist, and is it in use? | `onion_service.cpp` | `authorized_clients/*.auth` + `device_token` |

---

## Axis 1 — Node

Derived once, in `NodeProbe::statusJson()`. Both UIs read it; neither re-derives it.

| State | Meaning | Colour |
|---|---|---|
| `Stopped` | Deliberately stopped | gray |
| `Starting` | Intent is up, no API yet, nothing wrong | orange |
| `Recovering` | Replaying stored blocks (carries a block count) | orange |
| `Online` | API answering | green |
| `Error` | Intent is up, API down, fatal line in the log | red |

**Precedence, and why the order is what it is:**

1. **`Online`** — reachability beats everything. Checked first so a node that is up while
   still bootstrapping reads as live rather than as unknown.
2. **`Stopped`** — intent beats the log. Checked *ahead of* recovery detection because
   recovery is a **log scrape**, and a log line outlives the state it describes. This is
   the "deploy config on a stopped node" bug: a stale fatal line rendered a deliberately
   stopped node as a red error.
3. **`Recovering`** → 4. **`Error`** → 5. **`Starting`**.

The intent latch is written by **both** surfaces — node_remote and logos_node_1click — to
the same org/app/key. One-sided, a desktop stop reads on the phone as `Error` or as a
permanent `Starting…`.

Covered by `lifecycle_test.sh` (4/4): L1 stale-fatal-log-while-stopped, L2 real error,
L3 replay with block count, L4 quiet start.

---

## Axis 2 — Link

**Phone:** `NO_INTERNET` · `CONNECTING` · `CONNECTED` · `DISCONNECTED`

`DISCONNECTED` is a *user intent*, never an observation. "Cannot reach it yet" is
`CONNECTING` — Tor circuits and descriptor fetches legitimately take tens of seconds, and
falling back to `DISCONNECTED` on a slow circuit is what made a working link look dead.

Two traps, both now fixed:

- **`CONNECTED` must mean the circuit is usable.** It was set immediately after the SOCKS
  port appeared — which happens 10–40s *before* tor bootstraps. `TorClient.awaitReady()`
  now waits on `RuntimeEvent.READY`.
- **Freshness is not truth.** A status older than 45s is stale; the UI shows `—` rather
  than the last good reading. A pane that says "Connected" forever after one request is
  worse than one that is 90s late.

**Desktop:** `connected = lastAuthedAt > 0 && age ≤ 90s`, `everConnected = lastAuthedAt > 0`.
90s, not 30s, because Doze and a stalled circuit stretch a poll and a flickering pane is
worse than a late one.

---

## Axis 3 — Pairing

This is the axis that was collapsed, and the collapse caused the field failure.

| State | On disk | `lastAuthedAt` | Rotating is |
|---|---|---|---|
| **Not paired** | no `.auth`, or only `_sealed.auth` | 0 | safe |
| **Code issued, unused** | `phone.auth` exists | 0 | **safe** — nobody holds it |
| **Paired and in use** | `phone.auth` exists | > 0 | **destructive** |
| **Revoked** | `.auth` removed, `_sealed.auth` written | — | n/a |

`_sealed.auth` is a deny-all sentinel: an *empty* `authorized_clients` dir makes tor serve
the onion to the world, so revoking the last client installs a freshly generated key that
is immediately discarded. Fail closed. It is cleared the moment a real client is authorized.

### The rule that was missing

> `beginPairing()` refuses when **`lastAuthedAt > 0`**.

Not "when a key exists" — a key is written the instant the QR is drawn, so refusing on
existence would strand the user with an expired code and no way to mint another. The test
is whether a device has *actually authenticated*.

**Why it matters.** `beginPairing()` mints a new X25519 keypair, **truncates**
`authorized_clients/phone.auth` over the old public key, and rotates the bearer token. A
phone that is already paired holds the old private half and is told nothing. To tor, an
un-authorized client is indistinguishable from no client at all — so there is no error
anywhere. Requests simply time out forever, `last_seen` stays `NEVER`, and both UIs look
healthy.

Observed: a rotation at 17:58:53 left `phone.auth` holding `76a2yia3…` while the phone held
the private half of `4swheovo…`. The only way to see it was to derive the public key from
the phone's stored key by hand.

The rotation had **no user behind it**: the pane called `beginPairing()` from a 2.5-second
poll timer (`if (ready && busy && pairUri === "")`). A working pairing could be destroyed by
a tick.

Rotation is now spelled **revoke → pair**, two explicit steps, with `Unpair` as the name of
the destructive one.

Covered by `pairing_stability.sh` (9/9) against a real client tor over a real onion.

---

## Interactions that are not obvious

**Pairing invalidates readiness.** `beginPairing()` calls `reload()`, which restarts tor so
it re-reads `authorized_clients` — and a restarted onion needs 30–60s to republish its
descriptor. So:

- the QR must not be shown until `ready` is true again, or every scan lands in a window
  where the onion is unreachable;
- two pairings in a row cannot happen without waiting for republish in between.

**The QR retires on success, not on expiry.** It used to stay on screen until the timer ran
out even after the phone was through, which reads as failure and invites a re-scan of a
spent code.

---

## Failure modes and their guards

| Failure | Guard | Test |
|---|---|---|
| Stale fatal log renders a stopped node as Error | intent checked before log scrape | `lifecycle_test.sh` L1 |
| Desktop stop shows as Error on the phone | both surfaces write `nodeIntent` | `lifecycle_test.sh` L1/L4 |
| Poll timer rotates a live pairing | `&& !root.paired`; module refuses | `pairing_stability.sh` S1a/S1b |
| Rotation cuts off a working phone | refuse when `lastAuthedAt > 0` | `pairing_stability.sh` S1b/S2 |
| Expired code cannot be re-minted | refusal keyed on *use*, not existence | `pairing_stability.sh` S3 |
| Revoked key keeps access | revoke + tor restart | `pairing_stability.sh` S4b, `pairing_e2e.sh` P4 |
| Empty `authorized_clients` serves the onion publicly | `_sealed.auth` sentinel | `pairing_e2e.sh` P3 |
| Unpaired client can reach the onion | client auth | `pairing_e2e.sh` P3 |
| Phone polls before tor bootstraps | `awaitReady()` | — *(needs a device)* |
| Cross-thread container corruption | `QMutex` on balance strings and `BlockStore` | — *(soak)* |

---

## Testing these safely

Every suite mints pairings, revokes clients and persists tokens. They now set
`XDG_DATA_HOME` to `~/.cache/node_remote-test-home` so they never touch the machine's real
state — without it, running the test for *"do not destroy the user's pairing"* destroys the
user's pairing.

The path is **stable, not `mktemp`**: tor's `DataDirectory` hangs off it, and a fresh dir
per run forces a cold consensus fetch that presents as an onion that never publishes.

Three traps worth knowing, each of which cost a debugging cycle:

- `logoscore` has **no `start` subcommand** — it is `-D -m <dir>` then `load-module`.
- The module's data dir is **`logos_host_qt`** under logoscore but **`.logos_host.elf`**
  under Basecamp. Hardcoding either points assertions at a directory the module never
  touched, and they fail while reading nothing.
- A killed run leaves tor holding its `DataDirectory` lock; the next run dies with
  *"another Tor process is running with the same data directory"*. Kill it **by PID** —
  `pkill -f` on the run-dir path also matches the test's own command line.
