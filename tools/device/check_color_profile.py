import argparse
import os
import time

from kill_test import PACKAGE, adb, launch, wait_for_artwork


def pick(channel, x, y):
    color = channel.execute("color.eyedropper", {"x": x, "y": y})
    return tuple(round(color[name] * 255) for name in ("red", "green", "blue"))


def close_enough(a, b, tolerance=2):
    return all(abs(x - y) <= tolerance for x, y in zip(a, b))


def document_profile(channel):
    return channel.execute("colorProfile.list")["documentProfile"]


def undo_count(channel):
    return channel.call("getState")["history"]["undo"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    arguments = parser.parse_args()
    size = 1024
    adb(arguments.device, "shell", "am", "force-stop", PACKAGE)
    channel = launch(arguments.device)
    channel.execute("gallery.close")
    time.sleep(3)
    artwork_id = channel.execute("gallery.newCanvas", {"width": size, "height": size, "name": "Color profile"})["id"]
    wait_for_artwork(channel, artwork_id)
    profiles = {entry["id"]: entry for entry in channel.execute("colorProfile.list")["profiles"]}
    channel.execute("tool.select", {"tool": "brush"})
    channel.execute("brush.setSize", {"sizePx": 120})
    channel.execute("color.setPrimary", {"hex": "FF0000"})
    channel.execute("tool.paint.strokePath", {"samples": [[size * 0.2 + step * size * 0.015, size / 2, 1.0, step * 8] for step in range(41)]})
    wait_for_artwork(channel, artwork_id)
    point = (size / 2, size / 2)
    results = {"painted": (pick(channel, *point), document_profile(channel))}

    def step(name, command, arguments):
        start = time.time()
        channel.execute(command, arguments)
        seconds = time.time() - start
        wait_for_artwork(channel, artwork_id)
        results[name] = (pick(channel, *point), document_profile(channel), undo_count(channel), seconds)
        print(name, results[name])

    step("to P3", "canvas.convertProfile", {"profileId": "display-p3"})
    channel.execute("gallery.close")
    time.sleep(3)
    channel.execute("gallery.open", {"id": artwork_id})
    wait_for_artwork(channel, artwork_id)
    time.sleep(0.5)
    results["reopened"] = (pick(channel, *point), document_profile(channel))
    print("reopened", results["reopened"])
    step("assigned sRGB", "canvas.assignProfile", {"profileId": "srgb"})
    step("to 16 bits", "canvas.colorDepth", {"bitsPerChannel": 16})
    step("to Adobe RGB", "canvas.convertProfile", {"profileId": "adobe-rgb"})
    step("back to sRGB", "canvas.convertProfile", {"profileId": "srgb"})
    channel.close()

    p3_red = (234, 51, 35)
    checks = {
        "bundled profiles listed": all(key in profiles for key in ("srgb", "display-p3", "adobe-rgb", "rec2020", "cgats001")),
        "the CMYK profile is not a document profile": not profiles["cgats001"]["documentProfile"],
        "painted red in sRGB": close_enough(results["painted"][0], (255, 0, 0)) and results["painted"][1] == "sRGB IEC61966-2.1",
        "converted to Display P3": close_enough(results["to P3"][0], p3_red) and results["to P3"][1] == "Display P3",
        "conversion clears the history": results["to P3"][2] == 0 and results["to Adobe RGB"][2] == 0,
        "reopen keeps profile and values": results["reopened"] == (results["to P3"][0], "Display P3"),
        "assign keeps the values": results["assigned sRGB"][0] == results["to P3"][0] and results["assigned sRGB"][1] == "sRGB IEC61966-2.1",
        "16-bit conversion to Adobe RGB": results["to Adobe RGB"][1].startswith("Adobe RGB")
        and results["to Adobe RGB"][0][0] < p3_red[0] - 5,
        "16-bit conversion back to sRGB": close_enough(results["back to sRGB"][0], p3_red, 2),
    }
    for name, passed in checks.items():
        print(("PASS " if passed else "FAIL ") + name)
    print("RESULT", "ok" if all(checks.values()) else "failed")


if __name__ == "__main__":
    main()
