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
