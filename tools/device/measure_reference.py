#!/usr/bin/env python3
"""Measures the reference window's Canvas mode: while a stroke is
drawn, the window asks for the composite every 250 ms; every frame must stay within
the display rate. Compares the same stylus stroke with the window hidden and shown.

Run on the benchmark build (./gradlew :app:installProdBenchmark), with the control channel:

    adb forward tcp:7765 localabstract:nanquim-control
    adb shell am start -n com.mobyle.nanquim/com.mobyle.nanquim.MainActivity --es com.mobyle.nanquim.extra.CONTROL_TOKEN killtest
    tools/device/measure_reference.py [--runs 3] [--size 60]

Opens a new 4096² canvas fit to the screen; for each run clears the layer, resets the frame
statistics, draws a 3 s stylus swipe (adb shell input stylus swipe) and reads the statistics.
The front buffer is off while measuring, so every vsync is a counted frame.
"""

import argparse
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "automation"))
from nanquim_control import ControlChannel  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--size", type=float, default=60, help="brush size, canvas px")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--token", default="killtest")
    options = parser.parse_args()
    channel = ControlChannel("127.0.0.1", 7765, options.token)

    def run(command, arguments=None):
        return channel.call("execute", {"id": command, "arguments": arguments or {}})

    run("gallery.newCanvas", {"width": 4096, "height": 4096, "unit": "px", "dpi": 300,
                              "name": "Reference timing", "background": [1, 1, 1, 1], "backgroundHidden": False})
    time.sleep(4)
    run("tool.select", {"tool": "brush"})
    run("brush.setSize", {"sizePx": options.size})
    layer = channel.call("getState")["document"]["activeLayerId"]
    run("view.fit")
    run("debug.render.frontBuffer", {"enabled": False})
    time.sleep(2)
    rows = []
    for shown in (False, True):
        run("reference.set", {"mode": "canvas", "docked": "topEnd"})
        run("reference.toggle", {"visible": shown})
        time.sleep(1)
        for index in range(options.runs):
            run("layer.clear", {"layerId": layer})
            time.sleep(1)
            run("debug.stats.resetFrames")
            subprocess.run([options.adb, "shell", "input", "stylus", "swipe", "250", "1000", "1350", "1900", "3000"], check=True)
            time.sleep(0.5)
            rows.append((f"{'shown ' if shown else 'hidden'} run {index + 1}", run("debug.stats.readFrames")))
    run("debug.render.frontBuffer", {"enabled": True})
    run("reference.toggle", {"visible": False})
    for name, frames in rows:
        print(f"{name}: {frames['sampleCount']} frames, interval median "
              f"{frames['intervalMedianMilliseconds']:.1f} ms, p99 {frames['intervalP99Milliseconds']:.1f}, "
              f"max {frames['intervalMaxMilliseconds']:.1f}, dropped {frames['droppedFrames']}, "
              f"cpu median {frames['cpuMedianMilliseconds']:.2f} ms, p99 {frames['cpuP99Milliseconds']:.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
