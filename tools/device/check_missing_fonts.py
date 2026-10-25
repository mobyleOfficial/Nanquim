#!/usr/bin/env python3
"""Checks the missing-font flow: a text layer
whose style names a font this device does not have is saved, the artwork closed and opened
again; the app must list the font as missing (the "Missing fonts" dialog, getState.missingFonts),
keep the layer a text layer with the original font id, and let it be edited with a substitute.
The artwork is deleted at the end.

    python tools/device/check_missing_fonts.py --device <serial>
"""

import argparse
import os
import sys
import time

from kill_test import adb, launch, wait_for_artwork

MISSING = "NanquimMissingFont-Regular"


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
        artwork = channel.execute("gallery.newCanvas", {"width": 1600, "height": 1200, "name": "Missing font"})["id"]
        wait_for_artwork(channel, artwork)
        channel.execute("tool.select", {"tool": "text"})
        channel.execute("text.place", {"x": 100, "y": 100})
        channel.execute("text.insert", {"text": "Missing font"})
        channel.execute("text.setStyle", {"fontId": MISSING, "sizePt": 72})
        channel.execute("text.done")
        if channel.call("getState")["missingFonts"]:
            failures.append("missing fonts listed before reopening")
        channel.execute("document.save")
        time.sleep(2)
        channel.execute("gallery.close")
        time.sleep(3)
        channel.execute("gallery.open", {"id": artwork})
        wait_for_artwork(channel, artwork)
        time.sleep(2)
        state = channel.call("getState")
        if state["missingFonts"] != [MISSING]:
            failures.append(f"missingFonts is {state['missingFonts']}, expected [{MISSING}]")
        layers = [layer for layer in state["document"]["layers"] if layer["type"] == "text"]
        if len(layers) != 1 or layers[0].get("text") != "Missing font":
            failures.append(f"text layer not kept: {state['document']['layers']}")
        channel.execute("text.edit", {"layerId": layers[0]["id"]})
        channel.execute("text.insert", {"text": "!"})
        time.sleep(1)
        edit = channel.call("getState")["textEdit"]
        if not edit or not edit["laidOut"] or edit["text"] != "Missing font!":
            failures.append(f"editing with a substitute failed: {edit}")
        channel.execute("text.done")
        print("missing fonts:", state["missingFonts"])
    finally:
        if artwork is not None:
            channel.execute("gallery.close")
            time.sleep(3)
            channel.execute("gallery.delete", {"ids": [artwork]})
        channel.close()
    if failures:
        print("FAILED:\n" + "\n".join(failures))
        sys.exit(1)
    print("Missing-font flow passed.")


if __name__ == "__main__":
    main()
