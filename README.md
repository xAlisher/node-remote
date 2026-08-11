# Node Remote (Android)

Remote command centre for a Logos blockchain node running in Basecamp, reached over a
**Tor v3 onion service with client authorization**.

Pair by scanning the QR that `node_remote`'s `beginPairing()` produces; the phone
registers the client-auth key via `ONION_CLIENT_AUTH_ADD` and can then reach the onion.
Without that key a device cannot connect to the service, fetch its descriptor, or
confirm it exists.

## Status — spike proven on hardware

Verified on a Pixel 10 (GrapheneOS), 2026-08-11, against a live node:

```
auto: starting tor
SOCKS → 127.0.0.1:39267
auto: client auth registered
auto: STATUS_OK {"height":17491,"peers":46,"state":"Online","phase":"Following",…}
```

~7s from launch to live node status on a warm Tor cache.

## Build + drive over adb

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# desktop: publish an onion and pair a device
bash ../logos-node-remote/node_remote/tests/serve_for_phone.sh pixel10

# phone: run the whole flow with no screen taps
adb shell am start -n co.logos.noderemote/.MainActivity \
  --es uri '<lgnode://…>' --es token '<tok>' --ez auto true
adb logcat -s NodeRemoteApp:V
```

`am start` on an already-running activity will NOT re-run the flow (Compose's
`LaunchedEffect` doesn't recompose) — `adb shell am force-stop co.logos.noderemote` first.

## Notes for the next build

- `packaging { jniLibs { useLegacyPackaging = true } }` is load-bearing: kmp-tor execs the
  tor BINARY, and API 28+ blocks exec from the app data dir, so it must land in
  nativeLibraryDir. Without it tor never starts.
- Build with JDK 17. The JDK 21 package here is a JRE (no `javac`), and Gradle fails with
  "Toolchain … does not provide the required capabilities: [JAVA_COMPILER]".
- `libtorexec.so` logs a stream of SELinux `avc: denied { ioctl }` warnings on tcp_socket.
  They are benign — tor works — but they make logcat noisy and look alarming.
- Observers for `RuntimeEvent.STATE` / `LOG.WARN` are not optional. Without them a tor
  that fails is completely silent: no LISTENERS, no ERROR, just an app that sits there.

## Not built yet

QR scanning (CameraX + ML Kit), foreground service, status polling UI, notifications,
device management, start/stop controls.
