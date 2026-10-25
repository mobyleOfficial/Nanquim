#!/usr/bin/env python3
"""Measures animation playback: a 1920 × 1080
artwork with 100 frames, each with random tiles (--tiles per frame; 40 covers the whole canvas)
and a square that moves, played for --seconds at each --fps. Reports the frames shown against
the frames due (a frame the clock skipped is a dropped animation frame) and the engine's frame
intervals and CPU time. Run on the benchmark build. The artwork is deleted at the end.

    python tools/device/measure_playback.py --device <serial> [--tiles 12] [--fps 24 60]
"""

import argparse
import os
import sys
import time

from kill_test import adb, launch, wait_for_artwork

WIDTH, HEIGHT = 1920, 1080
FRAMES = 100


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--tiles", type=int, default=12)
    parser.add_argument("--fps", type=int, nargs="+", default=[24, 60])
    parser.add_argument("--seconds", type=float, default=10)
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    artwork = None
    failures = []
    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": WIDTH, "height": HEIGHT, "name": "Playback timing"})["id"]
        wait_for_artwork(channel, artwork)
        started = time.time()
        for index in range(FRAMES):
            if index > 0:
                channel.execute("animation.addFrame")
            layer = channel.call("getState")["document"]["activeLayerId"]
            channel.execute("debug.layer.fillRandomTiles", {"layerId": layer, "count": arguments.tiles, "seed": index + 1})
            channel.execute("selection.rect", {"x": 40 + index * 18, "y": 440, "width": 160, "height": 160})
            channel.execute("layer.fill", {"color": [0.05, 0.05, 0.05, 1]})
            channel.execute("selection.clear")
        print(f"built {FRAMES} frames in {time.time() - started:.0f} s")
        channel.execute("animation.setEnabled", {"enabled": True})
        channel.execute("view.fit")
        time.sleep(3)
        for fps in arguments.fps:
            channel.execute("animation.setSettings", {"framesPerSecond": fps})
            channel.execute("animation.play")
            time.sleep(FRAMES / fps + 1)
            channel.execute("animation.pause")
            time.sleep(1)
            channel.execute("debug.stats.resetFrames")
            channel.execute("animation.play")
            begin = time.time()
            time.sleep(arguments.seconds)
            shown = channel.call("getState")["playback"]["framesShown"]
            elapsed = time.time() - begin
            frames = channel.execute("debug.stats.readFrames")
            channel.execute("animation.pause")
            due = elapsed * fps
            print(f"{fps} fps: shown {shown} of {due:.0f} due ({shown / due * 100:.1f} %), "
                  f"engine frames {frames['sampleCount']} ({frames['incompleteFrames']} incomplete), interval median {frames['intervalMedianMilliseconds']:.1f} ms, "
                  f"p99 {frames['intervalP99Milliseconds']:.1f}, max {frames['intervalMaxMilliseconds']:.1f}, "
                  f"cpu median {frames['cpuMedianMilliseconds']:.2f} ms, p99 {frames['cpuP99Milliseconds']:.2f}")
            if shown < due * 0.97:
                failures.append(f"{fps} fps: {shown} frames shown, {due:.0f} due")
            time.sleep(1)
    finally:
        if artwork is not None:
            channel.execute("gallery.close")
            time.sleep(3)
            channel.execute("gallery.delete", {"ids": [artwork]})
        channel.close()
    if failures:
        print("FAILED:\n" + "\n".join(failures))
        sys.exit(1)
    print("Playback kept its frame rate.")


if __name__ == "__main__":
    main()
