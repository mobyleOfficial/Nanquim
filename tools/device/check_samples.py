#!/usr/bin/env python3
"""Checks the sample artworks.

1. A real first launch: the samples preference is removed (`run-as`, debug build) and the app
   started without the scripts' token: the three samples arrive in the gallery once; a second
   launch adds nothing.
2. Each sample opens: night-hills has its Screen and Soft Light layers, ink-flower its Multiply
   layer, bouncing-ball an animation of 13 frames over a background frame.
3. `gallery.samples.install` adds them again. Everything added is deleted at the end; the
   gallery is screenshot first (--out).

    python tools/device/check_samples.py --device <serial> [--out <folder>]
"""

import argparse
import os
import subprocess
import sys
import time

from kill_test import ACTIVITY, adb, launch, wait_for_artwork

SAMPLES = 3


def artwork_ids(device):
    return set(adb(device, "shell", "run-as", "com.mobyle.nanquim", "ls", "files/artworks").split())


def delete_artworks(channel, ids):
    for _ in range(20):
        channel.execute("gallery.close")
        time.sleep(1)
        if channel.call("getState")["artwork"] is None:
            break
    channel.execute("gallery.delete", {"ids": sorted(ids)})


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--out", default=os.path.join(os.environ.get("TEMP", "."), "nanquim-samples"))
    arguments = parser.parse_args()
    device = arguments.device
    os.makedirs(arguments.out, exist_ok=True)
    failures = []
    added = set()
    channel = None
    before = artwork_ids(device)
    try:
        adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
        adb(device, "shell", "run-as", "com.mobyle.nanquim", "rm", "-f", "shared_prefs/samples.xml")
        adb(device, "shell", "am", "start", "-W", "-n", ACTIVITY)
        deadline = time.time() + 30
        while time.time() < deadline and len(artwork_ids(device) - before) < SAMPLES:
            time.sleep(1)
        first = artwork_ids(device) - before
        added |= first
        if len(first) != SAMPLES:
            failures.append(f"the first launch added {len(first)} samples")
        time.sleep(2)
        adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
        adb(device, "shell", "am", "start", "-W", "-n", ACTIVITY)
        time.sleep(6)
        again = artwork_ids(device) - before - first
        added |= again
        if again:
            failures.append(f"a second launch added {len(again)} more")

        adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
        channel = launch(device)
        delete_artworks(channel, [])
        with open(os.path.join(arguments.out, "gallery.png"), "wb") as file:
            file.write(subprocess.run(
                [os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"), "-s", device,
                 "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout)
        found = {}
        for artwork in sorted(first):
            channel.execute("gallery.open", {"id": artwork})
            wait_for_artwork(channel, artwork)
            state = channel.call("getState")
            document = state["document"]
            name = state["artwork"]["name"]
            blends = {layer["blend"] for layer in document["layers"]}
            found[name] = (blends, document["animation"]["enabled"], len(document["layers"]))
            print(f"{name}: {len(document['layers'])} layers, blends {sorted(blends)}, animation {document['animation']['enabled']}")
            channel.execute("gallery.close")
            time.sleep(2)
        names = " ".join(found)
        for word in ("Night Hills", "Ink Flower", "Bouncing Ball"):
            portuguese = {"Night Hills": "Colinas", "Ink Flower": "Flor", "Bouncing Ball": "Bola"}[word]
            if word not in names and portuguese not in names:
                failures.append(f"no sample named like '{word}' / '{portuguese}': {list(found)}")
        for name, (blends, animated, layers) in found.items():
            if ("Hills" in name or "Colinas" in name) and not {"screen", "softLight"} <= blends:
                failures.append(f"{name}: blends {blends}")
            if ("Flower" in name or "Flor" in name) and "multiply" not in blends:
                failures.append(f"{name}: blends {blends}")
            if ("Ball" in name or "Bola" in name) and not (animated and layers == 13):
                failures.append(f"{name}: animation {animated}, {layers} layers")

        ids = channel.execute("gallery.samples.install")["ids"]
        added |= set(ids)
        if len(ids) != SAMPLES:
            failures.append(f"gallery.samples.install added {len(ids)}")
    finally:
        try:
            if added:
                delete_artworks(channel or launch(device), added)
        except Exception as error:  # noqa: BLE001 — cleanup is best effort
            print(f"cleanup: {error}")
    left = artwork_ids(device) - before
    if left:
        failures.append(f"{len(left)} artworks left after the cleanup")
    print(f"screenshots: {arguments.out}")
    if failures:
        print("FAILED:\n" + "\n".join(failures))
        sys.exit(1)
    print("samples ok")


if __name__ == "__main__":
    main()
