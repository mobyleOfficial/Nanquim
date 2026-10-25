#!/usr/bin/env python3
"""Checks private layers and layer select by touch on the device: a red layer and a
private blue one; the canvas shows both, a PNG export
(decoded back with debug.animation.decode) shows only the red one, and the canvas shows both
again afterwards; `layer.selectAt` picks the layer under each square and nothing on empty canvas.
The artwork is deleted at the end.

    python tools/device/check_private_layers.py --device <serial>
"""

import argparse
import base64
import io
import os
import sys
import time

from PIL import Image

from kill_test import adb, launch, wait_for_artwork

WIDTH, HEIGHT = 800, 600
RED = {"x": 100, "y": 100, "width": 200, "height": 200}
BLUE = {"x": 450, "y": 250, "width": 200, "height": 200}


def center(rect):
    return [rect["x"] + rect["width"] // 2, rect["y"] + rect["height"] // 2]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    arguments = parser.parse_args()
    adb(arguments.device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(arguments.device)
    artwork = None
    failures = []

    def canvas_color(point):
        time.sleep(1.0)
        image = Image.open(io.BytesIO(base64.b64decode(channel.call("screenshot")["png"]))).convert("RGB")
        scale = image.width / WIDTH
        return image.getpixel((int(point[0] * scale), int(point[1] * scale)))

    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": WIDTH, "height": HEIGHT, "name": "Private check"})["id"]
        wait_for_artwork(channel, artwork)
        red_layer = channel.call("getState")["document"]["activeLayerId"]
        channel.execute("selection.rect", RED)
        channel.execute("layer.fill", {"color": [0.9, 0.1, 0.1, 1]})
        channel.execute("selection.clear")
        blue_layer = channel.execute("layer.new")["layerId"]
        channel.execute("selection.rect", BLUE)
        channel.execute("layer.fill", {"color": [0.1, 0.2, 0.9, 1]})
        channel.execute("selection.clear")
        channel.execute("layer.setPrivate", {"layerId": blue_layer, "enabled": True})
        if canvas_color(center(BLUE))[2] < 150:
            failures.append("the canvas does not show the private layer")
        channel.execute("export.image", {"uri": "content://com.mobyle.nanquim.files/exports/private.png", "format": "png"})
        exported = channel.execute("debug.animation.decode", {"name": "private.png", "frames": [0], "points": [center(RED), center(BLUE)]})
        red, blue = exported["colors"][0]
        if red[0] < 200:
            failures.append(f"export lost the red layer: {red}")
        if blue[2] > 200 and blue[0] < 100:
            failures.append(f"export shows the private layer: {blue}")
        if canvas_color(center(BLUE))[2] < 150:
            failures.append("the canvas does not show the private layer after exporting")
        channel.execute("layer.select", {"layerId": red_layer})
        picked_blue = channel.execute("layer.selectAt", {"x": center(BLUE)[0], "y": center(BLUE)[1]})["layerId"]
        picked_red = channel.execute("layer.selectAt", {"x": center(RED)[0], "y": center(RED)[1]})["layerId"]
        picked_none = channel.execute("layer.selectAt", {"x": 20, "y": 580})["layerId"]
        if (picked_blue, picked_red, picked_none) != (blue_layer, red_layer, None):
            failures.append(f"layer select picked {picked_blue}, {picked_red}, {picked_none}")
        if channel.call("getState")["document"]["activeLayerId"] != red_layer:
            failures.append("layer select did not change the active layer")
    finally:
        if artwork is not None:
            channel.execute("gallery.close")
            time.sleep(3)
            channel.execute("gallery.delete", {"ids": [artwork]})
        channel.close()
    if failures:
        print("FAILED:\n" + "\n".join(failures))
        sys.exit(1)
    print("Private layers and layer select passed.")


if __name__ == "__main__":
    main()
