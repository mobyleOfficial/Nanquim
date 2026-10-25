#!/usr/bin/env python3
"""Checks the gallery in the system file picker.
Makes three painted artworks (two in a stack), runs GalleryDocumentsProviderTest in the app's
process with `am instrument` (listing, the streamed zip, thumbnails, search) and opens the
system picker on the Nanquim root: the stack and the loose artwork must be listed, the
artworks with their thumbnails (screenshot in --out). The artworks are deleted at the end.

Needs the debug app and its test APK installed (`./gradlew :app:installProdDebug
:app:installProdDebugAndroidTest`; never `:app:connectedProdDebugAndroidTest`, which uninstalls the app
and its gallery at the end).

    python tools/device/check_documents_provider.py --device <serial> [--out <folder>]
"""

import argparse
import os
import random
import re
import sys
import time

from kill_test import adb, launch, stroke_samples, wait_for_artwork

SIZE = 1000
AUTHORITY = "com.mobyle.nanquim.documents"
TEST = "com.mobyle.nanquim.gallery.GalleryDocumentsProviderTest"
RUNNER = "com.mobyle.nanquim.test/androidx.test.runner.AndroidJUnitRunner"


def screen(device):
    adb(device, "shell", "uiautomator", "dump", "/sdcard/nanquim-ui.xml")
    return adb_bytes(device, "exec-out", "cat", "/sdcard/nanquim-ui.xml").decode("utf-8", errors="replace")


def painted_artwork(channel, name, seed):
    artwork = channel.execute("gallery.newCanvas", {"width": SIZE, "height": SIZE, "name": name})["id"]
    wait_for_artwork(channel, artwork)
    generator = random.Random(seed)
    for _ in range(6):
        channel.execute("tool.paint.strokePath", {"samples": stroke_samples(SIZE, generator)})
    channel.execute("gallery.close")
    time.sleep(3)
    return artwork


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
    parser.add_argument("--out", default=os.path.join(os.environ.get("TEMP", "."), "nanquim-documents"))
    arguments = parser.parse_args()
    device = arguments.device
    os.makedirs(arguments.out, exist_ok=True)
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    artworks = []
    failures = []
    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artworks.append(painted_artwork(channel, "Provider loose", 1))
        artworks.append(painted_artwork(channel, "Provider stacked A", 2))
        artworks.append(painted_artwork(channel, "Provider stacked B", 3))
        channel.execute("gallery.stack", {"ids": artworks[1:], "name": "Provider stack"})
        time.sleep(1)

        report = adb(device, "shell", "am", "instrument", "-w", "-e", "class", TEST, RUNNER, check=False)
        print(report.strip().splitlines()[-1] if report.strip() else "no instrumentation output")
        if "OK (" not in report:
            failures.append("GalleryDocumentsProviderTest:\n" + report)

        adb(device, "shell", "am", "force-stop", "com.google.android.documentsui", check=False)
        adb(device, "shell", "am", "start", "-a", "android.intent.action.OPEN_DOCUMENT",
            "-c", "android.intent.category.OPENABLE", "-t", "'*/*'")
        time.sleep(5)
        bounds = re.search(r'text="Nanquim"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', screen(device))
        if bounds is None:
            failures.append("the picker does not list the Nanquim root")
        else:
            left, top, right, bottom = map(int, bounds.groups())
            adb(device, "shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
            time.sleep(4)
        shown = screen(device)
        for name in ("Provider loose.nanquim", "Provider stack"):
            if f'text="{name}"' not in shown:
                failures.append(f"the picker does not show '{name}'")
        picture = os.path.join(arguments.out, "picker.png")
        with open(picture, "wb") as file:
            file.write(adb_bytes(device, "exec-out", "screencap", "-p"))
        print(f"picker screenshot: {picture}")
        adb(device, "shell", "input", "keyevent", "KEYCODE_BACK")
        adb(device, "shell", "input", "keyevent", "KEYCODE_BACK")
    finally:
        try:
            if artworks:
                delete_artworks(launch(device), artworks)
        except Exception as error:  # noqa: BLE001 — cleanup is best effort
            print(f"cleanup: {error}")
    if failures:
        print("FAILED:\n" + "\n".join(failures))
        sys.exit(1)
    print("documents provider ok")


def adb_bytes(device, *arguments):
    import subprocess

    executable = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    if not os.path.exists(executable):
        executable = "adb"
    command = [executable] + (["-s", device] if device else []) + list(arguments)
    return subprocess.run(command, capture_output=True, check=True).stdout


if __name__ == "__main__":
    main()
