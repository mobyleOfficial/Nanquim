#!/usr/bin/env python3
"""Measures what timelapse recording costs the render thread (recording adds
< 1 ms on average to the frame time).
Run on the benchmark build with the control channel (`launch` of kill_test does both):

    python tools/device/measure_timelapse.py --device <serial> [--size 4096] [--resolution 4k]

Draws the same pen strokes (`input stylus swipe`, paced like a person: 600 ms strokes, a pause
between them) on a new canvas twice, timelapse on and off, under a Perfetto trace, and reads the
engine's sections: every NanquimRenderFrame (the frames drawn) and the capture work of the
timelapse (NanquimTimelapseFrame, NanquimPreviewComposite, NanquimPreviewRead: the composite
reduced and its asynchronous readback started). The cost per frame is the capture work divided by
the frames drawn while recording; the mean frame time is compared between the two runs too.
Needs the perfetto Python package (trace_processor).
"""

import argparse
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from kill_test import ADB, adb, launch, wait_for_artwork

TRACE_CONFIG = """buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config { atrace_apps: "com.mobyle.nanquim" } } }
duration_ms: %d
"""
CAPTURE_SECTIONS = ("NanquimTimelapseFrame", "NanquimTimelapseRead", "NanquimPreviewComposite", "NanquimPreviewRead")
STROKES = 24


def run_strokes(device, width, height):
    for index in range(STROKES):
        y = height * (0.2 + 0.6 * (index % 12) / 12)
        x0, x1 = width * 0.25, width * 0.75
        if index % 2:
            x0, x1 = x1, x0
        adb(device, "shell", "input", "stylus", "swipe", str(int(x0)), str(int(y)), str(int(x1)), str(int(y + 40)), "600")
        time.sleep(0.4)


def measure(device, channel, recording, size, resolution, screen):
    channel.execute("gallery.close")
    time.sleep(3)
    artwork = channel.execute("gallery.newCanvas", {"width": size, "height": size, "name": "Timelapse timing"})["id"]
    wait_for_artwork(channel, artwork)
    if recording:
        channel.execute("timelapse.setEnabled", {"enabled": True})
        channel.execute("timelapse.setSettings", {"resolution": resolution})
    else:
        channel.execute("timelapse.setEnabled", {"enabled": False, "purge": True})
    channel.execute("view.fit")
    channel.execute("tool.select", {"tool": "brush"})
    channel.execute("brush.setSize", {"sizePx": 40})
    time.sleep(2)
    duration = STROKES * 1600 + 6000
    remote = "/data/misc/perfetto-traces/timelapse.pftrace"
    recorder = subprocess.Popen(
        [ADB, "-s", device, "shell", "perfetto", "--txt", "-c", "-", "-o", remote],
        stdin=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    recorder.stdin.write((TRACE_CONFIG % duration).encode())
    recorder.stdin.close()
    time.sleep(2)
    run_strokes(device, *screen)
    recorder.wait()
    state = channel.call("getState")["timelapse"]
    from perfetto.trace_processor import TraceProcessor

    with tempfile.TemporaryDirectory() as directory:
        local = Path(directory) / "timelapse.pftrace"
        subprocess.run([ADB, "-s", device, "pull", remote, str(local)], check=True, capture_output=True)
        processor = TraceProcessor(trace=str(local))
        frames = [row.dur for row in processor.query("select dur from slice where name = 'NanquimRenderFrame'")]
        names = ", ".join(f"'{name}'" for name in CAPTURE_SECTIONS)
        rows = list(processor.query(f"select name, dur from slice where name in ({names})"))
        captures = [row.dur for row in rows]
        for name in CAPTURE_SECTIONS:
            durations = sorted(row.dur / 1e6 for row in rows if row.name == name)
            if durations:
                print(f"  {name}: {len(durations)}, median {durations[len(durations) // 2]:.2f} ms, max {durations[-1]:.2f} ms")
        processor.close()
    channel.execute("gallery.close")
    time.sleep(3)
    channel.execute("gallery.delete", {"ids": [artwork]})
    return frames, captures, state


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--size", type=int, default=2732)
    parser.add_argument("--resolution", default="1080p")
    options = parser.parse_args()
    channel = launch(options.device)
    adb(options.device, "shell", "cmd", "statusbar", "collapse")
    size = adb(options.device, "shell", "wm", "size")
    import re

    screen = tuple(int(value) for value in re.findall(r"(\d+)x(\d+)", size)[-1])
    results = {}
    for recording in (True, False):
        frames, captures, state = measure(options.device, channel, recording, options.size, options.resolution, screen)
        results[recording] = frames
        mean_frame = sum(frames) / len(frames) / 1e6 if frames else 0
        label = "on " if recording else "off"
        line = f"timelapse {label}: {len(frames)} frames, mean frame {mean_frame:.2f} ms"
        if recording:
            per_frame = sum(captures) / len(frames) / 1e6 if frames else 0
            longest = max(captures) / 1e6 if captures else 0
            line += (
                f"; capture work {sum(captures) / 1e6:.1f} ms in {len(captures)} sections"
                f" ({state['framesRecorded']} frames recorded, {state['framesSkipped']} skipped),"
                f" {per_frame:.3f} ms per frame drawn, longest section {longest:.2f} ms"
            )
        print(line)
    on, off = results[True], results[False]
    if on and off:
        print(f"mean frame time difference: {(sum(on) / len(on) - sum(off) / len(off)) / 1e6:+.3f} ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
