#!/usr/bin/env python3
"""Where the track screen's frame time goes on the replay drive (FBBD-266).

Reads a Perfetto trace of the app on the track screen (replay-drive.sh takes one minute of the
drive) and prints, as Markdown: the UI thread's frames (Choreographer#doFrame), how long the thread
ran, waited for a CPU or slept inside them, what ran inside the slow frames, and which other threads
had the CPUs while the UI thread waited for one. It never fails the drive: the drive's checks judge.

Usage: track-trace.py TRACE [OUT.md]
"""
import sys

from perfetto.trace_processor import TraceProcessor

APP = "com.flappedear.betterdriver"
MS = 1e6
# Frames at least this long count as slow: the drive check's limit (ui.frame_p95, 16 ms).
SLOW_NS = 16 * 1_000_000


def rows(tp, sql):
    return list(tp.query(sql))


def ui_thread(tp):
    """The app's main thread; without process names (a text trace), the thread drawing most frames."""
    named = rows(tp, f"""
        select t.utid from thread t join process p using(upid)
        where p.name = '{APP}' and t.tid = p.pid""")
    if named:
        return named[0].utid
    busiest = rows(tp, """
        select tt.utid, count(*) n from slice s join thread_track tt on s.track_id = tt.id
        where s.name like 'Choreographer#doFrame%' group by tt.utid order by n desc limit 1""")
    return busiest[0].utid if busiest else None


def pct(sorted_values, p):
    return sorted_values[int((len(sorted_values) - 1) * p)] if sorted_values else 0


def report(tp):
    utid = ui_thread(tp)
    if utid is None:
        return "No UI thread frames in the trace."
    frames = f"""
        select s.id, s.ts, s.dur, s.depth, s.track_id from slice s join thread_track tt on s.track_id = tt.id
        where tt.utid = {utid} and s.name like 'Choreographer#doFrame%'
          and s.depth = (select min(s2.depth) from slice s2 where s2.track_id = s.track_id
                         and s2.name like 'Choreographer#doFrame%')"""
    all_frames = rows(tp, frames)
    if not all_frames:
        return "No UI thread frames in the trace."
    durs = sorted(f.dur for f in all_frames)
    slow_ids = {f.id for f in all_frames if f.dur >= SLOW_NS}
    out = ["### Track screen frames from a trace of one minute of the drive (FBBD-266)", ""]
    out.append(
        f"{len(durs)} frames on the UI thread: p50 {pct(durs, .5) / MS:.1f} ms, p95 {pct(durs, .95) / MS:.1f} ms, "
        f"max {durs[-1] / MS:.1f} ms; {len(slow_ids)} of 16 ms or more."
    )
    out.append("")

    # The UI thread's state inside the frames: running, waiting for a CPU (R, R+), sleeping (S), I/O (D).
    states = rows(tp, f"""
        with f as ({frames})
        select f.id, st.state, sum(min(st.ts + st.dur, f.ts + f.dur) - max(st.ts, f.ts)) d
        from f join thread_state st on st.utid = {utid} and st.ts < f.ts + f.dur and st.ts + st.dur > f.ts
        group by f.id, st.state""")

    def state_table(ids, title):
        n = max(len(ids), 1)
        sums = {}
        for r in states:
            if r.id in ids:
                key = {"Running": "Running", "R": "Waiting for a CPU", "R+": "Waiting for a CPU",
                       "S": "Sleeping", "D": "I/O (D)"}.get(r.state, r.state)
                sums[key] = sums.get(key, 0) + r.d
        line = " · ".join(f"{k} {v / n / MS:.2f} ms" for k, v in sorted(sums.items(), key=lambda kv: -kv[1]))
        out.append(f"- {title} ({len(ids)}), per frame: {line or 'no thread states (no sched events)'}")

    state_table({f.id for f in all_frames}, "All frames")
    state_table(slow_ids, "Frames of 16 ms or more")
    out.append("")

    # Frames that drew (a draw inside) are the ones JankStats counts; the others only ran callbacks.
    drew = rows(tp, f"""
        with f as ({frames})
        select f.id, f.dur from f where exists (select 1 from slice c where c.track_id = f.track_id
          and c.ts >= f.ts and c.ts < f.ts + f.dur and c.depth > f.depth and c.name = 'draw')""")
    drawn = sorted(r.dur for r in drew)
    if drawn:
        out.append(
            f"{len(drawn)} of them drew: p50 {pct(drawn, .5) / MS:.1f} ms, p95 {pct(drawn, .95) / MS:.1f} ms, "
            f"max {drawn[-1] / MS:.1f} ms."
        )
        out.append("")

    # The UI thread's work outside the frames: it delays the next frame's start (JankStats counts that delay).
    span = rows(tp, "select trace_start() a, trace_end() b")[0]
    seconds = max((span.b - span.a) / 1e9, 1e-9)
    running = rows(tp, f"select sum(dur) d from thread_state where utid = {utid} and state = 'Running'")[0].d or 0
    in_frames = sum(r.d for r in states if r.state == "Running")
    out.append(
        f"UI thread running: {running / seconds / MS:.1f} ms a second, "
        f"{(running - in_frames) / seconds / MS:.1f} ms of it outside frames."
    )
    outside = rows(tp, f"""
        select s.name, count(*) n, sum(s.dur) total, max(s.dur) worst from slice s
        join thread_track tt on s.track_id = tt.id
        where tt.utid = {utid} and s.depth = 0 and s.name not like 'Choreographer#doFrame%'
        group by s.name order by total desc limit 10""")
    if outside:
        out.append("")
        out.append("| UI thread outside frames | Count | Total | Worst |")
        out.append("| --- | --- | --- | --- |")
        for r in outside:
            out.append(f"| {r.name.replace('|', '/')[:70]} | {r.n} | {r.total / MS:.1f} ms | {r.worst / MS:.2f} ms |")
    out.append("")

    # What the UI thread did inside the slow frames (or the slowest 5 % when none is slow), by slice name and depth.
    picked = slow_ids or {f.id for f in all_frames if f.dur >= pct(durs, .95)}
    ids = ",".join(str(i) for i in picked)
    inner = rows(tp, f"""
        with f as (select id, ts, dur, depth, track_id from slice where id in ({ids}))
        select c.name, c.depth - f.depth rel, count(*) n, sum(c.dur) total, max(c.dur) worst
        from f join slice c on c.track_id = f.track_id and c.ts >= f.ts and c.ts < f.ts + f.dur
          and c.depth > f.depth and c.depth <= f.depth + 4
        group by c.name, rel order by total desc limit 25""")
    out.append(f"What ran inside those {len(picked)} frames (depth below doFrame, total and worst):")
    out.append("")
    out.append("| Slice | Depth | Count | Per frame | Worst |")
    out.append("| --- | --- | --- | --- | --- |")
    for r in inner:
        name = r.name.replace("|", "/")[:70]
        out.append(f"| {name} | {r.rel} | {r.n} | {r.total / len(picked) / MS:.2f} ms | {r.worst / MS:.2f} ms |")
    out.append("")

    # Who had the CPUs while the UI thread waited for one in those frames.
    waits = rows(tp, f"""
        with f as (select id, ts, dur from slice where id in ({ids})),
        w as (select max(st.ts, f.ts) a, min(st.ts + st.dur, f.ts + f.dur) b from f
              join thread_state st on st.utid = {utid} and st.state in ('R', 'R+')
              and st.ts < f.ts + f.dur and st.ts + st.dur > f.ts)
        select coalesce(p.name, '?') proc, coalesce(t.name, '?') thread,
               sum(min(sc.ts + sc.dur, w.b) - max(sc.ts, w.a)) d
        from w join sched sc on sc.ts < w.b and sc.ts + sc.dur > w.a and sc.utid != 0
        join thread t using(utid) left join process p using(upid)
        group by proc, thread order by d desc limit 12""")
    if waits:
        out.append("Threads on the CPUs while the UI thread waited for one in those frames:")
        out.append("")
        out.append("| Process | Thread | Time |")
        out.append("| --- | --- | --- |")
        for r in waits:
            out.append(f"| {r.proc} | {r.thread} | {r.d / MS:.1f} ms |")
        out.append("")
    return "\n".join(out)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    tp = TraceProcessor(trace=sys.argv[1])
    try:
        text = report(tp)
    finally:
        tp.close()
    print(text)
    if len(sys.argv) > 2:
        with open(sys.argv[2], "w") as f:
            f.write(text + "\n")


if __name__ == "__main__":
    main()
