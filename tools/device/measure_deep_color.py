#!/usr/bin/env python3
"""Measures a 4096² canvas in 8 and in 16 bits per channel:
navigation, a stroke and a flood fill, so the cost of 16-bit documents (half floats, 8 bytes a
pixel) is known against the same work in 8 bits.

Run on the benchmark build (./gradlew :app:installProdBenchmark):

    python tools/device/measure_deep_color.py --device <serial> [--layers 10] [--runs 3]

For each depth: a new 4096² canvas with --layers layers, every tile filled with the debug test
pattern (worst-case memory); then
- navigation: the 10 s camera tour (debug.view.cameraTour), frame statistics read after it;
- stroke: a 5 s stylus swipe on a new layer, the canvas fit to the screen, front buffer off so
  every vsync is a counted frame (--runs times);
- fill: line art on a new layer, then the color dropped into the whole canvas around it and into
  one 512² cell, timed by the engine's "Fill …" log line (--runs times each, undone after each).
"""

import argparse
import os
import re
import time

from kill_test import adb, launch, wait_for_artwork

FILL_TIMING = re.compile(r"Fill (\d+)x(\d+): read ([\d.]+) ms, region ([\d.]+) ms, (\d+) tiles, shown ([\d.]+) ms")


def frames_line(frames):
    return (f"{frames['sampleCount']} frames, median {frames['intervalMedianMilliseconds']:.1f} ms, "
            f"p99 {frames['intervalP99Milliseconds']:.1f}, max {frames['intervalMaxMilliseconds']:.1f}, "
            f"dropped {frames['droppedFrames']}")


def line(x0, y0, x1, y1, count=80):
    return [[x0 + (x1 - x0) * i / (count - 1), y0 + (y1 - y0) * i / (count - 1), 1.0, 8.0 * i] for i in range(count)]


def measure(device, channel, bits, layers, runs):
    def run(command, arguments=None):
        return channel.execute(command, arguments or {})

    run("gallery.close")
    time.sleep(3)
    artwork = run("gallery.newCanvas", {"width": 4096, "height": 4096, "name": f"Deep color timing {bits}",
                                        "bitsPerChannel": bits})["id"]
    wait_for_artwork(channel, artwork)
    for _ in range(layers - 1):
        run("layer.new")
    run("debug.layer.fillRandomTiles", {"layerId": 0, "count": -1, "seed": 7})
    time.sleep(20)
    run("view.fit")
    time.sleep(3)
    report = []

    run("debug.stats.resetFrames")
    run("debug.view.cameraTour")
    time.sleep(11)
    report.append(f"navigation (camera tour): {frames_line(run('debug.stats.readFrames'))}")

    run("layer.new")
    run("tool.select", {"tool": "brush"})
    run("brush.setSize", {"sizePx": 60})
    run("debug.render.frontBuffer", {"enabled": False})
    time.sleep(2)
    for index in range(runs):
        run("debug.stats.resetFrames")
        adb(device, "shell", "input", "stylus", "swipe", "350", "700", "1250", "1300", "5000")
        report.append(f"stroke run {index + 1}: {frames_line(run('debug.stats.readFrames'))}")
        time.sleep(2)
    run("debug.render.frontBuffer", {"enabled": True})

    run("layer.new")
    run("brush.setSize", {"sizePx": 12})
    run("color.setPrimary", {"red": 0.05, "green": 0.05, "blue": 0.05})
    for x in range(1024, 3073, 512):
        run("tool.paint.strokePath", {"samples": line(x, 1024, x, 3072)})
    for y in range(1024, 3073, 512):
        run("tool.paint.strokePath", {"samples": line(1024, y, 3072, y)})
    time.sleep(2)
    for name, x, y in (("whole canvas minus the grid", 200, 200), ("one 512² cell", 1280, 1280)):
        shown = []
        for index in range(runs):
            adb(device, "logcat", "-c")
            run("color.setPrimary", {"red": 0.2 + 0.1 * index, "green": 0.5, "blue": 0.3})
            run("tool.fill.drop", {"x": x, "y": y, "threshold": 0.1})
            time.sleep(3)
            log = adb(device, "logcat", "-d", "-s", "Nanquim:I", "NanquimEngine:I", check=False)
            matches = FILL_TIMING.findall(log)
            if matches:
                shown.append(float(matches[-1][5]))
                report.append(f"  run {index + 1}: read {matches[-1][2]} ms, region {matches[-1][3]} ms, "
                              f"{matches[-1][4]} tiles, shown {matches[-1][5]} ms")
            run("edit.undo")
            time.sleep(1)
        shown.sort()
        report.append(f"fill {name}: " + (f"shown median {shown[len(shown) // 2]:.0f} ms (max {shown[-1]:.0f})"
                                          if shown else "no timing lines found"))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--layers", type=int, default=10)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--bits", type=int, nargs="+", default=[8, 16], choices=[8, 16])
    arguments = parser.parse_args()
    adb(arguments.device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(arguments.device)
    for bits in arguments.bits:
        print(f"--- {bits} bits, 4096² × {arguments.layers} layers")
        for row in measure(arguments.device, channel, bits, arguments.layers, arguments.runs):
            print(row, flush=True)
    channel.close()


if __name__ == "__main__":
    main()
