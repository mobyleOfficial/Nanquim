#!/usr/bin/env python3
"""Checks video layers on the device (low confidence):
a 640 × 360 animation of five colored squares is exported as MP4 into the app's export folder,
then imported into a 1280 × 720 artwork as a video layer (`video.import` with the FileProvider
URI). The document composite must show the first frame fitted to the canvas; scrubbing to the
fourth frame must show it; painting is refused with the rasterize prompt; rasterize and undo
switch the layer kind; the time and pixels survive closing and reopening. Artworks deleted at
the end.

    python tools/device/check_video_layer.py --device <serial> [--out <folder>]
"""

import argparse
import base64
import io
import os
import sys
import time

from PIL import Image

from kill_test import adb, launch, wait_for_artwork

COLORS = [[0.9, 0.1, 0.1], [0.1, 0.7, 0.2], [0.1, 0.3, 0.9], [0.9, 0.8, 0.1], [0.6, 0.2, 0.8]]
URI = "content://com.mobyle.nanquim.files/exports/video-layer.mp4"


def square(index):
    return {"x": 40 + index * 120, "y": 60, "width": 80, "height": 80}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--out", default="video-layer-check", help="folder for the screenshots")
    arguments = parser.parse_args()
    device = arguments.device
    os.makedirs(arguments.out, exist_ok=True)
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    artworks = []
    failures = []

    def composite():
        time.sleep(1.0)
        return Image.open(io.BytesIO(base64.b64decode(channel.call("screenshot")["png"]))).convert("RGB")

    def color_at(image, index, scale):
        rect = square(index)
        factor = image.width / 1280 * scale
        return image.getpixel((int((rect["x"] + 40) * factor), int((rect["y"] + 40) * factor)))

    def close(actual, expected, tolerance=30):
        return all(abs(a - e * 255) <= tolerance for a, e in zip(actual, expected))

    def layer_kind(layer_id):
        layers = channel.call("getState")["document"]["layers"]
        return next(layer for layer in layers if layer["id"] == layer_id)

    try:
        channel.execute("gallery.close")
        time.sleep(3)
        source = channel.execute("gallery.newCanvas", {"width": 640, "height": 360, "name": "Video source"})["id"]
        artworks.append(source)
        wait_for_artwork(channel, source)
        for index, color in enumerate(COLORS):
            if index > 0:
                channel.execute("animation.addFrame")
            channel.execute("selection.rect", square(index))
            channel.execute("layer.fill", {"color": color + [1]})
            channel.execute("selection.clear")
        channel.execute("animation.setEnabled", {"enabled": True})
        channel.execute("animation.setSettings", {"framesPerSecond": 10})
        channel.execute("export.image", {"uri": URI, "format": "mp4"})
        channel.execute("gallery.close")
        time.sleep(3)

        target = channel.execute("gallery.newCanvas", {"width": 1280, "height": 720, "name": "Video layer"})["id"]
        artworks.append(target)
        wait_for_artwork(channel, target)
        imported = channel.execute("video.import", {"uri": URI})
        layer = imported["layerId"]
        print("imported:", imported)
        if abs(imported["durationMicroseconds"] - 500_000) > 50_000:
            failures.append(f"duration {imported['durationMicroseconds']} µs, expected 500000")
        channel.execute("video.setTime", {"layerId": layer, "timeMicroseconds": 0, "wait": True})
        image = composite()
        image.save(os.path.join(arguments.out, "first.png"))
        if not close(color_at(image, 0, 2), COLORS[0]):
            failures.append(f"first frame: square 1 is {color_at(image, 0, 2)}")
        channel.execute("video.setTime", {"layerId": layer, "timeMicroseconds": 350_000, "wait": True})
        image = composite()
        image.save(os.path.join(arguments.out, "fourth.png"))
        if not close(color_at(image, 3, 2), COLORS[3]) or close(color_at(image, 0, 2), COLORS[0]):
            failures.append(f"fourth frame: squares {color_at(image, 3, 2)}, {color_at(image, 0, 2)}")
        try:
            channel.execute("layer.fill", {"color": [0, 0, 0, 1]})
            failures.append("filling a video layer was not refused")
        except RuntimeError:
            pass
        if channel.call("getState")["rasterizePrompt"] != layer:
            failures.append("no rasterize prompt")
        channel.execute("video.rasterize", {"layerId": layer})
        if layer_kind(layer)["type"] != "raster":
            failures.append(f"rasterized layer is {layer_kind(layer)}")
        channel.execute("edit.undo")
        state = layer_kind(layer)
        if state["type"] != "video" or state["video"]["timeMicroseconds"] != 350_000:
            failures.append(f"undo of rasterize: {state}")
        channel.execute("document.save")
        time.sleep(2)
        channel.execute("gallery.close")
        time.sleep(3)
        channel.execute("gallery.open", {"id": target})
        wait_for_artwork(channel, target)
        layers = channel.call("getState")["document"]["layers"]
        videos = [entry for entry in layers if entry["type"] == "video"]
        if len(videos) != 1 or videos[0]["video"]["timeMicroseconds"] != 350_000:
            failures.append(f"reopened layers: {layers}")
        image = composite()
        if not close(color_at(image, 3, 2), COLORS[3]):
            failures.append(f"reopened: square 4 is {color_at(image, 3, 2)}")
    finally:
        channel.execute("gallery.close")
        time.sleep(3)
        if artworks:
            channel.execute("gallery.delete", {"ids": artworks})
        channel.close()
    if failures:
        print("FAILED:\n" + "\n".join(failures))
        sys.exit(1)
    print("Video layer check passed.")


if __name__ == "__main__":
    main()
