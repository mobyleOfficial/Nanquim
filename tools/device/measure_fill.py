#!/usr/bin/env python3
"""Measures flood fill on a 4096² canvas (< 300 ms).

Run on the benchmark build (./gradlew :app:installProdBenchmark), with the control channel:

    adb forward tcp:7765 localabstract:nanquim-control
    adb shell am start -n com.mobyle.nanquim/com.mobyle.nanquim.MainActivity --es com.mobyle.nanquim.extra.CONTROL_TOKEN killtest
    tools/device/measure_fill.py [--runs 5]

Draws line art on a reference layer, then drops the color into regions of different sizes and
reads the engine's timing line ("Fill 4096x4096: read …, region …, shown … after the start").
"""

import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "automation"))
from nanquim_control import ControlChannel  # noqa: E402

TIMING = re.compile(r"Fill (\d+)x(\d+): read ([\d.]+) ms, region ([\d.]+) ms, (\d+) tiles, shown ([\d.]+) ms")


def line(x0, y0, x1, y1, count=80):
    return [[x0 + (x1 - x0) * i / (count - 1), y0 + (y1 - y0) * i / (count - 1), 1.0, 8.0 * i] for i in range(count)]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--adb", default="adb")
    options = parser.parse_args()
    channel = ControlChannel("127.0.0.1", 7765, "killtest")

    def run(command, arguments=None):
        return channel.call("execute", {"id": command, "arguments": arguments or {}})

    run("gallery.newCanvas", {"width": 4096, "height": 4096, "unit": "px", "dpi": 300, "name": "Fill timing",
                              "background": [1, 1, 1, 1], "backgroundHidden": False})
    time.sleep(4)
    run("tool.select", {"tool": "brush"})
    run("brush.setSize", {"sizePx": 12})
    run("color.setPrimary", {"red": 0.05, "green": 0.05, "blue": 0.05})
    for x in range(1024, 3073, 512):
        run("tool.paint.strokePath", {"samples": line(x, 1024, x, 3072)})
    for y in range(1024, 3073, 512):
        run("tool.paint.strokePath", {"samples": line(1024, y, 3072, y)})
    run("layer.setReference", {"layerId": 1, "enabled": True})
    run("layer.new")
    time.sleep(2)
    cases = [("whole canvas minus the grid", 200, 200), ("one 512² cell", 1280, 1280)]
    results = {name: [] for name, _, _ in cases}
    subprocess.run([options.adb, "logcat", "-c"], check=True)
    for run_index in range(options.runs):
        for name, x, y in cases:
            run("color.setPrimary", {"red": 0.2 + 0.1 * run_index % 0.7, "green": 0.5, "blue": 0.3})
            run("tool.fill.drop", {"x": x, "y": y, "threshold": 0.1})
            time.sleep(3)
            log = subprocess.run([options.adb, "logcat", "-d", "-s", "Nanquim:I", "NanquimEngine:I"], capture_output=True, text=True).stdout
            matches = TIMING.findall(log)
            if matches:
                results[name].append(matches[-1])
            subprocess.run([options.adb, "logcat", "-c"], check=True)
            run("edit.undo")
            time.sleep(1)
    for name, rows in results.items():
        shown = sorted(float(row[5]) for row in rows)
        region = sorted(float(row[3]) for row in rows)
        read = sorted(float(row[2]) for row in rows)
        if not shown:
            print(f"{name}: no timing lines found")
            continue
        median = len(shown) // 2
        print(f"{name}: shown median {shown[median]:.0f} ms (max {shown[-1]:.0f}), "
              f"read {read[median]:.0f} ms, region {region[median]:.0f} ms, tiles {rows[0][4]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
