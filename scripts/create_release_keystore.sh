#!/usr/bin/env bash
# create_release_keystore.sh — make the release signing key (run once, locally).
#
# The key is created OUTSIDE the repo (default: ~/.stackward-release/release.keystore) and is never uploaded
# anywhere: scripts/sign_release.sh signs CI-built APKs with it on this machine. Passwords are random, stored in
# passwords.txt next to the keystore, and never printed.
#
# BACK UP the keystore file and passwords file somewhere safe. If they are lost you can never publish an update
# that installs over existing phones. Needs: keytool (JDK), openssl.
#
# Usage: scripts/create_release_keystore.sh

set -euo pipefail

DIR="${STACKWARD_RELEASE_DIR:-${HOME}/.stackward-release}"
STORE="${DIR}/release.keystore"
ALIAS="stackward-release"

for tool in keytool openssl; do
    command -v "${tool}" >/dev/null || { echo "error: ${tool} is required" >&2; exit 1; }
done
[[ ! -e "${STORE}" ]] || { echo "error: ${STORE} already exists; refusing to overwrite" >&2; exit 1; }

umask 077
mkdir -p "${DIR}"
STORE_PASS="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)"
KEY_PASS="${STORE_PASS}"

keytool -genkeypair -v -keystore "${STORE}" -storetype PKCS12 -alias "${ALIAS}" \
    -keyalg RSA -keysize 4096 -validity 36500 \
    -storepass "${STORE_PASS}" -keypass "${KEY_PASS}" \
    -dname "CN=Stackward release, O=Stackward" >/dev/null

printf 'store=%s\nalias=%s\nstorePassword=%s\nkeyPassword=%s\n' "${STORE}" "${ALIAS}" "${STORE_PASS}" "${KEY_PASS}" > "${DIR}/passwords.txt"

echo "Created ${STORE} (key never leaves this machine)."
echo "Back up ${DIR} (keystore + passwords.txt) now; it is the only copy."
echo "Phones with an older build installed must be uninstalled once: the new key cannot update the old one."
