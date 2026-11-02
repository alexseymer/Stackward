#!/usr/bin/env bash
# verify_catalog_helper_parity.sh — fails if the phone-side action catalog and the
# root sudoers helper disagree about which actions may run with elevation.
#
# CheckActionCatalog.kt (phone) decides which action ids are RISKY and what
# command the phone sends. scripts/bootstrap_linux.sh installs
# stackward-check-action (host, root), which independently re-validates the id.
# A RISKY catalog entry with no matching helper case silently fails against a real
# host; a helper case with no catalog entry is a privileged action the phone never
# vetted. This shipped as a bug once and was caught by review, not by tests.
#
# Rules enforced:
#   1. Every RISKY catalog entry's command is exactly
#      "sudo /usr/local/sbin/stackward-check-action <its own id>".
#   2. Every RISKY id has a case in the helper.
#   3. Every helper case is a RISKY catalog id (the helper accepts nothing extra).
#   4. SAFE entries never use sudo or the helper; SCARY/UNKNOWN entries have no command.
#
# Usage: scripts/verify_catalog_helper_parity.sh
# Test hooks: CATALOG_KT and BOOTSTRAP_SH override the files that are read.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CATALOG_KT="${CATALOG_KT:-${SCRIPT_DIR}/../app/src/main/java/dev/stackward/check/CheckActionCatalog.kt}"
BOOTSTRAP_SH="${BOOTSTRAP_SH:-${SCRIPT_DIR}/bootstrap_linux.sh}"
HELPER_PATH="/usr/local/sbin/stackward-check-action"

# id|RISK|command ("-" when the command is null)
entries="$(awk '
  /^[[:space:]]*"[a-z0-9_]+" to CatalogEntry\(/ {
    match($0, /"[a-z0-9_]+"/); id = substr($0, RSTART + 1, RLENGTH - 2); risk = ""; cmd = "-"; next
  }
  id != "" && /risk = ActionRisk\./ {
    sub(/.*ActionRisk\./, ""); sub(/[^A-Z].*/, ""); risk = $0; next
  }
  id != "" && /command = null/ { cmd = "-"; next }
  id != "" && /command = "/ {
    line = $0; sub(/^[^"]*"/, "", line); sub(/",?[[:space:]]*$/, "", line); cmd = line; next
  }
  id != "" && /^[[:space:]]*\),?[[:space:]]*$/ { print id "|" risk "|" cmd; id = "" }
' "${CATALOG_KT}")"

if [[ -z "${entries}" ]]; then
    echo "ERROR: found no CatalogEntry rows in ${CATALOG_KT}; parser out of date?" >&2
    exit 1
fi

helper_cases="$(awk -v helper="${HELPER_PATH}" '
  index($0, "cat > " helper) { in_helper = 1; next }
  in_helper && /^HELPER_EOF$/ { exit }
  in_helper && /case "\$1" in/ { in_case = 1; next }
  in_case && /^[[:space:]]*esac/ { exit }
  in_case && /^[[:space:]]+[a-z0-9_|]+\)[[:space:]]*$/ {
    gsub(/[[:space:]]|\)/, ""); n = split($0, parts, "|"); for (i = 1; i <= n; i++) print parts[i]
  }
' "${BOOTSTRAP_SH}")"

if [[ -z "${helper_cases}" ]]; then
    echo "ERROR: found no case labels in the ${HELPER_PATH} heredoc in ${BOOTSTRAP_SH}" >&2
    exit 1
fi

failed=0
fail() { echo "ERROR: $*" >&2; failed=1; }

risky_ids=""
while IFS='|' read -r id risk cmd; do
    case "${risk}" in
        RISKY)
            risky_ids+="${id}"$'\n'
            expected="sudo ${HELPER_PATH} ${id}"
            [[ "${cmd}" == "${expected}" ]] \
                || fail "RISKY '${id}': command must be exactly '${expected}', got '${cmd}'"
            grep -qxF "${id}" <<<"${helper_cases}" \
                || fail "RISKY '${id}' has no case in ${HELPER_PATH} (it would fail on a real host)"
            ;;
        SAFE)
            [[ "${cmd}" != "-" ]] || fail "SAFE '${id}' has no command"
            [[ "${cmd}" != *sudo* && "${cmd}" != *stackward-check-action* ]] \
                || fail "SAFE '${id}' must not use sudo or the helper: '${cmd}'"
            ;;
        SCARY | UNKNOWN)
            [[ "${cmd}" == "-" ]] || fail "${risk} '${id}' must have command = null, got '${cmd}'"
            ;;
        *)
            fail "'${id}' has unrecognized risk '${risk}'"
            ;;
    esac
done <<<"${entries}"

while IFS= read -r label; do
    [[ -z "${label}" || "${label}" == "*" ]] && continue
    grep -qxF "${label}" <<<"${risky_ids}" \
        || fail "helper case '${label}' is not a RISKY catalog id (privileged action the phone never vetted)"
done <<<"${helper_cases}"

if [[ ${failed} -ne 0 ]]; then
    exit 1
fi
echo "OK: catalog and ${HELPER_PATH} agree on RISKY actions: $(tr '\n' ' ' <<<"${risky_ids}")"
