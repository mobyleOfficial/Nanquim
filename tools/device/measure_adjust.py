#!/usr/bin/env python3
"""Measures frame times of an adjustment's live preview on a 4096² canvas fit to the screen
(the preview keeps the display rate).

Run on the benchmark build (./gradlew :app:installProdBenchmark), with the control channel:

    adb forward tcp:7765 localabstract:nanquim-control
    adb shell am start -n com.mobyle.nanquim/com.mobyle.nanquim.MainActivity --es com.mobyle.nanquim.extra.CONTROL_TOKEN killtest
    tools/device/measure_adjust.py [--runs 3] [--filter hueSaturationBrightness] [--actual-pixels]

Paints color bands over the whole canvas, opens the adjustment, and for each run resets the frame
statistics, injects a 4 s finger swipe across the canvas (which drags the main parameter through
its range, so every frame shows a new value) and reads the statistics. The adjustment is canceled
at the end. The front buffer is off while measuring, so every vsync is a counted frame.
"""

import argparse
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
    parser.add_argument("--filter", default="hueSaturationBrightness")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--open-document", action="store_true",
                        help="measure on the document already open instead of a new 4096² canvas with color bands")
    parser.add_argument("--swipe-y", default="1300",
                        help="screen row of the finger swipe (above the panel for Liquify, e.g. 800)")
    parser.add_argument("--actual-pixels", action="store_true",
                        help="measure at 100 %% zoom (full-resolution path) instead of fit to screen")
    options = parser.parse_args()
    channel = ControlChannel("127.0.0.1", 7765, "killtest")

    def run(command, arguments=None):
        return channel.call("execute", {"id": command, "arguments": arguments or {}})

    if not options.open_document:
        run("gallery.newCanvas", {"width": 4096, "height": 4096, "unit": "px", "dpi": 300,
                                  "name": "Adjustment timing", "background": [1, 1, 1, 1], "backgroundHidden": False})
        time.sleep(4)
        run("tool.select", {"tool": "brush"})
        run("brush.setSize", {"sizePx": 420})
        for index in range(11):
            red, green, blue = COLORS[index % len(COLORS)]
            run("color.setPrimary", {"red": red, "green": green, "blue": blue})
            x = 150 + index * 380
            run("tool.paint.strokePath", {"samples": line(x, 100, x, 4000)})
        time.sleep(8)

    run("debug.render.frontBuffer", {"enabled": False})
    run("view.actualPixels" if options.actual_pixels else "view.fit")
    time.sleep(2)
    run(f"adjust.{options.filter}.preview")
    time.sleep(3)
    rows = []
    for index in range(options.runs):
        run("debug.stats.resetFrames")
        start, end = ("200", "1400") if index % 2 == 0 else ("1400", "200")
        subprocess.run([options.adb, "shell", "input", "swipe", start, options.swipe_y, end, options.swipe_y, "4000"],
                       check=True)
        rows.append((f"run {index + 1}", run("debug.stats.readFrames")))
        time.sleep(2)
    run("adjust.cancel")
    run("debug.render.frontBuffer", {"enabled": True})
    for name, frames in rows:
        print(f"{options.filter} {name}: {frames['sampleCount']} frames, interval median "
              f"{frames['intervalMedianMilliseconds']:.1f} ms, p99 {frames['intervalP99Milliseconds']:.1f}, "
              f"max {frames['intervalMaxMilliseconds']:.1f}, dropped {frames['droppedFrames']}, "
              f"cpu median {frames['cpuMedianMilliseconds']:.2f} ms, p99 {frames['cpuP99Milliseconds']:.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
