import argparse
import io
import os
import time

import tifffile
from PIL import Image, ImageCms

from check_display_color import screen_center
from check_profile_export import export, tiff_tags
from kill_test import PACKAGE, adb, launch, wait_for_artwork

CMYK_ID = "nanquim-cmyk"
CMYK_NAME = "Nanquim CMYK (CGATS TR 001)"
PROFILES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "assets-src", "profiles")


def separation(rgb):
    working = ImageCms.getOpenProfile(os.path.join(PROFILES, "AdobeCompat-v4.icc"))
    printing = ImageCms.getOpenProfile(os.path.join(PROFILES, "NanquimCMYK-v4.icc"))
    transform = ImageCms.buildTransform(
        working, printing, "RGB", "CMYK", ImageCms.Intent.RELATIVE_COLORIMETRIC, ImageCms.Flags.BLACKPOINTCOMPENSATION
    )
    return ImageCms.applyTransform(Image.new("RGB", (1, 1), rgb), transform).getpixel((0, 0))


def profile_description(name):
    return ImageCms.getProfileDescription(ImageCms.getOpenProfile(os.path.join(PROFILES, name))).strip()


def wait_display(channel, converted=True, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        display = channel.execute("colorProfile.list")["display"]
        if display["converted"] == converted:
            return display
        time.sleep(0.3)
    return channel.execute("colorProfile.list")["display"]


def wait_cmyk(channel, expected, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        listed = channel.execute("colorProfile.list")
        if listed["cmykProfile"] == expected:
            return listed
        time.sleep(0.3)
    return channel.execute("colorProfile.list")


def set_primary(channel, arguments, timeout=10):
    deadline = time.time() + timeout
    while True:
        result = channel.execute("color.setPrimary", arguments)
        if "cmyk" in result or time.time() > deadline:
            return result
        time.sleep(0.3)


def close(a, b, tolerance):
    return all(abs(x - y) <= tolerance for x, y in zip(a, b))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "am", "force-stop", PACKAGE)
    channel = launch(device)
    channel.execute("gallery.close")
    time.sleep(3)
    artwork = channel.execute("gallery.newCanvas", {"width": 1024, "height": 1024, "name": "CMYK", "profileId": CMYK_ID})["id"]
    wait_for_artwork(channel, artwork)
    listed = wait_cmyk(channel, CMYK_NAME)
    display = wait_display(channel)

    green = set_primary(channel, {"red": 0.0, "green": 1.0, "blue": 0.0})
    inks = set_primary(channel, {"cyan": 0.2, "magenta": 0.5, "yellow": 0.6, "black": 0.0})
    channel.execute("layer.fill")
    wait_for_artwork(channel, artwork)
    fill_rgb = tuple(round(value * 255) for value in inks["rgba"][:3])
    time.sleep(1)
    proofed_screen = screen_center(device)

    channel.execute("canvas.setCmykProfile", {})
    wait_cmyk(channel, None)
    channel.execute("color.setPrimary", {"red": 0.0, "green": 1.0, "blue": 0.0})
    plain_green = channel.execute("color.setPrimary", {"red": 0.0, "green": 1.0, "blue": 0.0})
    channel.execute("layer.fill")
    wait_for_artwork(channel, artwork)
    time.sleep(1)
    rgb_screen = screen_center(device)
    channel.execute("canvas.setCmykProfile", {"profileId": CMYK_ID})
    wait_cmyk(channel, CMYK_NAME)
    wait_for_artwork(channel, artwork)
    time.sleep(1.5)
    cmyk_screen = screen_center(device)

    channel.execute("color.setPrimary", {"cyan": 0.2, "magenta": 0.5, "yellow": 0.6, "black": 0.0})
    channel.execute("layer.fill")
    wait_for_artwork(channel, artwork)
    tiff = export(device, channel, "cmyk_canvas.tiff", "tiff")
    psd = export(device, channel, "cmyk_canvas.psd", "psd")
    rgb_tiff = export(device, channel, "cmyk_canvas_rgb.tiff", "tiff", "rgb")
    png = export(device, channel, "cmyk_canvas.png", "png")

    channel.execute("gallery.close")
    time.sleep(3)
    channel.execute("gallery.open", {"id": artwork})
    wait_for_artwork(channel, artwork)
    reopened = wait_cmyk(channel, CMYK_NAME)

    channel.execute("gallery.close")
    time.sleep(3)
    imported = channel.execute("gallery.import", {"uri": f"content://{PACKAGE}.files/exports/cmyk_canvas.tiff"})["id"]
    channel.execute("gallery.open", {"id": imported})
    wait_for_artwork(channel, imported)
    imported_listed = wait_cmyk(channel, CMYK_NAME)
    time.sleep(1)
    picked = channel.execute("color.eyedropper", {"x": 512, "y": 512})
    picked_rgb = tuple(round(picked[name] * 255) for name in ("red", "green", "blue"))
    channel.execute("gallery.close")
    time.sleep(2)
    channel.execute("gallery.delete", {"ids": [artwork, imported]})
    channel.close()

    with tifffile.TiffFile(io.BytesIO(tiff)) as tiff_file:
        pixels = tiff_file.pages[0].asarray()
    tiff_inks = tuple(int(value) for value in pixels[pixels.shape[0] // 2, pixels.shape[1] // 2])
    png_image = Image.open(io.BytesIO(png))
    png_profile = ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(png_image.info["icc_profile"]))).strip()
    results = {
        "listed": (listed["documentProfile"], listed["cmykProfile"]),
        "display": display,
        "green asked": green["rgba"][:3],
        "green on RGB canvas": plain_green["rgba"][:3],
        "inks asked 20/50/60/0, got": inks.get("cmyk"),
        "screen center proofed / RGB green / CMYK green": (proofed_screen, rgb_screen, cmyk_screen),
        "TIFF photometric, samples, center inks, expected": (tiff_tags(tiff).get(262), tiff_tags(tiff).get(277), tiff_inks, separation(fill_rgb)),
        "PSD mode": int.from_bytes(psd[24:26], "big"),
        "RGB TIFF photometric": tiff_tags(rgb_tiff).get(262),
        "PNG profile": png_profile,
        "reopened": reopened["cmykProfile"],
        "imported": (imported_listed["documentProfile"], imported_listed["cmykProfile"], picked_rgb, fill_rgb),
    }
    for name, value in results.items():
        print(name, value)
    checks = {
        "new CMYK canvas: Adobe RGB working profile and the CMYK profile": listed["documentProfile"].startswith("Adobe RGB")
        and listed["cmykProfile"] == CMYK_NAME,
        "display converted": display["converted"] is True and "error" not in display,
        "RGB green is brought into the CMYK gamut": green["rgba"][0] + green["rgba"][2] > 0.1 and green["rgba"][1] < 1.0,
        "the same green stays as asked on an RGB canvas": close(plain_green["rgba"][:3], [0.0, 1.0, 0.0], 0.001),
        "inks come back as asked": inks.get("cmyk") is not None and close(inks["cmyk"], [0.2, 0.5, 0.6, 0.0], 0.001),
        "the proof dulls the screen green": sum(abs(a - b) for a, b in zip(rgb_screen, cmyk_screen)) > 30,
        "TIFF is separated CMYK with the profile's inks for the fill": tiff_tags(tiff).get(262) == 5
        and close(tiff_inks[:4], separation(fill_rgb), 6),
        "PSD is in CMYK mode": int.from_bytes(psd[24:26], "big") == 4,
        "RGB export still possible": tiff_tags(rgb_tiff).get(262) == 2,
        "PNG keeps the working RGB profile": png_profile == profile_description("AdobeCompat-v4.icc"),
        "the CMYK profile survives closing and reopening": reopened["cmykProfile"] == CMYK_NAME,
        "the CMYK TIFF imports as a CMYK canvas with the same color": imported_listed["cmykProfile"] == CMYK_NAME
        and imported_listed["documentProfile"].startswith("Adobe RGB")
        and close(picked_rgb, fill_rgb, 6),
    }
    for name, passed in checks.items():
        print(("PASS " if passed else "FAIL ") + name)
    print("RESULT", "ok" if all(checks.values()) else "failed")


if __name__ == "__main__":
    main()
