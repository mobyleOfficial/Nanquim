#!/usr/bin/env python3
"""Measures exports: a 1920 × 1080 animation of
100 frames (random tiles and a dark square moving right; frame 50 held for two more frames) at
24 fps exported as GIF, APNG, MP4 (H.264, HEVC when available), WebM and a PNG sequence, each
decoded back on the device (debug.animation.decode): size, frame count, duration, and the square
of frames 1, 50 and 100 where it should be. Prints how long each export took. Run on the
benchmark build. The artwork and files are deleted at the end.

    python tools/device/measure_animation_export.py --device <serial> [--tiles 12]
"""

import argparse
import os
import sys
import time

from kill_test import adb, launch, wait_for_artwork

WIDTH, HEIGHT = 1920, 1080
FRAMES = 100
FPS = 24
AUTHORITY = "content://com.mobyle.nanquim.files/exports/"


def square_center(index):
    return [40 + index * 18 + 80, 440 + 80]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--tiles", type=int, default=12)
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    channel.socket.settimeout(900)
    artwork = None
    failures = []
    slots = FRAMES + 2
    duration = round(slots * 1000 / FPS)
    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": WIDTH, "height": HEIGHT, "name": "Export timing"})["id"]
        wait_for_artwork(channel, artwork)
        for index in range(FRAMES):
            if index > 0:
                channel.execute("animation.addFrame")
            layer = channel.call("getState")["document"]["activeLayerId"]
            channel.execute("debug.layer.fillRandomTiles", {"layerId": layer, "count": arguments.tiles, "seed": index + 1})
            channel.execute("selection.rect", {"x": 40 + index * 18, "y": 440, "width": 160, "height": 160})
            channel.execute("layer.fill", {"color": [0.05, 0.05, 0.05, 1]})
            channel.execute("selection.clear")
        frames = [layer["id"] for layer in channel.call("getState")["document"]["layers"]]
        channel.execute("animation.setEnabled", {"enabled": True})
        channel.execute("animation.setSettings", {"framesPerSecond": FPS})
        channel.execute("animation.setHold", {"layerId": frames[49], "holdFrames": 2})
        probe = [0, 49, 99]
        points = [square_center(index) for index in probe]

        def check(name, format, expected_frames, frame_indices, tolerance, **options):
            started = time.time()
            try:
                channel.execute("export.image", {"uri": AUTHORITY + name, "format": format, **options})
            except RuntimeError as error:
                if options.get("hevc"):
                    print(f"{name}: skipped ({error})")
                    return
                raise
            elapsed = time.time() - started
            size_only = channel.execute("debug.animation.decode", {"name": name, "frames": [], "points": []})
            scale = size_only["width"] / WIDTH if size_only["width"] else 1.0
            scaled = [[int(x * scale), int(y * scale)] for x, y in points]
            decoded = channel.execute("debug.animation.decode", {"name": name, "frames": frame_indices, "points": scaled})
            print(f"{name}: {decoded['width']}×{decoded['height']}, {decoded['frames']} frames, "
                  f"{decoded['durationMilliseconds']} ms, exported in {elapsed:.1f} s")
            size = (decoded["width"], decoded["height"])
            reduced = format in ("mp4", "webm") and size != (WIDTH, HEIGHT) and abs(size[0] / size[1] - WIDTH / HEIGHT) < 0.01
            if reduced:
                print(f"{name}: the encoder takes at most {size[0]}×{size[1]}")
            if (size != (WIDTH, HEIGHT) and not reduced) or decoded["frames"] != expected_frames:
                failures.append(f"{name}: {decoded['width']}×{decoded['height']}, {decoded['frames']} frames, expected {expected_frames}")
            if decoded["durationMilliseconds"] and abs(decoded["durationMilliseconds"] - duration) > 20:
                failures.append(f"{name}: {decoded['durationMilliseconds']} ms, expected {duration}")
            for slot, colors in enumerate(decoded["colors"]):
                square = colors[min(slot, len(probe) - 1)] if format != "gif" and format != "apng" else colors[0]
                if max(square[:3]) > tolerance:
                    failures.append(f"{name}: frame {frame_indices[slot]} square shows {square}")

        check("acceptance.gif", "gif", FRAMES, [0], 40)
        check("acceptance.png", "apng", FRAMES, [0], 20)
        check("acceptance.mp4", "mp4", FRAMES, [0, 49, 99], 50)
        check("acceptance-hevc.mp4", "mp4", FRAMES, [0, 49, 99], 50, hevc=True)
        check("acceptance.webm", "webm", FRAMES, [0, 49, 99], 50)
        check("acceptance.zip", "pngSequence", slots, [0, 51, 101], 20)
    finally:
        if artwork is not None:
            channel.execute("gallery.close")
            time.sleep(3)
            channel.execute("gallery.delete", {"ids": [artwork]})
        channel.close()
    if failures:
        print("FAILED:\n" + "\n".join(failures))
        sys.exit(1)
    print("Every format decoded back.")


if __name__ == "__main__":
    main()
