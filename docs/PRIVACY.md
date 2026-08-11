# Privacy

> **Not an official Logos product.** Independent project by [@alisher](https://x.com/alisher),
> not made or endorsed by Logos or IFT. The privacy properties described below are properties
> of *this* app and of Tor — they say nothing about Logos software generally.

What Node Remote's Tor link actually protects, and what it does not.

This page is written against [rend-spec-v3](https://spec.torproject.org/rend-spec/). Where
we previously shipped a stronger claim than the spec supports, that is called out rather
than quietly corrected — the earlier wording is in the git history, and someone who read it
deserves to know what changed.

## What is on the wire

Your node's Basecamp module serves a small JSON API on **loopback only** — it never binds a
public interface. A Tor v3 onion service publishes that loopback port, with **client
authorization** (also called restricted discovery) enabled.

The phone runs its own embedded Tor (kmp-tor, no Orbot, no Google Play Services), holds the
X25519 client-auth private key from the pairing QR, and reaches the onion over a Tor
circuit.

## What this protects

**Nobody without the key can connect.** Client authorization means the introduction points
live in the descriptor's *inner* encrypted layer, which can only be decrypted with an
authorized client's X25519 private key. Without it there is no way to reach the service at
all — not a rejected request, no response.

**The content is end-to-end encrypted and forward-secret**, and the `.onion` address
authenticates the desktop: the address *is* the service's ed25519 public identity key, so
there is nothing to impersonate and no certificate authority to trust.

**There is no port forwarding and no public IP exposure.** Your home IP is never handed to
the phone, and no inbound port is opened on your router.

**Revocation is immediate and unilateral.** Removing a device deletes its key from
`authorized_clients/` and reloads tor. The onion stops answering that device, and other
paired devices are unaffected.

**The address is in no directory.** HSDirs index descriptors by a *blinded* key that rotates
each time period, so the service cannot be enumerated or searched for.

## What this does NOT protect

**Your ISP can see that you use Tor — on both ends.** Tor is not a VPN and does not claim to
be. An observer on your home connection sees a Tor connection; an observer on the phone's
connection sees the same. Neither sees what you reached or that the two are related. If
being *seen using Tor* is itself your threat, this design does not solve it, and nothing in
the app will.

**Anyone who learns the `.onion` address can confirm the service exists.** ← *We got this
wrong.* The pane used to say nobody could "connect to it, look it up, or tell that it
exists". The last clause is false.

A v3 address is `base32(PUBKEY | CHECKSUM | VERSION)` — it *contains* the ed25519 identity
key. The descriptor's **superencrypted (outer)** layer is encrypted to a credential derived
from that identity key. So anyone holding the address can fetch the descriptor from an HSDir
and decrypt the outer layer, which is enough to see that the service is published and live.
Only the **inner** layer, carrying the introduction points, needs a client key.

They still cannot connect. But "cannot tell it exists" was never true for someone with the
address — and the address is printed in the pairing pane, sits in the pairing URI behind a
Copy button, and is encoded in the QR.

**The pairing QR is full access until it expires.** It carries the client-auth private key
and the bearer token in plaintext. Anyone who photographs your screen within the 120-second
window can pair. This is why the 6-digit SAS exists: confirm the digits on the phone match
the desktop before trusting the pairing.

**Anything on the desktop can reach the loopback API** if it has the bearer token. The token
is stored `0600`, but this is not a defence against malware already running as your user.

**Traffic timing is not hidden.** The app polls on a fixed interval. Someone observing both
ends of a Tor circuit — a global adversary — could correlate timing. Tor has never claimed
to defeat that.

## Data we collect

None. There is no analytics, no crash reporting, no telemetry, and no network destination
other than your own node over Tor. The app has no account and no server.

What is stored on the phone: the pairing URI (onion address, client-auth key, bearer token)
and your notification preferences. Nothing else. "Disconnect" removes them.

What is stored on the desktop: the onion service keys, `authorized_clients/`, and the bearer
token — all under the module's own data directory, owner-readable only.

## Permissions the app asks for

| Permission | Why |
|---|---|
| `INTERNET` | Reach your node over Tor |
| `CAMERA` | Scan the pairing QR — requested only when you press *Scan QR*, and the *Enter URI* path avoids it entirely |
| `POST_NOTIFICATIONS` | Node status notifications, if you enable them |
| `FOREGROUND_SERVICE` (`dataSync`) | Keep Tor up and poll while the app is backgrounded |

No location, no contacts, no storage, no account access.

## Reporting a problem

Security issues: open an issue at
<https://github.com/xAlisher/node-remote/issues>, or reach the maintainer at
[@alisher](https://x.com/alisher). This is a pre-alpha project maintained by one person —
please set expectations accordingly.
