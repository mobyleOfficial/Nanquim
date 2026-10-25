#!/usr/bin/env python3
"""Measures QuickShape on the stroke: a rectangle drawn with the
stylus and held still until it becomes a shape (recognition, 150 ms morph, commit) against the
same stroke lifted at once; every frame must stay within the display rate.

Run on the benchmark build (./gradlew :app:installProdBenchmark), with the control channel:

    adb forward tcp:7765 localabstract:nanquim-control
    adb shell am start -n com.mobyle.nanquim/com.mobyle.nanquim.MainActivity --es com.mobyle.nanquim.extra.CONTROL_TOKEN killtest
    tools/device/measure_quickshape.py [--runs 3] [--size 60]

Opens a new 4096² canvas fit to the screen and for each run resets the frame statistics, injects
the stroke (adb shell input stylus motionevent, then a 1.2 s hold for the shape) and reads the
statistics. The front buffer is off while measuring, so every vsync is a counted frame. The
recognition time is in logcat (tag StrokeInput, "QuickShape: … in N us").
"""

import argparse
import math
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "automation"))
from nanquim_control import ControlChannel  # noqa: E402

CORNERS = [(700, 450), (1500, 430), (1520, 1000), (690, 1010), (700, 450)]


def script(hold):
    points = []
    for (x0, y0), (x1, y1) in zip(CORNERS, CORNERS[1:]):
        for k in range(10):
            t = k / 10
            points.append((x0 + (x1 - x0) * t + 6 * math.sin(k * 1.7), y0 + (y1 - y0) * t + 6 * math.cos(k * 2.3)))
    lines = ["input stylus motionevent DOWN %d %d" % CORNERS[0]]
    lines += ["input stylus motionevent MOVE %d %d" % (round(x), round(y)) for x, y in points[1:] + [CORNERS[-1]]]
    if hold:
        lines.append("sleep 1.2")
    lines.append("input stylus motionevent UP %d %d" % CORNERS[-1])
    return "\n".join(lines) + "\n"


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

    with tempfile.TemporaryDirectory() as folder:
        for hold in (False, True):
            path = Path(folder) / f"quickshape_{'hold' if hold else 'lift'}.sh"
            path.write_text(script(hold), encoding="utf-8", newline="\n")
            subprocess.run([options.adb, "push", str(path), "/data/local/tmp/"], check=True, capture_output=True)

    run("gallery.newCanvas", {"width": 4096, "height": 4096, "unit": "px", "dpi": 300,
                              "name": "QuickShape timing", "background": [1, 1, 1, 1], "backgroundHidden": False})
    time.sleep(4)
    run("tool.select", {"tool": "brush"})
    run("brush.setSize", {"sizePx": options.size})
    run("settings.quickShape.set", {"enabled": True, "delay": 600})
    layer = channel.call("getState")["document"]["activeLayerId"]
    run("debug.render.frontBuffer", {"enabled": False})
    run("view.fit")
    time.sleep(2)
    rows = []
    for hold in (False, True):
        for index in range(options.runs):
            run("layer.clear", {"layerId": layer})
            time.sleep(1)
            run("debug.stats.resetFrames")
            name = "hold" if hold else "lift"
            subprocess.run([options.adb, "shell", "sh", f"/data/local/tmp/quickshape_{name}.sh"], check=True)
            time.sleep(0.5)
            history = channel.call("getState")["history"]
            rows.append((f"{'held (shape)' if hold else 'lifted     '} run {index + 1}", run("debug.stats.readFrames"), history))
            time.sleep(1)
    run("debug.render.frontBuffer", {"enabled": True})
    for name, frames, history in rows:
        print(f"{name}: {frames['sampleCount']} frames, interval median "
              f"{frames['intervalMedianMilliseconds']:.1f} ms, p99 {frames['intervalP99Milliseconds']:.1f}, "
              f"max {frames['intervalMaxMilliseconds']:.1f}, dropped {frames['droppedFrames']}, "
              f"cpu median {frames['cpuMedianMilliseconds']:.2f} ms, p99 {frames['cpuP99Milliseconds']:.2f}; "
              f"last step {history['nextUndo']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
