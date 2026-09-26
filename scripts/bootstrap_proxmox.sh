#!/usr/bin/env bash
# bootstrap_proxmox.sh — OPTIONAL out-of-band Proxmox API token setup.
#
# The Stackward app does NOT run this script (no root/sudo from the app).
# An administrator runs it on the Proxmox host when API access is needed.
#
# Usage:
#   ./bootstrap_proxmox.sh
#
# What it does:
#   1. Creates a custom Proxmox role with minimal privileges
#   2. Creates stackward-agent@pve user (if not exists)
#   3. Assigns the role to the user
#   4. Generates a scoped API token and prints it (store in Android Keystore)
#
# Tier mapping (PVE 9+):
#   Tier 1 (read):    VM.Audit, Sys.Audit
#   Tier 2 (power):   VM.PowerMgmt (start/stop/restart — confirmed in app)
#   Tier 3 (blocked): VM.Config.*, VM.Allocate, Datastore.Allocate
#
# Note: VM.Monitor was dropped in PVE 9 (replaced by Sys.Audit / VM.GuestAgent.*).
# Older hosts fall back to a legacy privilege set that still includes VM.Monitor.
#
# Requires: root on Proxmox host (or pveum access)

set -euo pipefail

PVE_USER="stackward-agent@pve"
ROLE_ID="stackward-agent"
TOKEN_NAME="stackward"

# PVE 9+: VM.Monitor removed. Keep guest-agent out of scope for v1 (read/power only).
PRIVS_MODERN="VM.Audit,VM.PowerMgmt,Sys.Audit"
# PVE 8 and earlier.
PRIVS_LEGACY="VM.Monitor,VM.Audit,VM.PowerMgmt,Sys.Audit"

apply_role_privs() {
    local privs="$1"
    if pveum role add "${ROLE_ID}" -privs "${privs}" 2>/dev/null; then
        return 0
    fi
    pveum role modify "${ROLE_ID}" -privs "${privs}"
}

echo "==> Creating custom role: ${ROLE_ID}"
if apply_role_privs "${PRIVS_MODERN}" 2>/dev/null; then
    echo "Role ${ROLE_ID} privileges: ${PRIVS_MODERN}"
else
    echo "Modern privileges rejected; falling back to legacy set (includes VM.Monitor)."
    apply_role_privs "${PRIVS_LEGACY}"
    echo "Role ${ROLE_ID} privileges: ${PRIVS_LEGACY}"
fi

echo "==> Creating Proxmox user: ${PVE_USER}"
pveum user add "${PVE_USER}" \
    --comment "Stackward on-device agent (scoped)" \
    2>/dev/null || echo "User ${PVE_USER} already exists."

echo "==> Assigning role ${ROLE_ID} to ${PVE_USER}"
pveum aclmod / -user "${PVE_USER}" -role "${ROLE_ID}"

echo "==> Generating API token: ${PVE_USER}!${TOKEN_NAME}"
# Token secret is printed once; Stackward parses STACKWARD_TOKEN_JSON from stdout.
TOKEN_JSON="$(pveum user token add "${PVE_USER}" "${TOKEN_NAME}" \
    --privsep 1 \
    --comment "Stackward mobile agent token" \
    --output-format json)"
echo "STACKWARD_TOKEN_JSON=${TOKEN_JSON}"

echo ""
echo "==> Proxmox bootstrap complete."
echo "    User:  ${PVE_USER}"
echo "    Role:  ${ROLE_ID}"
echo "    Token: ${PVE_USER}!${TOKEN_NAME}"
echo ""
echo "Token secret captured by Stackward — not shown again."
