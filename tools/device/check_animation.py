#!/usr/bin/env python3
"""Checks animation on the device: a 1600 × 1200
artwork with five frames (a square moving right on each), animation on; the document composite
(the control channel's screenshot) must show the current frame, its onion neighbours tinted and
the far frames hidden; holds, reordering and the background frame change what is shown; undo
turns animation off again; settings survive closing and reopening. The artwork is deleted at the
end. Writes the screenshots to --out for review.

    python tools/device/check_animation.py --device <serial> [--out <folder>]
"""

import argparse
import base64
import io
import os
import sys
import time

from PIL import Image

from kill_test import adb, launch, wait_for_artwork

WIDTH, HEIGHT = 1600, 1200
FRAMES = 5


def square(index):
    return {"x": 100 + index * 280, "y": 500, "width": 200, "height": 200}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--out", default="animation-check")
    arguments = parser.parse_args()
    device = arguments.device
    os.makedirs(arguments.out, exist_ok=True)
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    artwork = None
    failures = []

    def shot(name):
        time.sleep(1.0)
        image = Image.open(io.BytesIO(base64.b64decode(channel.call("screenshot")["png"]))).convert("RGB")
        image.save(os.path.join(arguments.out, f"{name}.png"))
        scale = image.width / WIDTH
        return lambda x, y: image.getpixel((int(x * scale), int(y * scale)))

    def center(index):
        rect = square(index)
        return rect["x"] + rect["width"] // 2, rect["y"] + rect["height"] // 2

    def expect(sample, index, test, what):
        color = sample(*center(index))
        if not test(color):
            failures.append(f"{what}: frame {index + 1} shows {color}")

    def dark(color):
        return max(color) < 60

    def white(color):
        return min(color) > 235

    def reddish(color):
        return color[0] > color[2] + 20

    def bluish(color):
        return color[2] > color[0] + 20

    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": WIDTH, "height": HEIGHT, "name": "Animation check"})["id"]
        wait_for_artwork(channel, artwork)
        for index in range(FRAMES):
            if index > 0:
                channel.execute("animation.addFrame")
            channel.execute("selection.rect", square(index))
            channel.execute("layer.fill", {"color": [0.05, 0.05, 0.05, 1]})
            channel.execute("selection.clear")
        state = channel.call("getState")
        frames = [layer["id"] for layer in state["document"]["layers"]]
        if len(frames) != FRAMES:
            failures.append(f"expected {FRAMES} frames, got {state['document']['layers']}")
        channel.execute("animation.setEnabled", {"enabled": True})
        channel.execute("animation.goToFrame", {"index": 2})
        sample = shot("onion_frame3")
        expect(sample, 2, dark, "current frame")
        expect(sample, 1, reddish, "previous onion frame")
        expect(sample, 3, bluish, "next onion frame")
        expect(sample, 0, reddish, "second previous onion frame")
        expect(sample, 4, bluish, "second next onion frame")
        channel.execute("animation.setSettings", {"onionFrames": 1})
        sample = shot("onion_one")
        expect(sample, 0, white, "frame outside the onion range")
        expect(sample, 4, white, "frame outside the onion range")
        channel.execute("animation.setSettings", {"onionEnabled": False})
        sample = shot("onion_off")
        expect(sample, 1, white, "onion off")
        expect(sample, 2, dark, "current frame, onion off")
        channel.execute("animation.setSettings", {"firstFrameIsBackground": True})
        sample = shot("background")
        expect(sample, 0, dark, "background frame")
        channel.execute("animation.setHold", {"layerId": frames[2], "holdFrames": 3})
        channel.execute("layer.move", {"layerId": frames[4], "parentId": None, "index": 1})
        state = channel.call("getState")
        order = [layer["id"] for layer in state["document"]["layers"]]
        if order != [frames[0], frames[4], frames[1], frames[2], frames[3]]:
            failures.append(f"reorder: {order}")
        animation = state["document"]["animation"]
        if animation["holdFrames"] != {str(frames[2]): 3}:
            failures.append(f"holds: {animation['holdFrames']}")
        channel.execute("document.save")
        time.sleep(2)
        channel.execute("gallery.close")
        time.sleep(3)
        channel.execute("gallery.open", {"id": artwork})
        wait_for_artwork(channel, artwork)
        state = channel.call("getState")
        reopened = state["document"]["animation"]
        reopenedOrder = [layer["id"] for layer in state["document"]["layers"]]

        def by_position(settings, ids):
            return {**settings, "holdFrames": {ids.index(int(key)): value for key, value in settings["holdFrames"].items()}}

        if by_position(reopened, reopenedOrder) != by_position(animation, order):
            failures.append(f"reopened animation {reopened}, saved {animation}")
        sample = shot("reopened")
        expect(sample, 0, dark, "background frame after reopening")
        channel.execute("animation.setSettings", {"framesPerSecond": 24})
        channel.execute("animation.play")
        time.sleep(1.5)
        playback = channel.call("getState")["playback"]
        if not playback["playing"] or playback["framesShown"] < 20 or playback["frameId"] is None:
            failures.append(f"playback: {playback}")
        channel.execute("animation.pause")
        playback = channel.call("getState")["playback"]
        if playback["playing"] or playback["frameId"] is not None:
            failures.append(f"pause: {playback}")
        channel.execute("animation.setEnabled", {"enabled": False})
        sample = shot("off")
        for index in range(FRAMES):
            expect(sample, index, dark, "animation off")
        channel.execute("edit.undo")
        if not channel.call("getState")["document"]["animation"]["enabled"]:
            failures.append("undo did not turn animation back on")
        print("animation:", reopened)
    finally:
        if artwork is not None:
            channel.execute("gallery.close")
            time.sleep(3)
            channel.execute("gallery.delete", {"ids": [artwork]})
        channel.close()
    if failures:
        print("FAILED:\n" + "\n".join(failures))
        sys.exit(1)
    print("Animation check passed.")


if __name__ == "__main__":
    main()
