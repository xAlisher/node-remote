# logos-node-remote

Remote command surface for the Logos blockchain node running in Logos Basecamp,
served over a **Tor v3 onion service with client authorization**.

- `node_remote/` — `type: core`. Onion service + loopback JSON API + node probe.
- `node_remote_ui/` — `type: ui_qml`. Pairing (QR) and device management. *(not yet built)*

The Android client is **Node Remote**.

## Privacy property

With client authorization enabled, a party that does not hold an authorized X25519
private key **cannot connect to the onion, cannot fetch its descriptor, and cannot
confirm it exists**. This is proven by a controlled experiment, not asserted:

```
tests/client_auth_test.sh
  Phase A  no authorized_clients  → anonymous Tor client gets HTTP 200   (control)
  Phase B  authorized_clients set → anonymous Tor client gets 000        (no response)
```

A 401 in Phase B would be a FAILURE — the requirement is no response at all.

## Test

```bash
nix build .#packages.x86_64-linux.default   # from node_remote/
bash node_remote/tests/headless_test.sh     # 11 assertions, no Basecamp, no display
bash node_remote/tests/client_auth_test.sh  # the privacy claim
```
