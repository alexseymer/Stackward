#!/usr/bin/env bash
# check_sh_test.sh — runs scripts/check.sh against stubbed system commands and
# asserts on the JSON it prints. No host, root, or Android needed.
#
# The script under test runs with PATH set to a directory holding only stubs
# (df, systemctl, ss, journalctl, docker) and symlinks to a few basic tools, so
# the real machine's Docker, journal, etc. cannot leak into results. Meminfo and
# sshd_config come from fixture files via PROC_MEMINFO / SSHD_CONFIG.
#
# Known bugs are pinned as XFAIL (expected failure) with the issue that tracks
# them. A fixed bug shows up as XPASS and fails the run, so the case gets
# promoted to a normal test instead of silently rotting.
#
# Usage: scripts/tests/check_sh_test.sh

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECK_SH="${ROOT}/scripts/check.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

command -v python3 >/dev/null || { echo "python3 is required (JSON validation)" >&2; exit 2; }
BASH_BIN="$(command -v bash)"

BIN="${WORK}/bin"
mkdir -p "${BIN}"
for tool in grep awk sed wc date hostname cat tr head sort cut; do
    real="$(command -v "${tool}")" || { echo "missing basic tool: ${tool}" >&2; exit 2; }
    ln -s "${real}" "${BIN}/${tool}"
done
# Stub: prints $CASE_DIR/<name>.out; exits 1 (as if absent/denied) when no fixture file.
# shellcheck disable=SC2016  # the stub body is written literally; $f expands when the stub runs
make_stub() {
    local name="$1"
    {
        echo '#!/bin/sh'
        echo "f=\"\${CASE_DIR}/${name}.out\""
        echo '[ -f "$f" ] || exit 1'
        echo 'cat "$f"'
    } >"${BIN}/${name}"
    chmod +x "${BIN}/${name}"
}
for stub in df systemctl ss journalctl docker; do make_stub "${stub}"; done

pass=0 fail=0 xfail=0 xpass=0

# new_case <name>: healthy defaults; callers then override fixture files.
new_case() {
    CASE_DIR="${WORK}/${1}"
    mkdir -p "${CASE_DIR}"
    cat >"${CASE_DIR}/df.out" <<'EOF'
Filesystem      Size  Used Avail Use% Mounted on
/dev/sda1       100G   40G   60G  40% /
EOF
    printf 'MemTotal:       16000000 kB\nMemAvailable:   12000000 kB\n' >"${CASE_DIR}/meminfo"
    : >"${CASE_DIR}/sshd_config"
    printf 'UNIT LOAD ACTIVE SUB DESCRIPTION\n\n0 loaded units listed.\n' >"${CASE_DIR}/systemctl.out"
    printf 'State Recv-Q Send-Q Local Address:Port\nLISTEN 0 128 0.0.0.0:22\nLISTEN 0 128 0.0.0.0:80\n' >"${CASE_DIR}/ss.out"
    : >"${CASE_DIR}/journalctl.out"
    : >"${CASE_DIR}/docker.out"
    export CASE_DIR
}

run_check() {
    env -i PATH="${BIN}" HOME="${WORK}" CASE_DIR="${CASE_DIR}" \
        PROC_MEMINFO="${CASE_DIR}/meminfo" SSHD_CONFIG="${CASE_DIR}/sshd_config" \
        "${BASH_BIN}" "${CHECK_SH}" >"${CASE_DIR}/result.json" 2>"${CASE_DIR}/stderr.txt"
}

# assert_py <name> <python-expression over d> [xfail-issue]
assert_py() {
    local name="$1" expr="$2" xfail_issue="${3:-}" ok=1 err
    if err="$(python3 - "${CASE_DIR}/result.json" "${expr}" 2>&1 <<'PY'
import json, sys
path, expr = sys.argv[1], sys.argv[2]
try:
    d = json.load(open(path))
except Exception as e:
    print(f"invalid JSON: {e}"); sys.exit(1)
if not eval(expr, {"d": d}):
    print(f"assertion false: {expr}"); sys.exit(1)
PY
    )"; then ok=0; fi
    if [[ -n "${xfail_issue}" ]]; then
        if [[ ${ok} -eq 0 ]]; then
            echo "XPASS  ${name} -- known bug ${xfail_issue} now passes; remove the XFAIL marker"; xpass=$((xpass + 1))
        else
            echo "XFAIL  ${name} (known bug ${xfail_issue}: ${err})"; xfail=$((xfail + 1))
        fi
    elif [[ ${ok} -eq 0 ]]; then
        echo "PASS   ${name}"; pass=$((pass + 1))
    else
        echo "FAIL   ${name}: ${err}"; fail=$((fail + 1))
        sed 's/^/         | /' "${CASE_DIR}/result.json" | head -30
    fi
}

SCHEMA='isinstance(d.get("timestamp"), str) and isinstance(d.get("hostname"), str) and isinstance(d["issues"], list) and "suggestions" not in d and all({"type","severity","message","advice"} <= set(i) for i in d["issues"]) and set(d["coverage"]) == {"journal","docker","systemd"} and all(v in ("ok","denied","absent") for v in d["coverage"].values())'

new_case healthy; run_check
assert_py "healthy: valid schema" "${SCHEMA}"
assert_py "healthy: no issues" 'd["issues"] == []'
assert_py "healthy: everything readable" 'd["coverage"] == {"journal": "ok", "docker": "ok", "systemd": "ok"}'

new_case full_disk
cat >"${CASE_DIR}/df.out" <<'EOF'
Filesystem      Size  Used Avail Use% Mounted on
/dev/sda1       100G   96G    4G  96% /
/dev/sdb1       100G   40G   60G  40% /data
EOF
run_check
assert_py "disk 96%: one critical disk issue" '[(i["type"], i["severity"]) for i in d["issues"]] == [("disk", "critical")]'
assert_py "disk 96%: names the mount" '"/ at 96%" in d["issues"][0]["message"]'
assert_py "disk 96%: advice is text only" '"advice" in d["issues"][0] and "sudo" not in d["issues"][0]["advice"]'

new_case high_disk
cat >"${CASE_DIR}/df.out" <<'EOF'
Filesystem      Size  Used Avail Use% Mounted on
/dev/sda1       100G   90G   10G  90% /
EOF
run_check
assert_py "disk 90%: high, not critical" '[(i["type"], i["severity"]) for i in d["issues"]] == [("disk", "high")]'

new_case memory
printf 'MemTotal:       16000000 kB\nMemAvailable:    2000000 kB\n' >"${CASE_DIR}/meminfo"
run_check
assert_py "memory 87.5% used: one high memory issue" '[(i["type"], i["severity"]) for i in d["issues"]] == [("memory", "high")]'

new_case failed_unit
cat >"${CASE_DIR}/systemctl.out" <<'EOF'
UNIT          LOAD   ACTIVE SUB    DESCRIPTION
nginx.service loaded failed failed A high performance web server
EOF
run_check
assert_py "failed unit: critical service issue naming the unit" '[(i["type"], i["severity"]) for i in d["issues"]] == [("service", "critical")] and "nginx.service" in d["issues"][0]["message"]'

new_case ssh_password
echo 'PasswordAuthentication yes' >"${CASE_DIR}/sshd_config"
run_check
assert_py "password auth: critical security issue" '[(i["type"], i["severity"]) for i in d["issues"]] == [("security", "critical")]'

new_case ssh_password_commented
echo '#PasswordAuthentication yes' >"${CASE_DIR}/sshd_config"
run_check
assert_py "commented-out password auth is ignored" 'd["issues"] == []'

new_case many_ports
{ echo 'State Recv-Q Send-Q Local Address:Port'; for p in $(seq 1 25); do echo "LISTEN 0 128 0.0.0.0:${p}"; done; } >"${CASE_DIR}/ss.out"
run_check
assert_py "25 listening ports: medium security issue" '[(i["type"], i["severity"]) for i in d["issues"]] == [("security", "medium")]'

new_case journal_errors
for n in $(seq 1 8); do echo "Oct 01 00:00:0${n} host app[1]: error ${n}"; done >"${CASE_DIR}/journalctl.out"
run_check
assert_py "8 journal errors: medium logs issue" '[(i["type"], i["severity"]) for i in d["issues"]] == [("logs", "medium")]'

new_case docker_exited
printf 'web\njob\n' >"${CASE_DIR}/docker.out"
run_check
assert_py "2 exited containers: one medium docker issue" '[(i["type"], i["severity"]) for i in d["issues"]] == [("docker", "medium")] and "2 container" in d["issues"][0]["message"]'

new_case docker_clean_exit
printf 'job Exited (0) 2 hours ago\n' >"${CASE_DIR}/docker.out"
run_check
assert_py "container that exited 0 is not an issue" 'd["issues"] == []'

new_case docker_failed_exit
printf 'web Exited (137) 2 hours ago\njob Exited (0) 2 hours ago\n' >"${CASE_DIR}/docker.out"
run_check
assert_py "only non-zero exits count" '[(i["type"], i["message"]) for i in d["issues"]] == [("docker", "1 container(s) exited")]'

new_case spaced_mount
cat >"${CASE_DIR}/df.out" <<'EOF'
Filesystem      Size  Used Avail Use% Mounted on
/dev/sdb1       100G   90G   10G  90% /mnt/my disk
/dev/sdb1       100G   90G   10G  90% /mnt/my disk
EOF
run_check
assert_py "mount with a space: one issue, full name, deduped" '[i["message"] for i in d["issues"]] == ["/mnt/my disk at 90%"]'

new_case no_optional_tools
rm -f "${BIN}/ss" "${BIN}/journalctl"
run_check
assert_py "no ss/journalctl: still valid JSON, no issues" "${SCHEMA} and d[\"issues\"] == []"
assert_py "journalctl missing: coverage says absent, not ok" 'd["coverage"]["journal"] == "absent"'
make_stub ss; make_stub journalctl

new_case journal_denied
printf 'No journal files were opened due to insufficient permissions.\n' >"${CASE_DIR}/journalctl.out"
run_check
assert_py "journal unreadable: coverage says denied (never a silent all-clear)" 'd["coverage"]["journal"] == "denied" and d["issues"] == []'

new_case docker_denied
rm -f "${CASE_DIR}/docker.out"
run_check
assert_py "docker present but unreadable: coverage says denied" 'd["coverage"]["docker"] == "denied"'

new_case systemd_denied
rm -f "${CASE_DIR}/systemctl.out"
run_check
assert_py "systemctl fails: coverage says denied" 'd["coverage"]["systemd"] == "denied"'

new_case advice_is_text
printf 'UNIT LOAD ACTIVE SUB DESCRIPTION\nx.service loaded failed failed X\n' >"${CASE_DIR}/systemctl.out"
run_check
assert_py "advice for a failed unit names the unit" '"x.service" in d["issues"][0]["advice"]'

# --- Regression cases for fixed check.sh bugs (#89) ---
new_case loop_mount
cat >"${CASE_DIR}/df.out" <<'EOF'
Filesystem      Size  Used Avail Use% Mounted on
/dev/sda1       100G   40G   60G  40% /
/dev/loop0       64M   64M     0 100% /snap/core20/2318
EOF
run_check
assert_py "snap loop mount at 100% must not raise a disk alert" 'd["issues"] == []'

new_case hostile_mount_name
cat >"${CASE_DIR}/df.out" <<'EOF'
Filesystem      Size  Used Avail Use% Mounted on
/dev/sdb1       100G   99G    1G  99% /mnt/we"ird\path
EOF
run_check
assert_py "quote/backslash in a mount name still yields valid JSON" "${SCHEMA} and 'we\"ird' in d[\"issues\"][0][\"message\"]"

echo
echo "check.sh tests: ${pass} passed, ${fail} failed, ${xfail} known-bug (XFAIL), ${xpass} unexpectedly fixed (XPASS)"
[[ ${fail} -eq 0 && ${xpass} -eq 0 ]]
