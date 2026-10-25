#!/usr/bin/env python3
"""Measures a stroke with the radial symmetry's 8 copies against the same stroke alone: every
stroke frame must stay within the display rate.

Run on the benchmark build (./gradlew :app:installProdBenchmark), with the control channel:

    adb forward tcp:7765 localabstract:nanquim-control
    adb shell am start -n com.mobyle.nanquim/com.mobyle.nanquim.MainActivity --es com.mobyle.nanquim.extra.CONTROL_TOKEN killtest
    tools/device/measure_symmetry.py [--runs 3] [--size 60]

Opens a new 4096² canvas fit to the screen, shows a radial symmetry guide (8 segments, mirrored)
at the canvas center, and for each run resets the frame statistics, injects a 5 s stylus swipe
and reads the statistics: first with the layer not assisted (one copy), then assisted (8 copies).
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
                              "name": "Symmetry timing", "background": [1, 1, 1, 1], "backgroundHidden": False})
    time.sleep(4)
    run("tool.select", {"tool": "brush"})
    run("brush.setSize", {"sizePx": options.size})
    run("guide.set", {"type": "symmetry", "visible": True, "symmetry": "radial", "segments": 8,
                      "rotational": False, "centerX": 2048, "centerY": 2048, "symmetryRotation": 0.0})
    layer = channel.call("getState")["document"]["activeLayerId"]
    run("debug.render.frontBuffer", {"enabled": False})
    run("view.fit")
    time.sleep(2)
    rows = []
    for assisted in (False, True):
        run("layer.setAssisted", {"layerId": layer, "enabled": assisted})
        for index in range(options.runs):
            run("debug.stats.resetFrames")
            subprocess.run([options.adb, "shell", "input", "stylus", "swipe", "350", "700", "1250", "1300", "5000"],
                           check=True)
            rows.append((f"{'8 copies' if assisted else '1 copy  '} run {index + 1}", run("debug.stats.readFrames")))
            time.sleep(2)
    run("debug.render.frontBuffer", {"enabled": True})
    for name, frames in rows:
        print(f"{name}: {frames['sampleCount']} frames, interval median "
              f"{frames['intervalMedianMilliseconds']:.1f} ms, p99 {frames['intervalP99Milliseconds']:.1f}, "
              f"max {frames['intervalMaxMilliseconds']:.1f}, dropped {frames['droppedFrames']}, "
              f"cpu median {frames['cpuMedianMilliseconds']:.2f} ms, p99 {frames['cpuP99Milliseconds']:.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
