#!/usr/bin/env bash
# Builds the Circuit Tools video sync test kit (FBBD-51): three VBO variants that differ only in
# how avisynctime is written, each with two MP4 files that show the session time and a big number
# equal to the speed channel. Open each .vbo in Circuit Tools; the variant whose speed at the cursor
# matches the big number in both videos is the format Circuit Tools reads.
#
# Needs ffmpeg with drawtext. Usage: tools/sync-kit/make.sh <output dir>
set -euo pipefail
out="$(cd "$(dirname "${1:?output dir}")" && pwd)/$(basename "$1")"
root="$(cd "$(dirname "$0")/../.." && pwd)"
rm -rf "$out" && mkdir -p "$out"
"$root/gradlew" -p "$root" :recorder:test --tests '*SyncKitTest*' -PsyncKitDir="$out" -q
rm -rf "$out/work"

# render <file> <session start s> <length s>
render() {
  ffmpeg -loglevel error -y -f lavfi -i "color=c=0x101418:s=1280x720:r=30:d=$3" -vf "\
drawtext=text='%{eif\:floor(t+$2)\:d}':fontsize=360:fontcolor=white:x=(w-tw)/2:y=(h-th)/2-40,\
drawtext=text='session %{eif\:floor(t+$2)\:d}.%{eif\:mod(floor((t+$2)*10)\,10)\:d} s   speed should read %{eif\:floor(t+$2)\:d} km/h':fontsize=40:fontcolor=0x8795A0:x=(w-tw)/2:y=h-90" \
    -c:v libx264 -pix_fmt yuv420p -preset veryfast "$1"
}
render "$out/a.mp4" 5 60
render "$out/b.mp4" 70 50
for dir in "$out"/*/; do
  i=0
  while read -r name; do
    i=$((i + 1))
    src=$([ "$i" = 1 ] && echo a.mp4 || echo b.mp4)
    cp "$out/$src" "$dir/$name"
  done < "$dir/videos.txt"
  rm "$dir/videos.txt"
done
rm "$out/a.mp4" "$out/b.mp4"
echo "Kit written to $out"
