import argparse
import base64
import io
import os
import random
import subprocess
import sys
import time

from PIL import Image, ImageChops

from kill_test import ADB, PACKAGE, adb, launch, stroke_samples, wait_for_artwork

SHOTS_PER_VIEW = 3
SIZE = 2048
VIEWS = [
    ("fit", None, 0, False),
    ("zoom037", 0.37, 0, False),
    ("zoom3", 3.0, 0, False),
    ("pixels", 12.0, 0, False),
    ("rotated", 0.8, 30, False),
    ("selection", None, 0, True),
    ("selection_zoom2", 2.0, 15, True),
]


def screenshot(device):
    png = subprocess.run([ADB, "-s", device, "exec-out", "screencap", "-p"], check=True,
                         capture_output=True).stdout
    return Image.open(io.BytesIO(png)).convert("RGB")


def capture(device, out):
    os.makedirs(out, exist_ok=True)
    adb(device, "shell", "am", "force-stop", PACKAGE)
    channel = launch(device)
    channel.execute("gallery.close")
    time.sleep(3)
    artwork_id = channel.execute("gallery.newCanvas", {"width": SIZE, "height": SIZE,
                                                       "name": "Viewport capture"})["id"]
    wait_for_artwork(channel, artwork_id)
    channel.execute("debug.layer.fillRandomTiles", {"layerId": 0, "count": 12, "seed": 7})
    channel.execute("layer.new")
    channel.execute("brush.setSize", {"sizePx": 80})
    generator = random.Random(20261021)
    for hex_color in ("1E5AA8", "D2462D", "2E9E4F", "F2C230"):
        channel.execute("color.setPrimary", {"hex": hex_color})
        for _ in range(3):
            channel.execute("tool.paint.strokePath", {"samples": stroke_samples(SIZE, generator)})
    wait_for_artwork(channel, artwork_id)
    layer = channel.call("getState")["document"]["activeLayerId"]
    channel.execute("layer.setHidden", {"layerId": layer, "hidden": True})
    time.sleep(1.5)
    channel.execute("layer.setHidden", {"layerId": layer, "hidden": False})
    time.sleep(1.5)
    wait_for_artwork(channel, artwork_id)
    shot = channel.call("screenshot")
    with open(os.path.join(out, "composite.png"), "wb") as file:
        file.write(base64.b64decode(shot["png"]))
    for name, zoom, degrees, selection in VIEWS:
        channel.execute("selection.clear")
        if selection:
            channel.execute("selection.ellipse", {"x": 400, "y": 300, "width": 1100, "height": 1300})
            channel.execute("selection.feather", {"radius": 30})
        channel.execute("view.rotateTo", {"degrees": 0})
        time.sleep(1.0)
        channel.execute("view.fit")
        time.sleep(1.0)
        if zoom is not None:
            channel.execute("view.zoomTo", {"zoom": zoom})
            time.sleep(1.0)
        if degrees:
            channel.execute("view.rotateTo", {"degrees": degrees})
        time.sleep(2.0)
        for shot in range(SHOTS_PER_VIEW):
            screenshot(device).save(os.path.join(out, f"{name}_{shot}.png"))
            time.sleep(0.4)
        print(f"captured {name}")
    channel.execute("selection.clear")
    channel.close()


def unstable(images):
    mask = Image.new("L", images[0].size, 0)
    for other in images[1:]:
        difference = ImageChops.difference(images[0], other).convert("L").point(lambda v: 255 if v else 0)
        mask = ImageChops.lighter(mask, difference)
    return mask


def compare(before, after):
    failed = False
    for name, *_ in VIEWS:
        first = [Image.open(os.path.join(before, f"{name}_{i}.png")).convert("RGB") for i in range(SHOTS_PER_VIEW)]
        second = [Image.open(os.path.join(after, f"{name}_{i}.png")).convert("RGB") for i in range(SHOTS_PER_VIEW)]
        mask = ImageChops.lighter(unstable(first), unstable(second))
        difference = ImageChops.difference(first[0], second[0]).convert("L").point(lambda v: 255 if v else 0)
        changed = ImageChops.subtract(difference, mask)
        count = sum(1 for value in changed.get_flattened_data() if value)
        ignored = sum(1 for value in mask.get_flattened_data() if value)
        print(f"{name}: {count} pixels differ ({ignored} unstable pixels ignored)")
        failed = failed or count > 0
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--out")
    parser.add_argument("--compare", nargs=2)
    arguments = parser.parse_args()
    if arguments.compare:
        sys.exit(compare(*arguments.compare))
    capture(arguments.device, arguments.out)


if __name__ == "__main__":
    main()
