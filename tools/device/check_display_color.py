import argparse
import io
import os
import re
import subprocess
import time

from PIL import Image

from kill_test import ADB, PACKAGE, adb, launch, wait_for_artwork


def screen_center(device):
    png = subprocess.run([ADB, "-s", device, "exec-out", "screencap", "-p"], check=True, capture_output=True).stdout
    image = Image.open(io.BytesIO(png)).convert("RGB")
    width, height = image.size
    pixels = [image.getpixel((width // 2 + dx, height // 2 + dy)) for dx in range(-4, 5) for dy in range(-4, 5)]
    return tuple(round(sum(pixel[channel] for pixel in pixels) / len(pixels)) for channel in range(3))


def pick(channel, x, y):
    color = channel.execute("color.eyedropper", {"x": x, "y": y})
    return tuple(round(color[name] * 255) for name in ("red", "green", "blue"))


def close_enough(a, b, tolerance=3):
    return all(abs(x - y) <= tolerance for x, y in zip(a, b))


def display(channel):
    return channel.execute("colorProfile.list")["display"]


def surface_dataspaces(device):
    lines = adb(device, "shell", "dumpsys", "SurfaceFlinger").splitlines()
    found = set()
    for index, line in enumerate(lines):
        if "SurfaceView" in line and PACKAGE in line:
            for following in lines[index + 1 : index + 30]:
                if "Layer" in following and PACKAGE in following:
                    break
                found.update(re.findall(r"dataspace=(\w+)", following))
    return sorted(found)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    arguments = parser.parse_args()
    device = arguments.device
    size = 1024
    adb(device, "shell", "am", "force-stop", PACKAGE)
    wide = "Device supports wide color: 1" in adb(device, "shell", "dumpsys", "SurfaceFlinger")
    channel = launch(device)
    channel.execute("gallery.close")
    time.sleep(3)
    artwork_id = channel.execute("gallery.newCanvas", {"width": size, "height": size, "name": "Display color"})["id"]
    wait_for_artwork(channel, artwork_id)
    channel.execute("color.setPrimary", {"hex": "FF0000"})
    channel.execute("layer.fill")
    channel.execute("view.fit")
    wait_for_artwork(channel, artwork_id)
    point = (size / 2, size / 2)
    results = {}

    def observe(name):
        time.sleep(1.0)
        results[name] = {
            "values": pick(channel, *point),
            "screen": screen_center(device),
            "display": display(channel),
            "dataspaces": surface_dataspaces(device),
        }
        print(name, results[name])

    def step(name, command, arguments):
        channel.execute(command, arguments)
        wait_for_artwork(channel, artwork_id)
        observe(name)

    observe("sRGB red")
    step("converted to P3", "canvas.convertProfile", {"profileId": "display-p3"})
    step("assigned sRGB", "canvas.assignProfile", {"profileId": "srgb"})
    step("assigned P3", "canvas.assignProfile", {"profileId": "display-p3"})
    step("converted to Rec. 2020", "canvas.convertProfile", {"profileId": "rec2020"})
    channel.execute("gallery.close")
    time.sleep(3)
    results["gallery"] = {"display": display(channel)}
    print("gallery", results["gallery"])
    channel.close()

    red = results["sRGB red"]["screen"]
    p3_red = (234, 51, 35)
    space = "display-p3" if wide else "srgb"
    checks = {
        "sRGB document needs no conversion": results["sRGB red"]["display"] == {"space": "srgb", "converted": False},
        "conversion changes the values": close_enough(results["converted to P3"]["values"], p3_red),
        "converted document still shows red": close_enough(results["converted to P3"]["screen"], red)
        and results["converted to P3"]["display"] == {"space": space, "converted": True},
        "assigned sRGB shows other values": not close_enough(results["assigned sRGB"]["screen"], red, 10)
        and results["assigned sRGB"]["display"] == {"space": "srgb", "converted": False},
        "assigned P3 shows red again": close_enough(results["assigned P3"]["screen"], red),
        "Rec. 2020 shows red": close_enough(results["converted to Rec. 2020"]["screen"], red)
        and results["converted to Rec. 2020"]["display"]["converted"],
        "Display P3 surface only with a profile on a wide display": all(
            ("DISPLAY_P3" in results[name]["dataspaces"]) == (wide and results[name]["display"]["space"] == "display-p3")
            for name in ("sRGB red", "converted to P3", "assigned sRGB", "assigned P3")
        ),
        "the gallery returns to sRGB": results["gallery"]["display"]["space"] == "srgb",
    }
    for name, passed in checks.items():
        print(("PASS " if passed else "FAIL ") + name)
    print("wide-gamut display:", wide)
    print("RESULT", "ok" if all(checks.values()) else "failed")


if __name__ == "__main__":
    main()
