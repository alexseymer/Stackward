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
# Stackward agent sudoers — key-management helpers only. The app is read-only
# (PRD D5): there is no sudo helper for running commands on the host.
# Managed by Stackward app. Do not add wildcards.
#
stackward-agent ALL=(root) NOPASSWD: /usr/local/sbin/stackward-push-key *
stackward-agent ALL=(root) NOPASSWD: /usr/local/sbin/stackward-revoke-key *
stackward-agent ALL=(root) NOPASSWD: /usr/local/sbin/stackward-panic-revoke
stackward-agent ALL=(root) NOPASSWD: /usr/local/sbin/stackward-sudoers-snapshot
SUDOERS_EOF
chmod 440 "${SUDOERS_FILE}"
visudo -c -f "${SUDOERS_FILE}"

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
# structured JSON: detected issues (each with a text-only `advice`) and a
# `coverage` object saying which data sources could actually be read.
# Read-only: it never changes anything on the host and suggests nothing to run.
#
# Installed by: scripts/bootstrap_linux.sh today; the app's onboarding should install it (PRD D2).
# Called by: Stackward Android app via SSH, on demand only (no scheduled runs).

set -uo pipefail

: "${SYSTEMD_JOURNAL_LINES:=100}"
: "${DOCKER_LOG_LINES:=50}"
: "${DISK_WARNING_PCT:=85}"
: "${DISK_CRITICAL_PCT:=95}"
: "${MEM_WARNING_PCT:=80}"
: "${PROC_MEMINFO:=/proc/meminfo}"   # overridable for tests
: "${SSHD_CONFIG:=/etc/ssh/sshd_config}"  # overridable for tests

# Escapes a string for use inside a JSON string literal: backslash, quote, and
# control characters (tab, CR) that would otherwise corrupt the document.
json_escape() {
    local v=$1
    v=${v//\\/\\\\}
    v=${v//\"/\\\"}
    v=${v//$'\t'/\\t}
    v=${v//$'\r'/\\r}
    printf '%s' "$v"
}

# Core detection functions
detect_disk_issues() {
    # -P: one line per filesystem, stable columns. Pseudo/read-only filesystems (snap
    # squashfs loops are always 100% full) are excluded. Mount points may contain
    # spaces, so the mount is everything after the 5th column.
    df -P -x squashfs -x tmpfs -x devtmpfs -x overlay -x iso9660 2>/dev/null | grep -E '^/' | grep -v '^/dev/loop' | sort -u | while IFS= read -r line; do
        local usage_pct mount
        usage_pct=$(echo "$line" | awk '{print $5}' | sed 's/%//')
        mount=$(echo "$line" | awk '{ $1=$2=$3=$4=$5=""; sub(/^ +/, ""); print }')
        [[ $usage_pct =~ ^[0-9]+$ ]] || continue
        mount=$(json_escape "$mount")

        if [[ $usage_pct -gt $DISK_CRITICAL_PCT ]]; then
            echo "  { \"type\": \"disk\", \"severity\": \"critical\", \"message\": \"$mount at ${usage_pct}% capacity\", \"advice\": \"Find large files with: du -xh / | sort -h | tail\" }"
        elif [[ $usage_pct -gt $DISK_WARNING_PCT ]]; then
            echo "  { \"type\": \"disk\", \"severity\": \"high\", \"message\": \"$mount at ${usage_pct}%\", \"advice\": \"Find large files with: du -xh / | sort -h | tail\" }"
        fi
    done || true
}

detect_memory_issues() {
    if [[ -f "${PROC_MEMINFO}" ]]; then
        local memtotal memavail mem_used_pct
        memtotal=$(grep MemTotal "${PROC_MEMINFO}" | awk '{print $2}')
        memavail=$(grep MemAvailable "${PROC_MEMINFO}" | awk '{print $2}')
        mem_used_pct=$(( (memtotal - memavail) * 100 / memtotal ))

        if [[ $mem_used_pct -gt $MEM_WARNING_PCT ]]; then
            echo "  { \"type\": \"memory\", \"severity\": \"high\", \"message\": \"Memory usage at ${mem_used_pct}%\", \"advice\": \"See top memory users with: ps aux --sort=-rss | head\" }"
        fi
    fi
}

detect_service_issues() {
    if command -v systemctl &>/dev/null; then
        # --no-legend drops the header and the "N loaded units listed." footer;
        # also require a unit-looking name (contains a dot) in case a systemd
        # version prints them anyway, so the footer's "0" is never read as a unit.
        systemctl list-units --state=failed --no-pager --plain --no-legend 2>/dev/null | while IFS= read -r line; do
            local unit
            unit=$(echo "$line" | awk '{print $1}')
            [[ $unit == *.* ]] || continue
            unit=$(json_escape "$unit")
            echo "  { \"type\": \"service\", \"severity\": \"critical\", \"message\": \"Unit $unit failed\", \"advice\": \"Inspect with: systemctl status $unit; journalctl -u $unit -n 50\" }"
        done || true
    fi
}

detect_security_issues() {
    # SSH password auth check
    if [[ -f "${SSHD_CONFIG}" ]]; then
        if grep -qE '^\s*PasswordAuthentication\s+yes' "${SSHD_CONFIG}" 2>/dev/null; then
            echo "  { \"type\": \"security\", \"severity\": \"critical\", \"message\": \"SSH password authentication enabled\", \"advice\": \"Set PasswordAuthentication no in sshd_config once key login works, then reload sshd (manual, as an admin).\" }"
        fi
    fi

    # Check for excessive open ports
    if command -v ss &>/dev/null; then
        local port_count
        port_count=$(ss -tlnp 2>/dev/null | grep -c LISTEN || echo "0")
        if [[ $port_count -gt 20 ]]; then
            echo "  { \"type\": \"security\", \"severity\": \"medium\", \"message\": \"High number of open listening ports ($port_count)\", \"advice\": \"List them with: ss -tlnp\" }"
        fi
    fi
}

detect_log_errors() {
    # Systemd journal errors
    if command -v journalctl &>/dev/null; then
        local error_count
        error_count=$(journalctl -n "$SYSTEMD_JOURNAL_LINES" --priority=err --no-pager 2>/dev/null | wc -l || echo "0")
        if [[ $error_count -gt 5 ]]; then
            echo "  { \"type\": \"logs\", \"severity\": \"medium\", \"message\": \"Multiple journal errors in last $SYSTEMD_JOURNAL_LINES entries\", \"advice\": \"Read them with: journalctl -p err -n 100 --no-pager\" }"
        fi
    fi

    # Docker container errors
    if command -v docker &>/dev/null; then
        local failing_containers
        failing_containers=$(docker ps --filter "status=exited" --format "{{.Names}} {{.Status}}" 2>/dev/null | grep -v "Exited (0)" || true)
        if [[ -n $failing_containers ]]; then
            local count
            count=$(echo "$failing_containers" | wc -l)
            echo "  { \"type\": \"docker\", \"severity\": \"medium\", \"message\": \"$count container(s) exited\", \"advice\": \"List them with: docker ps -a --filter status=exited\" }"
        fi
    fi
}

# Reports which data sources could actually be read, so a check that was blind
# says so instead of looking like "no issues". Values: ok | denied | absent.
detect_coverage() {
    local journal docker systemd out
    if ! command -v journalctl &>/dev/null; then
        journal=absent
    else
        out=$(journalctl -n 1 --no-pager 2>&1) && journal=ok || journal=denied
        [[ $out == *"No journal files"* || $out == *"insufficient permissions"* ]] && journal=denied
    fi
    if ! command -v docker &>/dev/null; then
        docker=absent
    else
        docker ps >/dev/null 2>&1 && docker=ok || docker=denied
    fi
    if ! command -v systemctl &>/dev/null; then
        systemd=absent
    else
        systemctl list-units --no-pager --plain --no-legend >/dev/null 2>&1 && systemd=ok || systemd=denied
    fi
    echo "  \"coverage\": { \"journal\": \"$journal\", \"docker\": \"$docker\", \"systemd\": \"$systemd\" }"
}

# Main
main() {
    local timestamp hostname
    timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    hostname=$(hostname -f 2>/dev/null || hostname)
    hostname=$(json_escape "$hostname")

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
        detect_coverage
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
