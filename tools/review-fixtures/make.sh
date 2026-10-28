#!/usr/bin/env bash
# Builds the lap review's fixture videos for the emulator tests (FBBD-24, FBBD-93, FBBD-163):
# 192 x 112 at 30 fps, every frame carrying its own number, so a test reads which frame a player
# shows (PixelCopy) or which source frame an exported video holds.
#
# The number is a 12-bit Gray code (one bit changes from frame to frame, which keeps the files
# small) in two rows of twelve 16 x 12 px cells, y 40-52 and 52-64: the top row has the bits
# (white = 1), the row under it their complement, so a frame read half way through a change is
# detected and read again. The rows sit in the band the video overlay leaves clear (FlappedEar
# Overlays' Tech HUD, FBBD-347: track map and heart rate above, G radar below), so a rendered
# video's frames read too. Media time of frame n is n / 30 s exactly (H.264 Constrained Baseline, no B-frames,
# a key frame every 30 frames so an exact seek decodes at most 29 small frames). 12 bits cover
# 136 s. No figures for people: changing text doubles the files.
#
# Needs ffmpeg. Usage: tools/review-fixtures/make.sh <output dir>
# Writes sync_a.mp4 and sync_b.mp4 at the lengths ReviewFixtures (app androidTest) records them for.
set -euo pipefail
out="${1:?output dir}"
mkdir -p "$out"

# Gray code bit of cell column c (0 = most significant): bit (11 - c) of N xor bit (12 - c) of N.
bit='mod(floor(N/pow(2,11-floor(X/16)))+floor(N/pow(2,12-floor(X/16))),2)'

# render <file> <length s>
render() {
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i "color=c=black:s=192x112:r=30:d=$2" \
    -vf "format=gray,geq=lum='if(gte(Y,40)*lt(Y,64),if(lt(Y,52),16+219*$bit,235-219*$bit),16)',format=yuv420p" \
    -c:v libx264 -profile:v baseline -preset veryslow -g 30 -bf 0 -crf 30 -threads 1 \
    -movflags +faststart -map_metadata -1 -fflags +bitexact -flags:v +bitexact "$1"
}

render "$out/sync_a.mp4" 96
render "$out/sync_b.mp4" 52
ls -l "$out"/sync_*.mp4
