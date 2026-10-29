#!/usr/bin/env python3
"""Who used the emulator's CPUs in each stretch of the replay drive (FBBD-266).

replay-drive.sh writes snapshots of /proc/*/stat and /proc/*/task/*/stat at set times of the drive,
one file per snapshot named <seconds>.txt, each with a "== ps" part (pid and process name) before the
stat lines. This prints, as Markdown, for each stretch between two snapshots: the CPU time of the
busiest processes and of the busiest threads of the app's processes. The start-up's slow frames
(the first ~90 s) are compared with the steady drive's this way. Never fails the drive.

Usage: cpu-windows.py DIR [OUT.md]
"""
import os
import re
import sys

APP = "com.flappedear.betterdriver"
# Clock ticks a second (USER_HZ), as on every Android kernel.
HZ = 100
STAT = re.compile(r"^(\d+) \((.*)\) \S (.*)$")


def snapshot(path):
    """pid -> name, and (pid, tid) -> (thread name, ticks) for every task, from one snapshot file."""
    names, tasks, part = {}, {}, None
    with open(path, errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("== "):
                part = line[3:].split()[0]
                continue
            if part == "ps":
                cols = line.split()
                if len(cols) >= 2 and cols[0].isdigit():
                    names[int(cols[0])] = cols[-1]
            elif part == "stat":
                m = STAT.match(line)
                if not m:
                    continue
                tid, comm, rest = int(m.group(1)), m.group(2), m.group(3).split()
                # After the state: ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime stime.
                tasks[tid] = (comm, int(rest[10]) + int(rest[11]))
            elif part == "task":
                # The app's threads, each stat line after "<pid>:" (a thread's own stat names no process).
                pid_s, _, stat = line.partition(":")
                m = STAT.match(stat)
                if not m or not pid_s.isdigit():
                    continue
                rest = m.group(3).split()
                tasks[(int(pid_s), int(m.group(1)))] = (m.group(2), int(rest[10]) + int(rest[11]))
    return names, tasks


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    d = sys.argv[1]
    snaps = sorted((int(f[:-4]), os.path.join(d, f)) for f in os.listdir(d) if f.endswith(".txt") and f[:-4].isdigit())
    out = ["### CPU time by stretch of the drive (FBBD-266)", ""]
    if len(snaps) < 2:
        out.append("Fewer than two CPU snapshots.")
    for (t0, p0), (t1, p1) in zip(snaps, snaps[1:]):
        n0, k0 = snapshot(p0)
        n1, k1 = snapshot(p1)
        names = {**n0, **n1}
        secs = max(t1 - t0, 1)
        procs, threads = {}, {}
        for key, (comm, ticks) in k1.items():
            if isinstance(key, tuple):
                pid, tid = key
                was = k0.get(key, (comm, 0))[1]
                threads[(names.get(pid, str(pid)), comm)] = threads.get((names.get(pid, str(pid)), comm), 0) + ticks - was
            else:
                was = k0.get(key, (comm, 0))[1]
                name = names.get(key, comm)
                procs[name] = procs.get(name, 0) + ticks - was
        total = sum(v for v in procs.values() if v > 0)
        out.append(f"**{t0}-{t1} s**: {total * 1000 / HZ / secs:.0f} ms of CPU a second in all.")
        out.append("")
        out.append("| Process | CPU ms/s |")
        out.append("| --- | --- |")
        for name, v in sorted(procs.items(), key=lambda kv: -kv[1])[:10]:
            out.append(f"| {name[-60:]} | {v * 1000 / HZ / secs:.0f} |")
        out.append("")
        out.append("| App process | Thread | CPU ms/s |")
        out.append("| --- | --- | --- |")
        for (proc, comm), v in sorted(threads.items(), key=lambda kv: -kv[1])[:15]:
            if v > 0:
                out.append(f"| {proc.replace(APP, '')  or 'ui'} | {comm} | {v * 1000 / HZ / secs:.0f} |")
        out.append("")
    text = "\n".join(out)
    print(text)
    if len(sys.argv) > 2:
        with open(sys.argv[2], "w") as f:
            f.write(text + "\n")


if __name__ == "__main__":
    main()
