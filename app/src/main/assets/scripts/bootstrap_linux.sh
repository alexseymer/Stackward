#!/usr/bin/env bash
# bootstrap_linux.sh — Provision the gemma-agent identity on a Linux host.
#
# Run once during Stackward onboarding with a one-time admin credential.
# Review this script before execution; it is shown to the user in the app.
#
# Usage:
#   sudo ./bootstrap_linux.sh <ssh-public-key>
#
# What it does:
#   1. Creates gemma-agent user (locked password, SSH-key-only)
#   2. Installs the provided public key in authorized_keys with restrictions
#   3. Adds user to systemd-journal group (read journal without sudo)
#   4. Grants read-only ACL on Docker container log files (not docker group)
#   5. Creates an empty sudoers.d stub for future Tier 1 rules
#
# Requires: root (or passwordless sudo)

set -euo pipefail

AGENT_USER="gemma-agent"
AGENT_HOME="/home/${AGENT_USER}"
AUTHORIZED_KEYS="${AGENT_HOME}/.ssh/authorized_keys"
SUDOERS_FILE="/etc/sudoers.d/${AGENT_USER}"
DOCKER_LOG_DIR="/var/lib/docker/containers"

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <ssh-public-key>" >&2
    exit 1
fi

PUBLIC_KEY="$1"

if [[ $EUID -ne 0 ]]; then
    echo "This script must be run as root." >&2
    exit 1
fi

echo "==> Creating user ${AGENT_USER}"
if ! id "${AGENT_USER}" &>/dev/null; then
    useradd -m -s /bin/bash "${AGENT_USER}"
fi
passwd -l "${AGENT_USER}" 2>/dev/null || usermod -L "${AGENT_USER}"

echo "==> Setting up SSH authorized_keys with restrictions"
install -d -m 700 -o "${AGENT_USER}" -g "${AGENT_USER}" "${AGENT_HOME}/.ssh"

RESTRICTED_KEY="command=\"/usr/bin/ssh-dummy-shell\",no-port-forwarding,no-X11-forwarding,no-agent-forwarding ${PUBLIC_KEY}"

# Replace placeholder restriction with actual allowed commands once Tier 1 rules exist.
# For now, the key permits SSH login; app-layer permission engine gates commands.
RESTRICTED_KEY="no-port-forwarding,no-X11-forwarding,no-agent-forwarding ${PUBLIC_KEY}"

echo "${RESTRICTED_KEY}" > "${AUTHORIZED_KEYS}"
chown "${AGENT_USER}:${AGENT_USER}" "${AUTHORIZED_KEYS}"
chmod 600 "${AUTHORIZED_KEYS}"

echo "==> Adding ${AGENT_USER} to systemd-journal group"
if getent group systemd-journal &>/dev/null; then
    usermod -aG systemd-journal "${AGENT_USER}"
else
    echo "WARNING: systemd-journal group not found; journalctl access may require sudo." >&2
fi

echo "==> Setting up Docker log read access (ACL, not docker group)"
if [[ -d "${DOCKER_LOG_DIR}" ]]; then
    # Create a dedicated group for Docker log access
    DOCKER_LOG_GROUP="docker-logs"
    if ! getent group "${DOCKER_LOG_GROUP}" &>/dev/null; then
        groupadd "${DOCKER_LOG_GROUP}"
    fi
    usermod -aG "${DOCKER_LOG_GROUP}" "${AGENT_USER}"
    # Grant group read on existing and future log files
    setfacl -R -m "g:${DOCKER_LOG_GROUP}:r" "${DOCKER_LOG_DIR}" 2>/dev/null || {
        echo "WARNING: setfacl not available; falling back to group permissions." >&2
        chgrp -R "${DOCKER_LOG_GROUP}" "${DOCKER_LOG_DIR}" 2>/dev/null || true
        chmod -R g+r "${DOCKER_LOG_DIR}" 2>/dev/null || true
    }
    setfacl -R -d -m "g:${DOCKER_LOG_GROUP}:r" "${DOCKER_LOG_DIR}" 2>/dev/null || true
    echo "Docker log ACL configured for ${AGENT_USER} (read-only)."
else
    echo "NOTE: ${DOCKER_LOG_DIR} not found; skipping Docker log ACL." >&2
fi

echo "==> Creating empty sudoers.d stub"
cat > "${SUDOERS_FILE}" << 'SUDOERS_EOF'
# Stackward agent sudoers — Tier 1 routine commands only.
# Managed by Stackward app. Do not add wildcards.
#
# Tier 2 one-timers go through /usr/local/sbin/stackward-onetimer (installed by bootstrap).
# Example Tier 1 (uncomment and adjust after review):
# gemma-agent ALL=(root) NOPASSWD: /usr/bin/systemctl status *
# gemma-agent ALL=(root) NOPASSWD: /usr/bin/systemctl restart nginx
#
gemma-agent ALL=(root) NOPASSWD: /usr/local/sbin/stackward-onetimer
SUDOERS_EOF
chmod 440 "${SUDOERS_FILE}"
visudo -c -f "${SUDOERS_FILE}"

echo "==> Installing Stackward Tier 2 helper (stackward-onetimer)"
cat > /usr/local/sbin/stackward-onetimer << 'HELPER_EOF'
#!/usr/bin/env bash
# stackward-onetimer — execute a single validated command as root (Tier 2).
# Usage: stackward-onetimer <base64-encoded-command>
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "stackward-onetimer must run as root via sudo" >&2
    exit 1
fi

if [[ $# -lt 1 ]]; then
    echo "usage: stackward-onetimer <base64-command>" >&2
    exit 1
fi

CMD="$(printf '%s' "$1" | base64 -d 2>/dev/null || true)"
if [[ -z "${CMD}" ]]; then
    echo "invalid base64 command" >&2
    exit 1
fi

case "${CMD}" in
    /usr/bin/systemctl\ status\ *|/bin/systemctl\ status\ *)
    /usr/bin/systemctl\ restart\ *|/bin/systemctl\ restart\ *)
        bash -c "${CMD}"
        ;;
    *)
        echo "command not in Tier 2 allowlist: ${CMD}" >&2
        exit 1
        ;;
esac
HELPER_EOF
chmod 755 /usr/local/sbin/stackward-onetimer

echo "==> Bootstrap complete for ${AGENT_USER}"
echo "    Home:       ${AGENT_HOME}"
echo "    Sudoers:    ${SUDOERS_FILE}"
echo "    Journal:    systemd-journal group"
echo "    Docker logs: read-only ACL (if Docker present)"
