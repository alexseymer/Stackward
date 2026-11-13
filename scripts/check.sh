#!/usr/bin/env bash
# ~/.stackward/check.sh — host anomaly detection for Stackward
#
# Queries system state (logs, disk, memory, services, security) and returns
# structured JSON: detected issues (each with a text-only `advice`) and a
# `coverage` object saying which data sources could actually be read.
# Read-only: it never changes anything on the host and suggests nothing to run.
#
# Installed by: scripts/bootstrap_linux.sh (app onboarding invokes this)
# Called by: Stackward Android app via SSH, on-demand or on schedule

set -uo pipefail

: "${SYSTEMD_JOURNAL_LINES:=100}"
: "${DOCKER_LOG_LINES:=50}"
: "${DISK_WARNING_PCT:=85}"
: "${DISK_CRITICAL_PCT:=95}"
: "${MEM_WARNING_PCT:=80}"
: "${PROC_MEMINFO:=/proc/meminfo}"   # overridable for tests
: "${SSHD_CONFIG:=/etc/ssh/sshd_config}"  # overridable for tests

# Core detection functions
detect_disk_issues() {
    df -h 2>/dev/null | grep -E '^/' | while IFS= read -r line; do
        local usage_pct mount
        usage_pct=$(echo "$line" | awk '{print $5}' | sed 's/%//')
        mount=$(echo "$line" | awk '{print $6}')

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
        failing_containers=$(docker ps --filter "status=exited" --format "{{.Names}}" 2>/dev/null || echo "")
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
