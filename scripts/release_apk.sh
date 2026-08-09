#!/usr/bin/env bash
# release_apk.sh — Bump version, build debug APK, and publish a GitHub Release.
#
# Pushes only to the `builds` branch (never `main`). Creates the branch from
# origin/main on first run; subsequent runs reset `builds` to latest main, bump
# the version, build, commit, push, and tag a release with the APK attached.
#
# Usage:
#   ./scripts/release_apk.sh
#
# Optional environment variables:
#   SOURCE_REF   Git ref to build from (default: origin/main)
#   DRY_RUN=1    Print planned actions without mutating git or creating a release
#
# Agent trigger phrase: "cut a new build" (runs this script end-to-end).

set -euo pipefail

SOURCE_REF="${SOURCE_REF:-origin/main}"
BUILDS_BRANCH="builds"
BUILD_FILE="app/build.gradle.kts"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "${REPO_ROOT}"

die() {
  echo "error: $*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "${1} is required"
}

read_version_fields() {
  VERSION_CODE="$(grep -E '^\s*versionCode\s*=' "${BUILD_FILE}" | sed -E 's/.*versionCode\s*=\s*([0-9]+).*/\1/')"
  VERSION_NAME="$(grep -E '^\s*versionName\s*=' "${BUILD_FILE}" | sed -E 's/.*versionName\s*=\s*"([^"]+)".*/\1/')"
  [[ -n "${VERSION_CODE}" && -n "${VERSION_NAME}" ]] || die "could not parse version from ${BUILD_FILE}"
}

bump_version() {
  read_version_fields

  if [[ "${VERSION_NAME}" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(.*)$ ]]; then
    local major="${BASH_REMATCH[1]}"
    local minor="${BASH_REMATCH[2]}"
    local patch="${BASH_REMATCH[3]}"
    local suffix="${BASH_REMATCH[4]}"
    local new_patch=$((patch + 1))
    NEW_VERSION_NAME="${major}.${minor}.${new_patch}${suffix}"
  else
    die "versionName \"${VERSION_NAME}\" is not semver (expected X.Y.Z or X.Y.Z-suffix)"
  fi

  NEW_VERSION_CODE=$((VERSION_CODE + 1))
  TAG="v${NEW_VERSION_NAME}"
}

write_version_fields() {
  sed -i \
    -e "s/^\([[:space:]]*versionCode[[:space:]]*=[[:space:]]*\)[0-9]\+/\1${NEW_VERSION_CODE}/" \
    -e "s/^\([[:space:]]*versionName[[:space:]]*=[[:space:]]*\)\"[^\"]*\"/\1\"${NEW_VERSION_NAME}\"/" \
    "${BUILD_FILE}"
}

restore_version_fields() {
  if [[ -n "${VERSION_CODE:-}" && -n "${VERSION_NAME:-}" ]]; then
    sed -i \
      -e "s/^\([[:space:]]*versionCode[[:space:]]*=[[:space:]]*\)[0-9]\+/\1${VERSION_CODE}/" \
      -e "s/^\([[:space:]]*versionName[[:space:]]*=[[:space:]]*\)\"[^\"]*\"/\1\"${VERSION_NAME}\"/" \
      "${BUILD_FILE}" || true
  fi
}

summarize_changes() {
  local last_tag range
  last_tag="$(git tag -l 'v*' --sort=-version:refname | head -n 1 || true)"

  if [[ -n "${last_tag}" ]]; then
    range="${last_tag}..HEAD"
  else
    range="HEAD"
  fi

  git log "${range}" --pretty=format:'%s' --no-merges -n 1 2>/dev/null \
    || git log "${range}" --pretty=format:'%s' -n 1
}

prepare_builds_branch() {
  local starting_branch
  starting_branch="$(git branch --show-current)"

  git fetch origin main "${BUILDS_BRANCH}" 2>/dev/null || git fetch origin main

  if git show-ref --verify --quiet "refs/remotes/origin/${BUILDS_BRANCH}"; then
    git checkout "${BUILDS_BRANCH}"
    git reset --hard "${SOURCE_REF}"
  else
    git checkout -B "${BUILDS_BRANCH}" "${SOURCE_REF}"
  fi

  STARTING_BRANCH="${starting_branch}"
}

print_download_url() {
  local repo url
  repo="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
  url="$(gh release view "${TAG}" --json assets \
    --jq ".assets[] | select(.name == \"app-debug.apk\") | .url")"
  [[ -n "${url}" ]] || die "could not find app-debug.apk on release ${TAG}"

  echo ""
  echo "==> Release ready"
  echo "    Tag:     ${TAG}"
  echo "    Branch:  ${BUILDS_BRANCH}"
  echo "    APK URL: ${url}"
  echo ""
  echo "Open on your phone:"
  echo "  https://github.com/${repo}/releases/tag/${TAG}"
}

main() {
  require_cmd git
  require_cmd gh
  require_cmd sed

  [[ -f "${BUILD_FILE}" ]] || die "${BUILD_FILE} not found (run from repo root)"
  [[ -f "./gradlew" ]] || die "./gradlew not found (run from repo root)"

  bump_version

  echo "==> Planned release"
  echo "    versionCode: ${VERSION_CODE} -> ${NEW_VERSION_CODE}"
  echo "    versionName: ${VERSION_NAME} -> ${NEW_VERSION_NAME}"
  echo "    tag:         ${TAG}"
  echo "    source:      ${SOURCE_REF}"
  echo "    branch:      ${BUILDS_BRANCH}"

  if [[ "${DRY_RUN:-}" == "1" ]]; then
    echo ""
    echo "DRY_RUN=1 — stopping before git/gradle/release changes."
    exit 0
  fi

  prepare_builds_branch

  write_version_fields

  echo "==> Building debug APK"
  if ! ./gradlew :app:assembleDebug; then
    restore_version_fields
    die "Gradle build failed — version bump reverted, no release created"
  fi

  [[ -f "${APK_PATH}" ]] || die "expected APK at ${APK_PATH} after build"

  local summary notes
  summary="$(summarize_changes)"
  notes="${TAG}

${summary}"

  git add "${BUILD_FILE}"
  git commit -m "chore: bump version to ${TAG}"

  echo "==> Pushing ${BUILDS_BRANCH}"
  git push -u origin "${BUILDS_BRANCH}"

  echo "==> Creating GitHub release ${TAG}"
  gh release create "${TAG}" \
    "${APK_PATH}" \
    --target "${BUILDS_BRANCH}" \
    --title "${TAG}" \
    --notes "${notes}"

  print_download_url

  if [[ -n "${STARTING_BRANCH:-}" && "${STARTING_BRANCH}" != "${BUILDS_BRANCH}" ]]; then
    git checkout "${STARTING_BRANCH}" >/dev/null 2>&1 || true
  fi
}

main "$@"
