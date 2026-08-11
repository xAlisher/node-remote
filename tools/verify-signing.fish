#!/usr/bin/env fish
#
# Prove the release build uses the NODE REMOTE key and not something else.
#
#   fish tools/verify-signing.fish
#
# This exists because the failure it catches is silent and expensive. The first release
# build of this app came out signed "CN=Peers, O=Peers, C=US" — because a global
# RELEASE_STORE_FILE in ~/.gradle/gradle.properties points at the Peers keystore and Gradle
# applies it to every project on the machine. It built cleanly and passed the signing gate.
# Nothing would have gone wrong until users could not upgrade.

set -l ROOT (dirname (status --current-filename))/..
cd $ROOT/node-remote-android

echo "── Building a signed release (hard-fails if the key is missing) ──"
./gradlew assembleRelease -PassertReleaseSigned
or begin
    echo
    echo "FAILED — the production keystore is not configured."
    echo "Run: fish tools/create-signing-key.fish   (then set the gradle properties)"
    exit 1
end

set -l APK app/build/outputs/apk/release/app-release.apk
if not test -e $APK
    echo "No APK at $APK — build reported success but produced nothing."
    exit 1
end

set -l APKSIGNER (ls $HOME/Android/Sdk/build-tools/*/apksigner 2>/dev/null | tail -1)
if test -z "$APKSIGNER"
    echo "apksigner not found under ~/Android/Sdk/build-tools — cannot verify. STOP."
    exit 1
end

echo
echo "── Signer certificate ──"
$APKSIGNER verify --print-certs $APK | grep -E "certificate DN|SHA-256 digest"

set -l DN ($APKSIGNER verify --print-certs $APK | grep "certificate DN" | head -1)

echo
if string match -q '*CN=Node Remote*' -- $DN
    echo "OK — signed by the Node Remote key. Safe to publish."
    echo "Record the SHA-256 above in docs/SIGNING.md if you have not already."
else if string match -q '*CN=Peers*' -- $DN
    echo "STOP. This APK is signed with the PEERS key."
    echo "The global RELEASE_STORE_FILE leaked in. Check that app/build.gradle reads the"
    echo "NODE_REMOTE_-prefixed property names. DO NOT PUBLISH THIS APK."
    exit 1
else
    echo "STOP. Unexpected signer: $DN"
    echo "Do not publish until you know whose key this is."
    exit 1
end
