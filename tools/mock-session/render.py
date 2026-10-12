"""Renders a mock on-board video from a VBO (FBBD-191): speed and time only, one frame per 1/30 s.

The video's first frame is the VBO's first data row, so the clip's offset to the session is 0.
Writes <out>.mp4 and prints the first row's UTC time in milliseconds since the epoch.

Usage: python3 render.py <file.vbo> <out.mp4> [time zone, default Europe/Warsaw]
"""
import datetime as dt
import subprocess
import sys
import tempfile
from pathlib import Path
from zoneinfo import ZoneInfo

WIDTH, HEIGHT, FPS = 1920, 1080, 30


def rows(vbo: Path):
    """(UTC datetime, speed km/h) per data row, with the date from the "File created on" line."""
    date = None
    names = None
    section = ""
    out = []
    for raw in vbo.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line.lower()
            continue
        if section == "" and date is None and line.startswith("File created on"):
            d, m, y = line.split()[3].split("/")
            date = dt.date(int(y), int(m), int(d))
        elif section == "[column names]" and names is None:
            names = line.split()
        elif section == "[data]":
            f = line.split()
            t = f[names.index("time")]
            speed = float(f[names.index("velocity")])
            secs = int(t[:2]) * 3600 + int(t[2:4]) * 60 + float(t[4:])
            out.append((secs, speed))
    if date is None or not out:
        sys.exit("VBO needs a 'File created on' line and a [data] section")
    midnight = dt.datetime.combine(date, dt.time(), dt.timezone.utc)
    day = 0
    result = []
    last = -1.0
    for secs, speed in out:
        if last >= 0 and secs < last - 43200:
            day += 1
        last = secs
        result.append((midnight + dt.timedelta(days=day, seconds=secs), speed))
    return result


def ass_time(s: float) -> str:
    cs = int(round(s * 100))
    return f"{cs // 360000}:{cs // 6000 % 60:02d}:{cs // 100 % 60:02d}.{cs % 100:02d}"


def subtitles(data, zone: ZoneInfo) -> str:
    head = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {WIDTH}
PlayResY: {HEIGHT}
WrapStyle: 2

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Speed,DejaVu Sans Mono,420,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,5,0,0,0,1
Style: Unit,DejaVu Sans,72,&H00A09587,&H00A09587,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,5,0,0,0,1
Style: Clock,DejaVu Sans Mono,96,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,5,0,0,0,1
Style: Small,DejaVu Sans,40,&H00A09587,&H00A09587,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    t0 = data[0][0]
    total = (data[-1][0] - t0).total_seconds() + 0.1
    lines = [
        f"Dialogue: 0,{ass_time(0)},{ass_time(total)},Unit,,0,0,0,,{{\\pos({WIDTH // 2},640)}}km/h",
        f"Dialogue: 0,{ass_time(0)},{ass_time(total)},Small,,0,0,0,,{{\\pos(48,36)}}MOCK VIDEO from VBO",
    ]
    for i, (utc, speed) in enumerate(data):
        start = (utc - t0).total_seconds()
        end = (data[i + 1][0] - t0).total_seconds() if i + 1 < len(data) else total
        local = utc.astimezone(zone)
        clock = local.strftime("%H:%M:%S.") + str(local.microsecond // 100000)
        el = int(round(start * 10))
        elapsed = f"+{el // 36000:d}:{el // 600 % 60:02d}:{el // 10 % 60:02d}.{el % 10}"
        a, b = ass_time(start), ass_time(end)
        lines.append(f"Dialogue: 0,{a},{b},Speed,,0,0,0,,{{\\pos({WIDTH // 2},420)}}{round(speed):d}")
        lines.append(f"Dialogue: 0,{a},{b},Clock,,0,0,0,,{{\\pos({WIDTH // 2},820)}}{clock}")
        lines.append(f"Dialogue: 0,{a},{b},Small,,0,0,0,,{{\\pos(48,{HEIGHT - 84})}}session {elapsed}")
    return head + "\n".join(lines) + "\n", total


def main():
    vbo, out = Path(sys.argv[1]), Path(sys.argv[2])
    zone = ZoneInfo(sys.argv[3] if len(sys.argv) > 3 else "Europe/Warsaw")
    data = rows(vbo)
    text, total = subtitles(data, zone)
    with tempfile.TemporaryDirectory() as tmp:
        ass = Path(tmp, "overlay.ass")
        ass.write_text(text, encoding="utf-8")
        subprocess.run([
            "ffmpeg", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", f"color=c=0x101418:s={WIDTH}x{HEIGHT}:r={FPS}:d={total:.3f}",
            "-vf", f"ass={ass}",
            "-c:v", "libx264", "-preset", "veryfast", "-tune", "stillimage", "-crf", "28",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out),
        ], check=True)
    print(int(data[0][0].timestamp() * 1000))


if __name__ == "__main__":
    main()
