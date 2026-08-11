#!/usr/bin/env fish
#
# Create the Node Remote production signing key. ONE TIME, EVER.
#
# Run this yourself — the passphrase must not pass through an agent, a log, or a shell
# history file. keytool prompts for it interactively and this script never handles it.
#
#   fish tools/create-signing-key.fish
#
# Android identifies an app by its signing certificate. This key is the app's identity for
# its whole life: lose it and no future build can upgrade an installed one — every user
# must uninstall and reinstall, losing their pairing, which lives in app storage. There is
# no recovery, no reset, no support channel. Back it up before you ship anything with it.

set -l DIR $HOME/.node-remote-signing
set -l KS   $DIR/node-remote-release.jks
set -l ALIAS node-remote

if test -e $KS
    echo "REFUSING: $KS already exists."
    echo "A second key would be a DIFFERENT app to Android. If you meant to inspect it:"
    echo "  keytool -list -v -keystore $KS -alias $ALIAS"
    exit 1
end

echo "Creating the Node Remote production key."
echo "  keystore: $KS"
echo "  alias:    $ALIAS"
echo "  validity: 10000 days (~27 years — it must outlive the app)"
echo
echo "You will be asked for a passphrase TWICE (keystore, then key)."
echo "Use the SAME strong passphrase for both and put it in your password manager now,"
echo "before you type it. Do not invent it at the prompt and hope to remember it."
echo

mkdir -p $DIR
chmod 700 $DIR

keytool -genkeypair -v \
    -keystore $KS \
    -alias $ALIAS \
    -keyalg RSA -keysize 4096 \
    -validity 10000 \
    -dname "CN=Node Remote, O=Node Remote, C=US"

or begin
    echo
    echo "keytool failed — nothing was created."
    exit 1
end

chmod 600 $KS

echo
echo "── Created. ──"
echo
echo "Now print the fingerprint and record it in docs/SIGNING.md."
echo "keytool will ask for the passphrase again — modern keystores are PKCS12, and even the"
echo "PUBLIC certificate cannot be read without it, so this cannot be automated away:"
echo
echo "  keytool -list -v -keystore $KS -alias $ALIAS | grep -E 'SHA256:|Valid from'"
echo
# Deliberately NOT run here with stderr hidden: doing that swallowed keytool's password
# prompt, and the fingerprint silently never printed.

echo
echo "── NEXT ─────────────────────────────────────────────────────────────────────"
echo "1. Add these to ~/.gradle/gradle.properties (NOT to the repo):"
echo
echo "   NODE_REMOTE_RELEASE_STORE_FILE=$KS"
echo "   NODE_REMOTE_RELEASE_STORE_PASSWORD=<your passphrase>"
echo "   NODE_REMOTE_RELEASE_KEY_ALIAS=$ALIAS"
echo "   NODE_REMOTE_RELEASE_KEY_PASSWORD=<your passphrase>"
echo
echo "   The NODE_REMOTE_ prefix is load-bearing: a bare RELEASE_STORE_FILE is already set"
echo "   globally in that file pointing at the PEERS keystore, and an unprefixed name would"
echo "   silently sign Node Remote with Peers' key."
echo
echo "2. Back up $KS AND the passphrase somewhere off this machine, in two places."
echo "   There is no recovery path."
echo
echo "3. Verify the build picks it up:"
echo "   fish tools/verify-signing.fish"
