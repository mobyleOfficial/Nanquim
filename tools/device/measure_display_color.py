#!/usr/bin/env python3
"""Measures what the display conversion costs: a 4096² canvas
with every tile of --layers layers filled, first in sRGB (no LUT), then converted to Display P3
(a 3D LUT in the viewport shader and, on a wide-gamut display, a Display P3 window surface).

Run on the benchmark build (./gradlew :app:installProdBenchmark):

    python tools/device/measure_display_color.py --device <serial> [--layers 5] [--runs 3]

For each profile: the 10 s camera tour (debug.view.cameraTour) with frame statistics and the
GPU time of the screen pass (debug.nanquim.gpuprofile, logcat "GPU profile"), and --runs 5 s
stylus swipes on a new layer with the front buffer off.
"""

import argparse
import os
import re
import time

from kill_test import adb, launch, wait_for_artwork
from measure_deep_color import frames_line

GPU_PROFILE = re.compile(r"GPU profile over (\d+) frames \(ms per frame\): (.*)")


def measure(device, channel, runs):
    def run(command, arguments=None):
        return channel.execute(command, arguments or {})

    report = []
    adb(device, "logcat", "-c")
    run("debug.stats.resetFrames")
    run("debug.view.cameraTour")
    time.sleep(11)
    report.append(f"navigation (camera tour): {frames_line(run('debug.stats.readFrames'))}")
    log = adb(device, "logcat", "-d", "-s", "NanquimEngine:I", check=False)
    profiles = GPU_PROFILE.findall(log)
    if profiles:
        report.append(f"GPU during the tour (last report): {profiles[-1][1]}")
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
    run("edit.undo")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--layers", type=int, default=5)
    parser.add_argument("--runs", type=int, default=3)
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "setprop", "debug.nanquim.gpuprofile", "1")
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": 4096, "height": 4096, "name": "Display color timing"})["id"]
        wait_for_artwork(channel, artwork)
        for _ in range(arguments.layers - 1):
            channel.execute("layer.new")
        channel.execute("debug.layer.fillRandomTiles", {"layerId": 0, "count": -1, "seed": 7})
        time.sleep(20)
        wait_for_artwork(channel, artwork, timeout=120)
        channel.execute("view.fit")
        time.sleep(3)
        print(f"--- sRGB, 4096² × {arguments.layers} layers", flush=True)
        for row in measure(device, channel, arguments.runs):
            print(row, flush=True)
        start = time.time()
        channel.execute("canvas.convertProfile", {"profileId": "display-p3"})
        wait_for_artwork(channel, artwork, timeout=120)
        print(f"--- Display P3 (converted in {time.time() - start:.1f} s), "
              f"display {channel.execute('colorProfile.list')['display']}", flush=True)
        channel.execute("view.fit")
        time.sleep(3)
        for row in measure(device, channel, arguments.runs):
            print(row, flush=True)
    finally:
        channel.close()
        adb(device, "shell", "setprop", "debug.nanquim.gpuprofile", "0")


if __name__ == "__main__":
    main()
