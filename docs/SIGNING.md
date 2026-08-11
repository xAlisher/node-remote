# Signing

Node Remote's release APK must be signed with **its own** production key. This page is the
staging doc: it says how to create that key, how the build finds it, and the one mistake
that is already known to happen on this machine.

Nothing here is committed to the repo. The keystore and its passwords are maintainer-held.

## Why the key matters more than it looks

Android identifies an app by its signing certificate. An APK signed with a different key is
a *different app* to the system: it cannot upgrade an installed one in place. If a
debug-signed or wrong-key build ever reaches F-Droid, every user who installed it must
uninstall and reinstall to get the real one — losing their pairing in the process, because
the pairing lives in app storage.

So the key is created **once** and never rotated. Losing it means every user starts over.

## The known trap on this machine ⚠

`~/.gradle/gradle.properties` already contains a **global**:

```
RELEASE_STORE_FILE=/home/alisher/.peers-signing/peers-release.jks
```

That is the **Peers** keystore, and Gradle applies it to every project on the machine. The
first release build of Node Remote picked it up and produced an APK signed
`CN=Peers, O=Peers, C=US` — silently, with no warning, and it passed the signing gate
because a keystore *was* configured. Verified with `apksigner`, not assumed.

For that reason Node Remote's credentials are **app-scoped**:

```
NODE_REMOTE_RELEASE_STORE_FILE
NODE_REMOTE_RELEASE_STORE_PASSWORD
NODE_REMOTE_RELEASE_KEY_ALIAS
NODE_REMOTE_RELEASE_KEY_PASSWORD
```

Never reintroduce the unprefixed names here.

## The key (created 2026-08-12)

```
Owner:      CN=Node Remote, O=Node Remote, C=US
Algorithm:  RSA 4096, SHA384withRSA
Valid:      2026-08-12 → 2053-12-27
Keystore:   ~/.node-remote-signing/node-remote-release.jks   (0600, PKCS12)
Alias:      node-remote

SHA-256 (keytool form):
  6D:80:DB:34:76:BC:AD:A7:8F:EF:A3:A1:6B:23:09:FD:1C:F8:30:E5:FC:83:6F:3E:9C:6C:48:67:80:51:AE:74
SHA-256 (apksigner form):
  6d80db3476bcada78fefa3a16b2309fd1cf830e5fc836f3e9c6c48678051ae74
```

Both forms are recorded because the two tools disagree on formatting — keytool prints
colon-separated uppercase, apksigner prints bare lowercase — and a mismatch between them is
a formatting difference, not a different key.

**Verify against the DIGEST, never the name.** `CN=Node Remote` proves nothing: anyone can
generate a key with that subject in ten seconds. The SHA-256 is the identity.

## Recreating the key

Already done — do NOT run this again; `tools/create-signing-key.fish` refuses if the
keystore exists. Kept for the record:

```
mkdir -p ~/.node-remote-signing
keytool -genkeypair -v -keystore ~/.node-remote-signing/node-remote-release.jks -alias node-remote -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Node Remote, O=Node Remote, C=US"
```

`-validity 10000` is ~27 years; F-Droid and Play both expect a key that outlives the app.
Use a strong passphrase and store it in your password manager, not a file.

Then back it up **off this machine** — the keystore file *and* the passphrase, in two
places. There is no recovery path.

## Telling the build about it

Add to `~/.gradle/gradle.properties` (which is outside the repo):

```
NODE_REMOTE_RELEASE_STORE_FILE=/home/alisher/.node-remote-signing/node-remote-release.jks
NODE_REMOTE_RELEASE_STORE_PASSWORD=…
NODE_REMOTE_RELEASE_KEY_ALIAS=node-remote
NODE_REMOTE_RELEASE_KEY_PASSWORD=…
```

Environment variables of the same names also work. Both are supported deliberately:
`findProperty` alone does **not** see exported env vars (only `ORG_GRADLE_PROJECT_*`), so a
release job that exported the variables would otherwise fall through to the debug key.

## The gate

```
cd node-remote-android && ./gradlew assembleRelease -PassertReleaseSigned
```

Without the keystore configured this **fails and produces no APK**:

```
NODE_REMOTE_RELEASE_STORE_FILE not set — refusing to ship a DEBUG-SIGNED release.
```

Verified — that is observed output, not intent. A plain `assembleRelease` without the flag
still builds, warns loudly, and uses the debug key; that path is for local testing only.

## Verify before publishing — every time

```
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | grep -E "DN|SHA-256"
```

Expect `CN=Node Remote`. If it says `CN=Peers`, the global property leaked in again — stop,
do not publish, and check the property names.

Expect:

```
Signer #1 certificate DN:      CN=Node Remote, O=Node Remote, C=US
Signer #1 certificate SHA-256: 6d80db3476bcada78fefa3a16b2309fd1cf830e5fc836f3e9c6c48678051ae74
```

`tools/verify-signing.fish` checks the digest automatically and refuses to pass on
anything else.

## F-Droid

The repo at `~/basecamp/fdroid` is signed with its own separate index key
(`keystore.p12`), fingerprint `9283C4E3DAB31E68675B643AE38222358541431AD07295B6DF4A4C6D2ACCCF32`.
That is the repo's identity, distinct from the app's signing key — both matter, neither
substitutes for the other.
