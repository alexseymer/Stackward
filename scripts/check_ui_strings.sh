#!/usr/bin/env bash
# check_ui_strings.sh — fails when a localized screen gets a hard-coded UI string.
#
# The screens listed below read all user-visible text from res/values*/strings.xml (en + de).
# Add a screen to GUARDED once it is converted. Data shown as-is (host names, severities, check.sh
# text) is fine; literal prose (a quoted string with a space) passed to Text(...), text = or
# contentDescription is not.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

GUARDED=(
    app/src/main/java/dev/stackward/ui/dashboard/DashboardScreen.kt
    app/src/main/java/dev/stackward/ui/dashboard/HostDetailScreen.kt
    app/src/main/java/dev/stackward/ui/dashboard/UiText.kt
    app/src/main/java/dev/stackward/ui/settings/SettingsScreen.kt
    app/src/main/java/dev/stackward/ui/OnboardingScreen.kt
)

status=0
for file in "${GUARDED[@]}"; do
    if grep -nE '(Text\(|text = |contentDescription = )"[A-Za-z][^"]* ' "${file}"; then
        echo "ERROR: hard-coded UI string in ${file} (use stringResource / strings.xml)" >&2
        status=1
    fi
done

# Every string must exist in both languages.
names() { grep -oE '<(string|plurals) name="[^"]+"' "$1" | sort; }
if ! diff <(names app/src/main/res/values/strings.xml) <(names app/src/main/res/values-de/strings.xml); then
    echo "ERROR: values/strings.xml and values-de/strings.xml define different strings" >&2
    status=1
fi

[[ ${status} -eq 0 ]] && echo "OK: guarded screens use string resources; en/de string sets match."
exit "${status}"
