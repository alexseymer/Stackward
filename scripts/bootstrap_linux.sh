#!/usr/bin/env bash
# bootstrap_linux.sh — OPTIONAL out-of-band admin helpers for stackward-agent.
#
# The Stackward app does NOT run this script. App onboarding only installs a
# public key into ~/.ssh/authorized_keys of a user-chosen SSH account
# (recommended default on Debian: sudo adduser stackward-agent).
#
# This script is for administrators who optionally want journal/Docker ACLs
# or narrow sudoers helpers on the host. Run as root (or a real admin with
# sudo) — not as stackward-agent. Review carefully before use.
#
# Usage (admin, on the server):
#   sudo ./bootstrap_linux.sh <ssh-public-key>
#
# Requires: root (or passwordless sudo) — never invoked by the mobile app.

set -euo pipefail

AGENT_USER="stackward-agent"
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
# stackward-agent ALL=(root) NOPASSWD: /usr/bin/systemctl status *
# stackward-agent ALL=(root) NOPASSWD: /usr/bin/systemctl restart nginx
#
stackward-agent ALL=(root) NOPASSWD: /usr/local/sbin/stackward-onetimer
stackward-agent ALL=(root) NOPASSWD: /usr/local/sbin/stackward-push-key *
stackward-agent ALL=(root) NOPASSWD: /usr/local/sbin/stackward-revoke-key *
stackward-agent ALL=(root) NOPASSWD: /usr/local/sbin/stackward-panic-revoke
stackward-agent ALL=(root) NOPASSWD: /usr/local/sbin/stackward-sudoers-snapshot
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

echo "==> Installing Stackward security helpers"
cat > /usr/local/sbin/stackward-push-key << 'HELPER_EOF'
#!/usr/bin/env bash
# stackward-push-key — append a validated SSH public key for stackward-agent.
set -euo pipefail
AGENT_USER="stackward-agent"
AUTH_KEYS="/home/${AGENT_USER}/.ssh/authorized_keys"
KEY_LINE="$1"
if [[ $EUID -ne 0 ]]; then echo "must run as root" >&2; exit 1; fi
if [[ -z "${KEY_LINE}" ]]; then echo "usage: stackward-push-key <openssh-pubkey-line>" >&2; exit 1; fi
case "${KEY_LINE}" in
    ssh-ed25519\ *|ssh-rsa\ *|ecdsa-sha2-*\ *) ;;
    *) echo "invalid public key format" >&2; exit 1 ;;
esac
install -d -m 700 -o "${AGENT_USER}" -g "${AGENT_USER}" "/home/${AGENT_USER}/.ssh"
touch "${AUTH_KEYS}"
chown "${AGENT_USER}:${AGENT_USER}" "${AUTH_KEYS}"
chmod 600 "${AUTH_KEYS}"
if grep -qF "${KEY_LINE}" "${AUTH_KEYS}"; then
    echo "key already present"
    exit 0
fi
echo "${KEY_LINE}" >> "${AUTH_KEYS}"
echo "key pushed"
HELPER_EOF
chmod 755 /usr/local/sbin/stackward-push-key

cat > /usr/local/sbin/stackward-revoke-key << 'HELPER_EOF'
#!/usr/bin/env bash
# stackward-revoke-key — remove authorized_keys line containing marker (base64 key body).
set -euo pipefail
AGENT_USER="stackward-agent"
AUTH_KEYS="/home/${AGENT_USER}/.ssh/authorized_keys"
MARKER="$1"
if [[ $EUID -ne 0 ]]; then echo "must run as root" >&2; exit 1; fi
if [[ -z "${MARKER}" ]]; then echo "usage: stackward-revoke-key <marker>" >&2; exit 1; fi
if [[ ! -f "${AUTH_KEYS}" ]]; then echo "no authorized_keys" >&2; exit 0; fi
tmp="$(mktemp)"
grep -vF "${MARKER}" "${AUTH_KEYS}" > "${tmp}" || true
mv "${tmp}" "${AUTH_KEYS}"
chown "${AGENT_USER}:${AGENT_USER}" "${AUTH_KEYS}"
chmod 600 "${AUTH_KEYS}"
echo "key revoked"
HELPER_EOF
chmod 755 /usr/local/sbin/stackward-revoke-key

cat > /usr/local/sbin/stackward-panic-revoke << 'HELPER_EOF'
#!/usr/bin/env bash
# stackward-panic-revoke — emergency wipe of stackward-agent authorized_keys.
set -euo pipefail
AGENT_USER="stackward-agent"
AUTH_KEYS="/home/${AGENT_USER}/.ssh/authorized_keys"
if [[ $EUID -ne 0 ]]; then echo "must run as root" >&2; exit 1; fi
install -d -m 700 -o "${AGENT_USER}" -g "${AGENT_USER}" "/home/${AGENT_USER}/.ssh"
: > "${AUTH_KEYS}"
chown "${AGENT_USER}:${AGENT_USER}" "${AUTH_KEYS}"
chmod 600 "${AUTH_KEYS}"
echo "all agent keys revoked"
HELPER_EOF
chmod 755 /usr/local/sbin/stackward-panic-revoke

cat > /usr/local/sbin/stackward-sudoers-snapshot << 'HELPER_EOF'
#!/usr/bin/env bash
# stackward-sudoers-snapshot — output active sudoers.d rules for Tier 1 review.
set -euo pipefail
SUDOERS_FILE="/etc/sudoers.d/stackward-agent"
if [[ $EUID -ne 0 ]]; then echo "must run as root" >&2; exit 1; fi
if [[ ! -f "${SUDOERS_FILE}" ]]; then
    echo "# sudoers file missing" >&2
    exit 1
fi
cat "${SUDOERS_FILE}"
HELPER_EOF
chmod 755 /usr/local/sbin/stackward-sudoers-snapshot

echo "==> Installing check.sh anomaly detector"
install -d -m 750 -o "${AGENT_USER}" -g "${AGENT_USER}" "${AGENT_HOME}/.stackward"
cat > "${AGENT_HOME}/.stackward/check.sh" << 'CHECK_SCRIPT_EOF'
#!/usr/bin/env bash
# ~/.stackward/check.sh — host anomaly detection for Stackward
#
# Queries system state (logs, disk, memory, services, security) and returns
# structured JSON with detected issues and suggested improvements.
#
# Installed by: scripts/bootstrap_linux.sh (app onboarding invokes this)
# Called by: Stackward Android app via SSH, on-demand or on schedule

set -uo pipefail

: "${SYSTEMD_JOURNAL_LINES:=100}"
: "${DOCKER_LOG_LINES:=50}"
: "${DISK_WARNING_PCT:=85}"
: "${DISK_CRITICAL_PCT:=95}"
: "${MEM_WARNING_PCT:=80}"

# Core detection functions
detect_disk_issues() {
    df -h 2>/dev/null | grep -E '^/' | while IFS= read -r line; do
        local usage_pct mount
        usage_pct=$(echo "$line" | awk '{print $5}' | sed 's/%//')
        mount=$(echo "$line" | awk '{print $6}')

        if [[ $usage_pct -gt $DISK_CRITICAL_PCT ]]; then
            echo "  { \"type\": \"disk\", \"severity\": \"critical\", \"message\": \"$mount at ${usage_pct}% capacity\" }"
        elif [[ $usage_pct -gt $DISK_WARNING_PCT ]]; then
            echo "  { \"type\": \"disk\", \"severity\": \"high\", \"message\": \"$mount at ${usage_pct}%\" }"
        fi
    done || true
}

detect_memory_issues() {
    if [[ -f /proc/meminfo ]]; then
        local memtotal memavail mem_used_pct
        memtotal=$(grep MemTotal /proc/meminfo | awk '{print $2}')
        memavail=$(grep MemAvailable /proc/meminfo | awk '{print $2}')
        mem_used_pct=$(( (memtotal - memavail) * 100 / memtotal ))

        if [[ $mem_used_pct -gt $MEM_WARNING_PCT ]]; then
            echo "  { \"type\": \"memory\", \"severity\": \"high\", \"message\": \"Memory usage at ${mem_used_pct}%\" }"
        fi
    fi
}

detect_service_issues() {
    if command -v systemctl &>/dev/null; then
        systemctl list-units --state=failed --no-pager --plain 2>/dev/null | grep -v '^UNIT' | while IFS= read -r line; do
            local unit
            unit=$(echo "$line" | awk '{print $1}')
            [[ -z $unit ]] && continue
            echo "  { \"type\": \"service\", \"severity\": \"critical\", \"message\": \"Unit $unit failed\" }"
        done || true
    fi
}

detect_security_issues() {
    # SSH password auth check
    if [[ -f /etc/ssh/sshd_config ]]; then
        if grep -qE '^\s*PasswordAuthentication\s+yes' /etc/ssh/sshd_config 2>/dev/null; then
            echo "  { \"type\": \"security\", \"severity\": \"critical\", \"message\": \"SSH password authentication enabled\" }"
        fi
    fi

    # Check for excessive open ports
    if command -v ss &>/dev/null; then
        local port_count
        port_count=$(ss -tlnp 2>/dev/null | grep -c LISTEN || echo "0")
        if [[ $port_count -gt 20 ]]; then
            echo "  { \"type\": \"security\", \"severity\": \"medium\", \"message\": \"High number of open listening ports ($port_count)\" }"
        fi
    fi
}

detect_log_errors() {
    # Systemd journal errors
    if command -v journalctl &>/dev/null; then
        local error_count
        error_count=$(journalctl -n "$SYSTEMD_JOURNAL_LINES" --priority=err --no-pager 2>/dev/null | wc -l || echo "0")
        if [[ $error_count -gt 5 ]]; then
            echo "  { \"type\": \"logs\", \"severity\": \"medium\", \"message\": \"Multiple journal errors in last $SYSTEMD_JOURNAL_LINES entries\" }"
        fi
    fi

    # Docker container errors
    if command -v docker &>/dev/null; then
        local failing_containers
        failing_containers=$(docker ps --filter "status=exited" --format "{{.Names}}" 2>/dev/null || echo "")
        if [[ -n $failing_containers ]]; then
            local count
            count=$(echo "$failing_containers" | wc -l)
            echo "  { \"type\": \"docker\", \"severity\": \"medium\", \"message\": \"$count container(s) exited\" }"
        fi
    fi
}

detect_suggestions() {
    echo "  { \"id\": \"review-logs\", \"risk\": \"safe\", \"action\": \"tail_journal\", \"reason\": \"Inspect recent journal errors\" }"
    echo "  { \"id\": \"check-services\", \"risk\": \"safe\", \"action\": \"list_failed_services\", \"reason\": \"Review failed systemd units\" }"

    # Suggest disk cleanup if high
    if df -h 2>/dev/null | grep -E '^/' | awk '{print $5}' | sed 's/%//' | grep -qE '([89][0-9]|100)'; then
        echo "  { \"id\": \"cleanup-disk\", \"risk\": \"risky\", \"action\": \"cleanup_old_logs\", \"reason\": \"Remove old log files to free disk space\" }"
    fi

    # Suggest SSH hardening if password auth is on
    if [[ -f /etc/ssh/sshd_config ]] && grep -qE '^\s*PasswordAuthentication\s+yes' /etc/ssh/sshd_config 2>/dev/null; then
        echo "  { \"id\": \"disable-ssh-pwd\", \"risk\": \"risky\", \"action\": \"disable_ssh_password_auth\", \"reason\": \"Disable SSH password authentication\" }"
    fi
}

# Main
main() {
    local timestamp hostname
    timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    hostname=$(hostname -f 2>/dev/null || hostname)

    {
        echo "{"
        echo "  \"timestamp\": \"$timestamp\","
        echo "  \"hostname\": \"$hostname\","
        echo "  \"issues\": ["

        # Collect all issues and join with commas
        {
            detect_disk_issues
            detect_memory_issues
            detect_service_issues
            detect_security_issues
            detect_log_errors
        } | sed '$!s/$/,/'

        echo "  ],"
        echo "  \"suggestions\": ["

        # Collect all suggestions and join with commas
        detect_suggestions | sed '$!s/$/,/'

        echo "  ]"
        echo "}"
    }
}

main "$@"
CHECK_SCRIPT_EOF
chmod 755 "${AGENT_HOME}/.stackward/check.sh"
chown "${AGENT_USER}:${AGENT_USER}" "${AGENT_HOME}/.stackward/check.sh"

echo "==> Bootstrap complete for ${AGENT_USER}"
echo "    Home:       ${AGENT_HOME}"
echo "    Check script: ${AGENT_HOME}/.stackward/check.sh"
echo "    Sudoers:    ${SUDOERS_FILE}"
echo "    Journal:    systemd-journal group"
echo "    Docker logs: read-only ACL (if Docker present)"
