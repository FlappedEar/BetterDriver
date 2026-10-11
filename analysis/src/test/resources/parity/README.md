Copied from FlappedEar Telemetry (https://github.com/FlappedEar/Telemetry, Apache 2.0),
`packages/telemetry_core/test/parity` and `test/fixtures` at commit 199c93c (2026-10-10). The VBO
files are synthetic; the JSON files are the output of FlappedEar Overlays' C++ over them.

`segments_reference.json` and `theoretical_best_reference.json` are trimmed to what this port
checks: the segment-storage results (random ids, approval), the published summary, time loss and
consistency are left out.
