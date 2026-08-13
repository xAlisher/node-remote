# Retro log — node-remote

Raw captures. Synthesised by `/retro` into PROJECT_KNOWLEDGE.md, basecamp-skills and
fieldcraft, then cleared.

> **This file did not exist during the session below.** Nothing was captured with `/log`
> while the work was hot; the entries were written at retro time from live session context
> — genuine root causes, but recovered rather than captured. `wins-and-fails.md` names this
> exact failure mode ("a repo with no retro-log.md is a repo whose lessons evaporate"), and
> it nearly happened here. The file now exists; use `/log fail` during the work.

## Week of 2026-08-12 — first build, repo, signing, and the crash

### Wins

- [project] **Found a SIGSEGV that three backtraces could not explain, by instrumenting
  instead of theorising.** Two hypotheses (balance timer interval, tor `reload()`) were
  both wrong and both plausible. A file trace showed `getNodeStatus: begin` and
  `balance: begin` interleaving 190ms apart, 20s before the fault — proving two threads,
  which no amount of code-reading had suggested. Extracted:
  `core-module-file-trace-crash`, `module-http-surface-thread-safety`.
- [project] **Verified the fix against the failure's own signature.** Every prior crash
  landed 77–80s after module load; the fixed build ran 15 minutes with the phone polling
  and the timer writing, traces interleaving without a fault. "It didn't crash once" would
  not have been evidence.
- [process] **Refused to `FORCE=1` a deploy over a running node, three times.** After
  killing the user's node twice, the installer gained a guard that stops the node first and
  refuses if it cannot. It blocked three subsequent deploys and each time the right answer
  was to wait for the user, not to override.
- [project] **Caught a `CN=Peers`-signed release before it could ship.** A global unprefixed
  `RELEASE_STORE_FILE` in `~/.gradle/gradle.properties` silently signed Node Remote with the
  Peers key — and it passed the signing gate, because a keystore genuinely was configured.
  `apksigner` caught it. Had it reached F-Droid, no user could ever have upgraded in place.
- [project] **Checked a privacy claim against the spec instead of shipping it.** The pane
  claimed nobody could "tell that it exists". A v3 address *is* the identity key, so anyone
  holding it can fetch and decrypt the descriptor's outer layer. The first spec lookup even
  said otherwise; a second lookup on address encoding settled it.

### Fails

- [process] **No `docs/retro-log.md` existed, so nothing was captured while it was hot.**
  Wrong action: built an entire repo (58 commits) with `docs/PRIVACY.md`, `SIGNING.md` and
  `TESTING.md` but no retro log, then relied on session memory at `/retro` time. Root cause:
  treated the retro log as a retro-time artifact rather than a during-work one — the exact
  thing `wins-and-fails.md` warns about. Only luck (an unsummarised session) preserved the
  root causes.
- [project] **Restarted Basecamp under a running node — twice.** Wrong action: the
  dev-install script SIGTERM'd Basecamp without stopping the node. Root cause: I equated
  "Basecamp exited cleanly" with "the node stopped cleanly". They are different: SIGTERM
  tears the module host out from under a node that never gets asked to stop. Evidence: a
  22MB unflushed RocksDB WAL vs ~70KB after a clean stop; the replay then overran
  Cryptarchia's 60s startup budget and the node sat in "Starting" indefinitely.
- [project] **Wired balance into `/v1/status` and deadlocked the route.** Wrong action:
  called `blockchain_module`'s synchronous wallet RPCs from inside a QHttpServer handler.
  Root cause: did not consider that the handler cannot return until the IPC replies, and the
  reply cannot arrive until the handler returns. Route went from instant to `HTTP 000` after
  30s. Caching on a timer fixed it — and introduced the thread race below.
- [project] **The race fix silently swallowed a wallet error.** Wrong action: on
  `wallet_get_balance` failure, cleared `m_balanceError` and returned, so the phone showed a
  bare "—" indistinguishable from "not fetched yet". Root cause: while restructuring for the
  mutex I moved error handling without re-reading what each early return communicated.
- [process] **Two diagnostic cycles produced nothing because `qInfo` is never captured.**
  Wrong action: instrumented with `qInfo`, deployed, waited for a crash, got zero
  `[node_remote]` lines. Root cause: assumed module logging reached the Basecamp log because
  `blockchain_module`'s stdout does — but that is `[out]` capture of a child process, not Qt
  logging from a plugin.
- [process] **Shipped a build with an undefined symbol.** Wrong action: declared the tracer
  `static` in one TU and `extern` in another. Root cause: shared libraries link with
  undefined symbols and fail only at first call — so "it built" felt like proof. Caught with
  `nm -D --undefined-only` before deploying, but only because I checked on a hunch.
- [process] **My own probe faked the result I was measuring.** Wrong action: polled
  `/v1/status` with the device token to check liveness; that authenticates, stamping
  last-seen and making the desktop report "Connected" — for my curl, not the phone. Root
  cause: chose the richest endpoint rather than the one with no side effects. Told the user
  the pane showed Connected before realising I had caused it.
- [process] **`adb install` fell back to "first device" and nearly hit the wrong phone.**
  Wrong action: `adb devices | head -1` when the Pixel dropped off USB. Root cause: wrote a
  convenience default instead of failing closed. It targeted the Xiaomi; nothing installed,
  but only by luck. Now selects by `ro.product.model` and refuses otherwise.
- [project] **Claimed a mitigation was a fix.** Wrong action: added a re-entrancy guard,
  called the crash "mitigated", deployed. It crashed again ~77s later. Root cause: reasoned
  from timing correlation (78s tick vs 80s crash) without testing the correlation — changing
  the interval to 30s should have moved the crash and did not, which I only noticed after
  the next crash.
- [process] **Diagnosed a silent auth failure by hypothesis for two rounds instead of
  deriving the key.** Wrong action: on "no data after pairing", reasoned about bootstrap
  timing, republish races and descriptor windows across two deploy cycles. Root cause: a
  Tor v3 client-auth mismatch produces *no error anywhere* — an un-authorized client is
  indistinguishable from no client — so every layer reports the generic symptom and the
  symptoms all point at timing. The decisive check took one command: derive the X25519
  public key from the private half the phone stored and compare it to
  `authorized_clients/phone.auth`. Do that FIRST whenever a client-authorized onion is
  unreachable; timing hypotheses only after the credential is settled.
- [project] **A poll timer performed a destructive action.** Wrong action: the pane called
  `beginPairing()` from a 2.5s tick whenever `ready && busy && pairUri === ""`. That call
  mints a new keypair, truncates `phone.auth` and rotates the bearer token — so a working
  pairing could be destroyed with no user behind it and no error at either end. Root cause:
  the destructive step was not named as destructive anywhere, and the guard that existed
  (`paired`) keyed on "a key exists" rather than "a device has used this key".
- [process] **Wrote a test for "do not destroy the user's pairing" that destroyed the
  user's pairing.** Wrong action: the suites ran against the machine's real module state,
  minting pairings, revoking clients and persisting tokens in the same directory a live
  phone depends on. Root cause: never asked where the module writes. Evidence had been
  visible for hours — a stray `pixel10` client from an earlier run, and a persisted
  `device_token` that silently overrode `NODE_REMOTE_TOKEN` and produced two "unauthorized"
  failures I nearly attributed to a code change. All suites now set `XDG_DATA_HOME`.
- [process] **Believed a code comment instead of the tool's documentation.** Wrong action:
  accepted `reload()`'s claim that "tor parses authorized_clients once, at startup" and
  designed around a full tor restart, which cost 2m20s of unreachable onion after every
  pairing — the entire "connected but no data" complaint. Root cause: the comment was
  confident and specific, so it read as researched. Tor re-reads them on SIGHUP; republish
  is now 2–4s. A comment asserting a dependency's behaviour is a claim to verify, not a fact.
- [process] **An optimisation silently weakened a security property, and "not 200" hid it.**
  Wrong action: switched revocation to SIGHUP along with pairing. A HUP leaves the revoked
  client's cached descriptor and intro points alive, so it still reaches the service and is
  stopped only by the bearer token. Root cause: the assertion was `!= 200`, so `000 → 401`
  passed. Caught only because the test PRINTS the code and I read it. Assertions on
  security properties must state the property (`000` = cannot reach), not its negation.
- [process] **`pkill -f` killed my own shell, again.** Wrong action: `pkill -f "tor -f
  /extra/tmp/node_remote"` to clear a stale lock; the pattern matched the bash command line
  containing it. Root cause: this exact trap is already recorded in memory for
  `logos_host` and I reached for the pattern form anyway. Kill by PID.
- [process] **Reported a build as verified from "BUILD SUCCESSFUL".** Wrong action: gradle
  said `BUILD SUCCESSFUL in 1s` after a Kotlin edit and I nearly took it. Root cause: a 1s
  build is up-to-date caching, not compilation. Also grepped one `classes.dex` of four and
  read 0 matches as "not in the build". Check the artifact, and check all of it.
- [env] **Hardcoded display server in the deploy script.** Wrong action: `WAYLAND_DISPLAY=
  wayland-0` when khidr had moved to X11 after a power cut. Qt aborted instantly with an
  EMPTY log, which reads as a broken build rather than a missing socket. Now detects.
