#!/usr/bin/env bash
# protect-main-branch.sh — Enable GitHub branch protection on main.
#
# Requires a GitHub token with admin access to the repository (classic PAT with
# `repo` scope, or fine-grained token with Administration: read and write).
#
# Usage (repo admin):
#   gh auth login   # as alexseymer or another admin
#   ./scripts/protect-main-branch.sh
#
# Or with an explicit token:
#   GH_TOKEN=ghp_... ./scripts/protect-main-branch.sh
#
# Optional overrides:
#   REPO=owner/name  (default: alexseymer/Stackward)
#   BRANCH=main      (default: main)

set -euo pipefail

REPO="${REPO:-alexseymer/Stackward}"
BRANCH="${BRANCH:-main}"
RULESET_NAME="Protect main"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RULESET_FILE="${SCRIPT_DIR}/../.github/rulesets/protect-main.json"

if ! command -v gh >/dev/null 2>&1; then
  echo "error: GitHub CLI (gh) is required" >&2
  exit 1
fi

if [[ ! -f "${RULESET_FILE}" ]]; then
  echo "error: ruleset file not found at ${RULESET_FILE}" >&2
  exit 1
fi

echo "==> Applying repository ruleset for ${REPO} (${BRANCH})"

existing_id="$(gh api "repos/${REPO}/rulesets" --paginate \
  --jq ".[] | select(.name == \"${RULESET_NAME}\") | .id" 2>/dev/null | head -n1 || true)"

if [[ -n "${existing_id}" ]]; then
  echo "    Updating existing ruleset id=${existing_id}"
  gh api \
    --method PUT \
    "repos/${REPO}/rulesets/${existing_id}" \
    --input "${RULESET_FILE}" >/dev/null
else
  echo "    Creating ruleset \"${RULESET_NAME}\""
  gh api \
    --method POST \
    "repos/${REPO}/rulesets" \
    --input "${RULESET_FILE}" >/dev/null
fi

echo "==> Verifying classic branch protection (fallback / compatibility)"
gh api \
  --method PUT \
  "repos/${REPO}/branches/${BRANCH}/protection" \
  --input - >/dev/null <<'EOF'
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["build"]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false,
    "required_approving_review_count": 1
  },
  "restrictions": null,
  "required_linear_history": false,
  "allow_force_pushes": false,
  "allow_deletions": false
}
EOF

echo "==> Done. main is now protected:"
echo "    - Pull requests required (1 approval)"
echo "    - CI status check 'build' required"
echo "    - Force pushes and branch deletion blocked"
