#!/usr/bin/env bash
# Checks the acceptance record of a release (docs/RELEASE_CHECKLIST.md):
# docs/release-notes/v<version>.md exists, has a line for every area, and no
# area failed. Usage: release-acceptance.sh <version>
set -euo pipefail
version="$1"
notes="docs/release-notes/v$version.md"
[ -f "$notes" ] || {
  echo "::error::$notes is missing. Add the release's acceptance record (docs/RELEASE_CHECKLIST.md) with the version bump."
  exit 1
}
status=0
for area in Recording Timing Video Analysis Coach Storage Hardware; do
  line=$(grep -E "^- $area: " "$notes" | head -1 || true)
  if [ -z "$line" ]; then
    echo "::error::$notes has no line '- $area: PASS | NOT RUN | N/A - evidence'."
    status=1
  elif ! grep -Eq "^- $area: (PASS|NOT RUN|N/A) - .+" <<<"$line"; then
    echo "::error::$notes: '$line' must be PASS, NOT RUN or N/A, then ' - ' and the evidence (a FAIL stops the release)."
    status=1
  fi
done
[ "$status" -eq 0 ] && echo "Acceptance record $notes is complete."
exit "$status"
