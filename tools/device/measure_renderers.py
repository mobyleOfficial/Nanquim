#!/usr/bin/env python3
"""Measures the app on both GPU backends side by side: a
4096² canvas with every tile of --layers layers filled is made once, then reopened with GL ES and
with Vulkan in turn (--rounds alternations; the developer property `debug.nanquim.renderer`,
which overrides Diagnostics › Renderer), each time with the 10 s camera tour (frame statistics and the
GPU time per frame of debug.nanquim.gpuprofile, logcat "GPU profile") and --runs 5 s stylus swipes
on a new layer, front-buffered and then double-buffered, undone after:
frame statistics, the engine's input-to-GPU latency of front-buffered updates, the first
front-buffered update of each stroke (entering the mode included) and the input-to-display latency
of double-buffered frames. The test artwork is deleted at the end.

Run on the benchmark build (./gradlew :app:installProdBenchmark):

    python tools/device/measure_renderers.py --device <serial> [--layers 5] [--runs 3] [--rounds 2]
"""

import argparse
import os
import re
import time

from kill_test import adb, launch, wait_for_artwork
from measure_deep_color import frames_line

GPU_PROFILE = re.compile(r"GPU profile over (\d+) frames \(ms per frame\): (.*)")
RENDERERS = ["gles", "vulkan"]


def start(device, renderer):
    adb(device, "shell", "setprop", "debug.nanquim.renderer", renderer)
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    adb(device, "logcat", "-c")
    channel = launch(device)
    time.sleep(2)
    log = adb(device, "logcat", "-d", "-s", "NanquimEngine:I", check=False)
    expected = "Render thread on Vulkan" if renderer == "vulkan" else "Render thread on OpenGL ES"
    if expected not in log:
        raise RuntimeError(f"the engine did not start on {renderer}")
    return channel


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
    for front_buffer in (True, False):
        run("debug.render.frontBuffer", {"enabled": front_buffer})
        time.sleep(2)
        for index in range(runs):
            run("debug.stats.resetFrames")
            adb(device, "shell", "input", "stylus", "swipe", "350", "700", "1250", "1300", "5000")
            time.sleep(1)
            frames = run("debug.stats.readFrames")
            report.append(f"stroke {'front' if front_buffer else 'double'}-buffered run {index + 1}: "
                          f"{frames_line(frames)}; {latency_line(frames['latency'])}")
            time.sleep(2)
    run("debug.render.frontBuffer", {"enabled": True})
    for _ in range(2 * runs + 1):
        run("edit.undo")
    return report


def latency_line(latency):
    if latency.get("frontBufferSampleCount", 0) > 0:
        return (f"input-to-GPU median {latency['inputToFrontBufferMedianMilliseconds']:.1f} ms, "
                f"p95 {latency['inputToFrontBufferP95Milliseconds']:.1f} over {latency['frontBufferSampleCount']} "
                f"updates, first update {latency['inputToFirstFrontBufferMedianMilliseconds']:.1f} ms")
    return f"input-to-display median {latency.get('inputToDisplayMedianMilliseconds', 0):.1f} ms"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--layers", type=int, default=5)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--rounds", type=int, default=2)
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "setprop", "debug.nanquim.gpuprofile", "1")
    artwork = None
    try:
        channel = start(device, "gles")
        try:
            channel.execute("gallery.close")
            time.sleep(3)
            artwork = channel.execute("gallery.newCanvas", {"width": 4096, "height": 4096, "name": "Renderer timing"})["id"]
            wait_for_artwork(channel, artwork)
            for _ in range(arguments.layers - 1):
                channel.execute("layer.new")
            channel.execute("debug.layer.fillRandomTiles", {"layerId": 0, "count": -1, "seed": 7})
            time.sleep(20)
            wait_for_artwork(channel, artwork, timeout=120)
        finally:
            channel.close()
        for round_index in range(arguments.rounds):
            for renderer in RENDERERS:
                channel = start(device, renderer)
                try:
                    channel.execute("gallery.close")
                    time.sleep(3)
                    channel.execute("gallery.open", {"id": artwork})
                    wait_for_artwork(channel, artwork, timeout=120)
                    time.sleep(10)
                    channel.execute("view.fit")
                    time.sleep(3)
                    print(f"--- {renderer}, round {round_index + 1}, 4096² × {arguments.layers} layers", flush=True)
                    for row in measure(device, channel, arguments.runs):
                        print(row, flush=True)
                finally:
                    channel.close()
    finally:
        adb(device, "shell", "setprop", "debug.nanquim.gpuprofile", "0")
        adb(device, "shell", "setprop", "debug.nanquim.renderer", "gles")
        if artwork is not None:
            channel = start(device, "gles")
            try:
                channel.execute("gallery.close")
                time.sleep(3)
                channel.execute("gallery.delete", {"ids": [artwork]})
            finally:
                channel.close()


if __name__ == "__main__":
    main()
