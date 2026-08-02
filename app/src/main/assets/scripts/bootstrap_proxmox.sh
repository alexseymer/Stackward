#!/usr/bin/env bash
# bootstrap_proxmox.sh — Create a scoped Proxmox API token for Stackward.
#
# Run on a Proxmox VE host during onboarding (after bootstrap_linux.sh or
# standalone if SSH-only access is not needed for Proxmox API calls).
#
# Usage:
#   ./bootstrap_proxmox.sh
#
# What it does:
#   1. Creates a custom Proxmox role with minimal privileges
#   2. Creates gemma-agent@pve user (if not exists)
#   3. Assigns the role to the user
#   4. Generates a scoped API token and prints it (store in Android Keystore)
#
# Tier mapping:
#   Tier 1 (read):    VM.Monitor, VM.Audit, Sys.Audit
#   Tier 2 (power):   VM.PowerMgmt (start/stop/restart — confirmed in app)
#   Tier 3 (blocked): VM.Config.*, VM.Allocate, Datastore.Allocate
#
# Requires: root on Proxmox host (or pveum access)

set -euo pipefail

PVE_USER="gemma-agent@pve"
ROLE_ID="stackward-agent"
TOKEN_NAME="stackward"

echo "==> Creating custom role: ${ROLE_ID}"
pveum role add "${ROLE_ID}" \
    -privs "VM.Monitor,VM.Audit,VM.PowerMgmt,Sys.Audit" \
    2>/dev/null || {
    echo "Role ${ROLE_ID} may already exist; updating privileges."
    pveum role modify "${ROLE_ID}" \
        -privs "VM.Monitor,VM.Audit,VM.PowerMgmt,Sys.Audit"
}

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
