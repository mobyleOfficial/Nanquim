#!/usr/bin/env python3
"""Measures frame times of a 5 s smudge stroke on a 4096² canvas (every
frame < 16.7 ms at 60 Hz).

Run on the benchmark build (./gradlew :app:installProdBenchmark), with the control channel:

    adb forward tcp:7765 localabstract:nanquim-control
    adb shell am start -n com.mobyle.nanquim/com.mobyle.nanquim.MainActivity --es com.mobyle.nanquim.extra.CONTROL_TOKEN killtest
    tools/device/measure_smudge.py [--runs 3] [--size 120]

Paints thick color bands, then for each run resets the frame statistics, injects a 5 s stylus
swipe across them with the smudge tool (`adb shell input` adds its own timing jitter; confirm
with a real pen) and reads the statistics. A paint stroke with the same swipe is the baseline.
The front buffer is turned off while measuring, so every vsync is a counted frame;
--screenshot saves the canvas after the last smudge stroke.
"""

import argparse
import base64
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "automation"))
from nanquim_control import ControlChannel  # noqa: E402

COLORS = [(0.9, 0.2, 0.15), (0.15, 0.35, 0.85), (0.95, 0.8, 0.15), (0.2, 0.65, 0.3)]


def line(x0, y0, x1, y1, count=80):
    return [[x0 + (x1 - x0) * i / (count - 1), y0 + (y1 - y0) * i / (count - 1), 1.0, 8.0 * i] for i in range(count)]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--size", type=float, default=120, help="smudge brush diameter in canvas px")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--screenshot", help="PNG of the canvas after the last smudge stroke")
    parser.add_argument("--open-document", action="store_true",
                        help="measure on the document already open (e.g. the TEST_DOCUMENT launch extra) "
                             "instead of a new 4096² canvas with color bands")
    options = parser.parse_args()
    channel = ControlChannel("127.0.0.1", 7765, "killtest")

    def run(command, arguments=None):
        return channel.call("execute", {"id": command, "arguments": arguments or {}})

    def swipe_frames(tool):
        run("tool.select", {"tool": tool})
        run("brush.setSize", {"sizePx": options.size})
        time.sleep(6)
        run("debug.stats.resetFrames")
        subprocess.run([options.adb, "shell", "input", "stylus", "swipe", "300", "700", "1300", "1800", "5000"],
                       check=True)
        frames = run("debug.stats.readFrames")
        time.sleep(1.5)
        return frames

    if not options.open_document:
        run("gallery.newCanvas", {"width": 4096, "height": 4096, "unit": "px", "dpi": 300,
                                  "name": "Smudge timing", "background": [1, 1, 1, 1], "backgroundHidden": False})
        time.sleep(4)
        run("tool.select", {"tool": "brush"})
        run("brush.setSize", {"sizePx": 400})
        for index in range(10):
            red, green, blue = COLORS[index % len(COLORS)]
            run("color.setPrimary", {"red": red, "green": green, "blue": blue})
            x = 200 + index * 380
            run("tool.paint.strokePath", {"samples": line(x, 200, x, 3900)})
        time.sleep(2)

    run("debug.render.frontBuffer", {"enabled": False})
    rows = [("paint (baseline)", swipe_frames("brush"))]
    run("edit.undo")
    for index in range(options.runs):
        rows.append((f"smudge run {index + 1}", swipe_frames("smudge")))
        if index + 1 < options.runs or not options.screenshot:
            run("edit.undo")
        time.sleep(1)
    if options.screenshot:
        shot = channel.call("screenshot")
        Path(options.screenshot).write_bytes(base64.b64decode(shot["png"]))
        run("edit.undo")
    run("debug.render.frontBuffer", {"enabled": True})
    for name, frames in rows:
        print(f"{name}: {frames['sampleCount']} frames, interval median {frames['intervalMedianMilliseconds']:.1f} ms, "
              f"p99 {frames['intervalP99Milliseconds']:.1f}, max {frames['intervalMaxMilliseconds']:.1f}, "
              f"dropped {frames['droppedFrames']}, cpu median {frames['cpuMedianMilliseconds']:.2f} ms, "
              f"p99 {frames['cpuP99Milliseconds']:.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
