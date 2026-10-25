#!/usr/bin/env python3
"""Measures "Edit shape": dragging a corner of a QuickShape
rectangle redraws the stroke live (Engine.replaceStroke once per frame); every frame must stay
within the display rate.

Run on the benchmark build (./gradlew :app:installProdBenchmark), with the control channel:

    adb forward tcp:7765 localabstract:nanquim-control
    adb shell am start -n com.mobyle.nanquim/com.mobyle.nanquim.MainActivity --es com.mobyle.nanquim.extra.CONTROL_TOKEN killtest
    tools/device/measure_shape_edit.py [--runs 3] [--size 60]

Opens a new 4096² canvas fit to the screen, draws a wobbly rectangle with the stylus and holds it
until it becomes a shape, opens its control points (shapeSnap.edit), then for each run drags the
third corner with a finger (adb shell input swipe, 1.5 s) and reads the frame statistics. Canvas
px are mapped to the screen from the drawn corners and the handles reported for them. The front
buffer is off while measuring, so every vsync is a counted frame.
"""

import argparse
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "automation"))
from nanquim_control import ControlChannel  # noqa: E402

CORNERS = [(500, 900), (1100, 880), (1120, 1500), (490, 1510), (500, 900)]


def script():
    lines = ["input stylus motionevent DOWN %d %d" % CORNERS[0]]
    for (x0, y0), (x1, y1) in zip(CORNERS, CORNERS[1:]):
        for k in range(1, 11):
            t = k / 10
            wobble = 5 if k < 10 else 0
            lines.append("input stylus motionevent MOVE %d %d" % (round(x0 + (x1 - x0) * t + wobble * (k % 2)),
                                                                 round(y0 + (y1 - y0) * t - wobble * (k % 2))))
    lines += ["sleep 1.2", "input stylus motionevent UP %d %d" % CORNERS[-1]]
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
        path = Path(folder) / "shape_edit.sh"
        path.write_text(script(), encoding="utf-8", newline="\n")
        subprocess.run([options.adb, "push", str(path), "/data/local/tmp/"], check=True, capture_output=True)

    run("gallery.newCanvas", {"width": 4096, "height": 4096, "unit": "px", "dpi": 300,
                              "name": "Edit shape timing", "background": [1, 1, 1, 1], "backgroundHidden": False})
    time.sleep(4)
    run("tool.select", {"tool": "brush"})
    run("brush.setSize", {"sizePx": options.size})
    run("settings.quickShape.set", {"enabled": True, "delay": 600})
    run("view.fit")
    time.sleep(2)
    subprocess.run([options.adb, "shell", "sh", "/data/local/tmp/shape_edit.sh"], check=True)
    time.sleep(1)
    edit = run("shapeSnap.edit")
    if edit["shape"] != "rectangle":
        print(f"recognized {edit['shape']}, not a rectangle", file=sys.stderr)
        return 1
    handles = edit["handles"]
    (sx0, sy0), (sx2, sy2) = CORNERS[0], CORNERS[2]
    scale = ((sx2 - sx0) / (handles[2]["x"] - handles[0]["x"]) + (sy2 - sy0) / (handles[2]["y"] - handles[0]["y"])) / 2
    offset_x = sx0 - handles[0]["x"] * scale
    offset_y = sy0 - handles[0]["y"] * scale
    run("debug.render.frontBuffer", {"enabled": False})
    time.sleep(1)
    rows = []
    for index in range(options.runs):
        corner = run("shapeSnap.edit")["handles"][2]
        x = round(corner["x"] * scale + offset_x)
        y = round(corner["y"] * scale + offset_y)
        target = (x + 150, y + 200) if index % 2 == 0 else (x - 150, y - 200)
        run("debug.stats.resetFrames")
        subprocess.run([options.adb, "shell", "input", "swipe", str(x), str(y), str(target[0]), str(target[1]), "1500"],
                       check=True)
        time.sleep(0.5)
        rows.append((f"drag run {index + 1}", run("debug.stats.readFrames"), channel.call("getState")["history"]))
        time.sleep(1)
    run("debug.render.frontBuffer", {"enabled": True})
    run("shapeSnap.endEdit")
    for name, frames, history in rows:
        print(f"{name}: {frames['sampleCount']} frames, interval median "
              f"{frames['intervalMedianMilliseconds']:.1f} ms, p99 {frames['intervalP99Milliseconds']:.1f}, "
              f"max {frames['intervalMaxMilliseconds']:.1f}, dropped {frames['droppedFrames']}, "
              f"cpu median {frames['cpuMedianMilliseconds']:.2f} ms, p99 {frames['cpuP99Milliseconds']:.2f}; "
              f"last step {history['nextUndo']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
