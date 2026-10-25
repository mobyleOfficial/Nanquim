#!/usr/bin/env python3
"""First-stroke latency after a pause (the late first frame after an idle GPU).
Each run leaves the tablet idle long enough for the GPU to power down, then draws one stylus
stroke with `input stylus swipe`; with --awake the GPU is kept awake first exactly as a hovering
pen does (`debug.gpu.keepAwake`). Prints the first-sample latency to the front buffer and to the
display and the longest frame interval of each stroke. Use the benchmark build.

    python tools/device/measure_first_stroke.py --device <serial> [--runs 10]
"""

import argparse
import os
import statistics
import time

from kill_test import PACKAGE, adb, launch, wait_for_artwork

IDLE_SECONDS = 1.2
HOVER_SECONDS = 0.3


def stroke(device, channel, awake, index):
    time.sleep(IDLE_SECONDS)
    if awake:
        channel.execute("debug.gpu.keepAwake", {"milliseconds": 1500})
        time.sleep(HOVER_SECONDS)
    channel.execute("debug.stats.resetFrames")
    y = 700 + (index % 10) * 60
    adb(device, "shell", "input", "stylus", "swipe", "400", str(y), "1100", str(y), "250")
    time.sleep(0.6)
    stats = channel.execute("debug.stats.readFrames")
    latency = stats.get("latency") or {}
    return (
        latency.get("inputToFirstFrontBufferMaxMilliseconds"),
        latency.get("inputToDisplayMedianMilliseconds"),
        stats.get("intervalMaxMilliseconds"),
    )


def summary(name, values):
    present = [value for value in values if value]
    if not present:
        return f"{name}: no samples"
    return f"{name}: median {statistics.median(present):.1f} ms, max {max(present):.1f} ms ({len(present)} strokes)"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--runs", type=int, default=10)
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "am", "force-stop", PACKAGE)
    channel = launch(device)
    channel.execute("gallery.close")
    time.sleep(2)
    artwork = channel.execute("gallery.newCanvas", {"width": 2048, "height": 2048, "name": "First stroke"})["id"]
    wait_for_artwork(channel, artwork)
    channel.execute("view.fit")
    results = {False: [], True: []}
    for index in range(arguments.runs * 2):
        awake = index % 2 == 1
        results[awake].append(stroke(device, channel, awake, index))
    channel.execute("gallery.close")
    time.sleep(2)
    channel.execute("gallery.delete", {"ids": [artwork]})
    channel.close()
    for awake, rows in results.items():
        label = "pen hovering (GPU awake)" if awake else "cold (GPU powered down)"
        print(label)
        print("  " + summary("input to first front-buffer sample", [row[0] for row in rows]))
        print("  " + summary("input to display", [row[1] for row in rows]))
        print("  " + summary("longest frame interval", [row[2] for row in rows]))


if __name__ == "__main__":
    main()
