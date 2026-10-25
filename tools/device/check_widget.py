#!/usr/bin/env python3
"""Checks the "Recent artwork" widget. Makes a
painted artwork, lets the app bind widgets (`appwidget grantbind`) and runs RecentArtworkWidgetTest
with `am instrument`: a widget in the test's own host shows the artwork modified last, follows a
gallery change within the 30 s limit and opens the artwork when tapped. The artwork is deleted at
the end.

Needs the debug app and its test APK installed (`./gradlew :app:installProdDebug
:app:installProdDebugAndroidTest`; never `:app:connectedProdDebugAndroidTest`, which uninstalls the app
and its gallery at the end).

    python tools/device/check_widget.py --device <serial>
"""

import argparse
import os
import random
import sys
import time

from kill_test import adb, launch, stroke_samples, wait_for_artwork

SIZE = 1000
TEST = "com.mobyle.nanquim.widget.RecentArtworkWidgetTest"
RUNNER = "com.mobyle.nanquim.test/androidx.test.runner.AndroidJUnitRunner"


def delete_artworks(channel, ids):
    for _ in range(20):
        channel.execute("gallery.close")
        time.sleep(1)
        if channel.call("getState")["artwork"] is None:
            break
    channel.execute("gallery.delete", {"ids": ids})


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    artwork = None
    passed = False
    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": SIZE, "height": SIZE, "name": "Widget check"})["id"]
        wait_for_artwork(channel, artwork)
        generator = random.Random(4)
        for _ in range(6):
            channel.execute("tool.paint.strokePath", {"samples": stroke_samples(SIZE, generator)})
        channel.execute("gallery.close")
        time.sleep(3)
        adb(device, "shell", "appwidget", "grantbind", "--package", "com.mobyle.nanquim", "--user", "0")
        report = adb(device, "shell", "am", "instrument", "-w", "-e", "class", TEST, RUNNER, check=False)
        print(report.strip())
        passed = "OK (1 test)" in report
        picture = os.path.join(os.environ.get("TEMP", "."), "nanquim-widget.png")
        adb(device, "pull", "/sdcard/Android/data/com.mobyle.nanquim/files/widget.png", picture, check=False)
        print(f"widget picture: {picture}")
    finally:
        try:
            if artwork is not None:
                delete_artworks(launch(device), [artwork])
        except Exception as error:  # noqa: BLE001 — cleanup is best effort
            print(f"cleanup: {error}")
    if not passed:
        print("FAILED")
        sys.exit(1)
    print("widget ok")


if __name__ == "__main__":
    main()
