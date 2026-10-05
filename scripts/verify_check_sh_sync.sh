#!/usr/bin/env bash
# verify_check_sh_sync.sh — fails if scripts/check.sh and the copy embedded in
# scripts/bootstrap_linux.sh have drifted apart.
#
# bootstrap_linux.sh embeds check.sh verbatim via a heredoc (rather than
# fetching it from a second file at install time) so the bootstrap script
# stays a single, self-contained file an admin can curl and run on a fresh
# host. That design is deliberate, but it means nothing enforces the two
# copies staying identical except this check — see the bug this caught
# during development, where check.sh was fixed but the embedded copy wasn't.
#
# Usage: scripts/verify_check_sh_sync.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK_SH="${SCRIPT_DIR}/check.sh"
BOOTSTRAP_SH="${SCRIPT_DIR}/bootstrap_linux.sh"

extracted="$(awk '
  /cat > "\$\{AGENT_HOME\}\/\.stackward\/check\.sh" << .CHECK_SCRIPT_EOF./ { capture=1; next }
  capture && /^CHECK_SCRIPT_EOF$/ { exit }
  capture { print }
' "${BOOTSTRAP_SH}")"

if [[ -z "${extracted}" ]]; then
    echo "ERROR: could not find the embedded check.sh block in ${BOOTSTRAP_SH}" >&2
    exit 1
fi

if ! diff -u <(printf '%s\n' "${extracted}") "${CHECK_SH}" > /tmp/check_sh_sync.diff; then
    echo "ERROR: scripts/check.sh and the copy embedded in bootstrap_linux.sh have drifted:" >&2
    cat /tmp/check_sh_sync.diff >&2
    echo "Fix: replace the heredoc body in bootstrap_linux.sh with the current scripts/check.sh." >&2
    exit 1
fi

echo "OK: check.sh and its embedded copy in bootstrap_linux.sh match."
