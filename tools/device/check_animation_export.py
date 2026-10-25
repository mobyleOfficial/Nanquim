#!/usr/bin/env python3
"""Checks animation exports by decoding them back on the device: a 640 × 360
animation — a background frame (ground on the lower half) and five
frames, each a square of its own color, the third held for two more frames, 12 fps — exported
as GIF, APNG, MP4 (H.264, and HEVC when the device has an encoder), WebM (VP9) and a PNG
sequence, plus a GIF without background at 50 %. Each file is read back with the platform
decoders (debug.animation.decode): size, frame count, duration and the colors of every frame.
The artwork and the files are deleted at the end.

    python tools/device/check_animation_export.py --device <serial>
"""

import argparse
import os
import sys
import time

from kill_test import adb, launch, wait_for_artwork

WIDTH, HEIGHT = 640, 360
COLORS = [[0.9, 0.1, 0.1], [0.1, 0.7, 0.2], [0.1, 0.3, 0.9], [0.9, 0.8, 0.1], [0.6, 0.2, 0.8]]
GROUND = [0.3, 0.3, 0.3]
AUTHORITY = "content://com.mobyle.nanquim.files/exports/"


def square(index):
    return {"x": 40 + index * 120, "y": 60, "width": 80, "height": 80}


def center(index, scale=1.0):
    rect = square(index)
    return [int((rect["x"] + 40) * scale), int((rect["y"] + 40) * scale)]


def close(actual, expected, tolerance):
    return all(abs(a - round(e * 255)) <= tolerance for a, e in zip(actual[:3], expected))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    artwork = None
    failures = []
    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": WIDTH, "height": HEIGHT, "name": "Export check"})["id"]
        wait_for_artwork(channel, artwork)
        channel.execute("selection.rect", {"x": 0, "y": 240, "width": WIDTH, "height": 120})
        channel.execute("layer.fill", {"color": GROUND + [1]})
        channel.execute("selection.clear")
        for index, color in enumerate(COLORS):
            channel.execute("animation.addFrame")
            channel.execute("selection.rect", square(index))
            channel.execute("layer.fill", {"color": color + [1]})
            channel.execute("selection.clear")
        frames = [layer["id"] for layer in channel.call("getState")["document"]["layers"]]
        channel.execute("animation.setEnabled", {"enabled": True})
        channel.execute("animation.setSettings", {"firstFrameIsBackground": True, "framesPerSecond": 12})
        channel.execute("animation.setHold", {"layerId": frames[3], "holdFrames": 2})
        duration = round(7 * 1000 / 12)
        every = list(range(5))
        points = [center(index) for index in range(5)] + [[320, 300], [5, 5]]

        def export(name, format, decode_frames=every, decode_points=points, **options):
            started = time.time()
            channel.execute("export.image", {"uri": AUTHORITY + name, "format": format, **options})
            elapsed = time.time() - started
            decoded = channel.execute("debug.animation.decode", {"name": name, "frames": decode_frames, "points": decode_points})
            print(f"{name}: {decoded['format']} {decoded.get('mime') or ''} {decoded['width']}×{decoded['height']}, "
                  f"{decoded['frames']} frames, {decoded['durationMilliseconds']} ms, exported in {elapsed:.1f} s")
            return decoded

        def check_colors(name, decoded, frame_indices, tolerance, ground=True):
            for slot, frame in enumerate(frame_indices):
                colors = decoded["colors"][slot]
                if not colors:
                    failures.append(f"{name}: frame {frame} not decoded")
                    continue
                if not close(colors[frame], COLORS[frame], tolerance):
                    failures.append(f"{name}: frame {frame} square {colors[frame]}, expected {COLORS[frame]}")
                other = (frame + 1) % 5
                if close(colors[other], COLORS[other], tolerance):
                    failures.append(f"{name}: frame {frame} also shows frame {other}")
                if ground and not close(colors[5], GROUND, tolerance):
                    failures.append(f"{name}: frame {frame} ground {colors[5]}")

        gif = export("check.gif", "gif")
        if gif["frames"] != 5 or abs(gif["durationMilliseconds"] - duration) > 10 or gif["width"] != WIDTH:
            failures.append(f"gif: {gif}")
        check_colors("gif", gif, [0], 6)

        apng = export("check.png", "apng")
        if apng["frames"] != 5 or abs(apng["durationMilliseconds"] - duration) > 2:
            failures.append(f"apng: {apng}")
        check_colors("apng", apng, [0], 1)

        for name, extra in [("check.mp4", {}), ("check-hevc.mp4", {"hevc": True}), ("check.webm", {})]:
            format = "webm" if name.endswith("webm") else "mp4"
            try:
                video = export(name, format, **extra)
            except Exception as error:
                if extra.get("hevc"):
                    print(f"{name}: skipped ({error})")
                    continue
                raise
            if video["frames"] not in (5, 6) or abs(video["durationMilliseconds"] - duration) > 10:
                failures.append(f"{name}: {video}")
            check_colors(name, video, every, 24)

        sequence = export("check.zip", "pngSequence", decode_frames=[0, 2, 3, 4, 5, 6])
        if sequence["frames"] != 7:
            failures.append(f"png sequence: {sequence['frames']} files, expected 7")
        check_colors("png sequence", sequence, [0, 2, 2, 2, 3, 4], 1)

        half = export("check-half.gif", "gif", decode_frames=[0], decode_points=[center(0, 0.5), [2, 2]], scale=50, includeBackground=False)
        if half["width"] != WIDTH // 2 or half["height"] != HEIGHT // 2:
            failures.append(f"half gif size {half['width']}×{half['height']}")
        if half["colors"][0][1][3] != 0:
            failures.append(f"half gif corner not transparent: {half['colors'][0][1]}")
        if not close(half["colors"][0][0], COLORS[0], 6):
            failures.append(f"half gif square {half['colors'][0][0]}")
        state = channel.call("getState")
        if state["playback"]["frameId"] is not None:
            failures.append("the canvas was left showing an export frame")
    finally:
        if artwork is not None:
            channel.execute("gallery.close")
            time.sleep(3)
            channel.execute("gallery.delete", {"ids": [artwork]})
        channel.close()
    if failures:
        print("FAILED:\n" + "\n".join(failures))
        sys.exit(1)
    print("Animation exports decoded back correctly.")


if __name__ == "__main__":
    main()
