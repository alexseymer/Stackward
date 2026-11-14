#!/usr/bin/env bash
# create_release_keystore.sh — make the release signing key and store it as GitHub secrets (run once, locally).
#
# The key is created OUTSIDE the repo (default: ~/.stackward-release/release.keystore) and the four secrets the
# Release APK workflow needs are uploaded with the GitHub CLI: DOGFOOD_KEYSTORE_BASE64, DOGFOOD_STORE_PASSWORD,
# DOGFOOD_KEY_ALIAS, DOGFOOD_KEY_PASSWORD. Passwords are random and never printed.
#
# BACK UP the keystore file and passwords file somewhere safe. If they are lost you can never publish an update
# that installs over existing phones. Needs: keytool (JDK), openssl, gh (authenticated, admin on the repo).
#
# Usage: scripts/create_release_keystore.sh [owner/repo]

set -euo pipefail

REPO="${1:-alexseymer/Stackward}"
DIR="${STACKWARD_RELEASE_DIR:-${HOME}/.stackward-release}"
STORE="${DIR}/release.keystore"
ALIAS="stackward-release"

for tool in keytool openssl gh base64; do
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

base64 -w0 "${STORE}" | gh secret set DOGFOOD_KEYSTORE_BASE64 --repo "${REPO}"
printf '%s' "${STORE_PASS}" | gh secret set DOGFOOD_STORE_PASSWORD --repo "${REPO}"
printf '%s' "${ALIAS}" | gh secret set DOGFOOD_KEY_ALIAS --repo "${REPO}"
printf '%s' "${KEY_PASS}" | gh secret set DOGFOOD_KEY_PASSWORD --repo "${REPO}"

echo "Created ${STORE} and uploaded the four secrets to ${REPO}."
echo "Back up ${DIR} (keystore + passwords.txt) now; it is the only copy."
echo "Phones with an older build installed must be uninstalled once: the new key cannot update the old one."
