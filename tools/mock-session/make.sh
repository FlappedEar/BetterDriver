#!/usr/bin/env bash
# Builds a mock session with video from a VBO (FBBD-191), to try tablet lap review and coaching
# between track days: renders a video that shows only the VBO's speed and time (render.py), then
# imports the VBO as the app does, adds the video as a phone clip in sync with the data and writes
# a .bdsession bundle (MockSessionTest). Open the bundle on the tablet with Sessions > Import, or
# open the file with BetterDriver.
#
# Needs python3 and ffmpeg with libass. Usage: tools/mock-session/make.sh <file.vbo> <output dir>
set -euo pipefail
vbo="$(cd "$(dirname "${1:?file.vbo}")" && pwd)/$(basename "$1")"
mkdir -p "${2:?output dir}"
out="$(cd "$2" && pwd)"
root="$(cd "$(dirname "$0")/../.." && pwd)"
video="$out/$(basename "${vbo%.*}").mp4"
start_ms="$(python3 -I "$root/tools/mock-session/render.py" "$vbo" "$video")"
duration_us="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$video" | awk '{ printf "%d", $1 * 1000000 }')"
"$root/gradlew" -p "$root" :transfer:test --tests '*MockSessionTest*' --rerun -i \
  -PmockVbo="$vbo" -PmockVideo="$video" -PmockVideoStartUtcMs="$start_ms" \
  -PmockVideoDurationUs="$duration_us" -PmockOut="$out" | grep -E '^ *(mock session|lap )' || true
ls "$out"/*.bdsession
