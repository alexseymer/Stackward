#!/usr/bin/env bash
# sign_release.sh — sign the APK built by the "Release APK" workflow with YOUR local release key and publish it.
#
# The CI workflow only builds (debug-signed, never published). This script downloads its artifact, re-signs the
# APK with the key in ~/.stackward-release/ (see scripts/create_release_keystore.sh) and creates the GitHub
# Release, so the release key never leaves this machine.
#
# Needs: gh (authenticated), apksigner (Android SDK build-tools; ANDROID_HOME or on PATH), sha256sum.
# Usage: scripts/sign_release.sh [workflow-run-id]     (default: the latest successful run)

set -euo pipefail

REPO="${STACKWARD_REPO:-alexseymer/Stackward}"
DIR="${STACKWARD_RELEASE_DIR:-${HOME}/.stackward-release}"
STORE="${DIR}/release.keystore"
PASSWORDS="${DIR}/passwords.txt"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

die() { echo "error: $*" >&2; exit 1; }

find_apksigner() {
    if command -v apksigner >/dev/null 2>&1; then command -v apksigner; return; fi
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" newest
    if [[ -n "${sdk}" && -d "${sdk}/build-tools" ]]; then
        newest="$(find "${sdk}/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)"
        [[ -x "${newest}/apksigner" ]] && { echo "${newest}/apksigner"; return; }
    fi
    die "apksigner not found (set ANDROID_HOME or put build-tools on PATH)"
}

command -v gh >/dev/null || die "gh is required"
[[ -f "${STORE}" && -f "${PASSWORDS}" ]] || die "no release key in ${DIR}; run scripts/create_release_keystore.sh first"
APKSIGNER="$(find_apksigner)"

RUN_ID="${1:-$(gh run list --repo "${REPO}" --workflow release.yml --status success --limit 1 --json databaseId --jq '.[0].databaseId')}"
[[ -n "${RUN_ID}" && "${RUN_ID}" != "null" ]] || die "no successful Release APK workflow run found"
echo "==> Downloading the unsigned build from run ${RUN_ID}"
gh run download "${RUN_ID}" --repo "${REPO}" --name unsigned-apk --dir "${WORK}/in"

TAG="$(cat "${WORK}/in/TAG")"
BUILD_TYPE="$(cat "${WORK}/in/BUILD_TYPE")"
SUMMARY="$(cat "${WORK}/in/SUMMARY")"
ASSET="$(find "${WORK}/in" -maxdepth 1 -name 'app-*.apk' -printf '%f\n' | head -n 1)"
[[ -n "${ASSET}" ]] || die "artifact contains no APK"
if gh release view "${TAG}" --repo "${REPO}" >/dev/null 2>&1; then die "release ${TAG} already exists"; fi

get() { grep "^$1=" "${PASSWORDS}" | cut -d= -f2-; }
export KS_PASS KEY_PASS
KS_PASS="$(get storePassword)"
KEY_PASS="$(get keyPassword)"

echo "==> Signing ${ASSET} (${TAG}) with the local release key"
"${APKSIGNER}" sign --ks "${STORE}" --ks-key-alias "$(get alias)" \
    --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
    --out "${WORK}/${ASSET}" "${WORK}/in/${ASSET}"

VERIFY="$("${APKSIGNER}" verify --verbose --print-certs "${WORK}/${ASSET}")"
grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"${VERIFY}" || die "v2 signature missing"
if grep -qi 'CN=Android Debug' <<<"${VERIFY}"; then die "APK is still debug-signed"; fi
echo "${VERIFY}" | grep -i 'certificate SHA-256' | head -n 1

SHA="$(sha256sum "${WORK}/${ASSET}" | awk '{print $1}')"
BYTES="$(wc -c < "${WORK}/${ASSET}" | tr -d ' ')"
NOTES="${TAG} (${BUILD_TYPE})

${SUMMARY}

SHA256: ${SHA}
Size: ${BYTES} bytes

Install with ADB (most reliable on Pixel in 2026):
  adb install -r ${ASSET}

First install after the key change: adb uninstall dev.stackward.dogfood"

echo "==> Publishing ${TAG}"
gh release create "${TAG}" "${WORK}/${ASSET}" --repo "${REPO}" --target builds --title "${TAG}" --notes "${NOTES}"
echo "==> Done: https://github.com/${REPO}/releases/tag/${TAG}"
