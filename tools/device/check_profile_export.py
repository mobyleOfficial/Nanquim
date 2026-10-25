import argparse
import io
import os
import struct
import subprocess
import tempfile
import time
import zlib

from PIL import Image, ImageCms

from kill_test import ADB, PACKAGE, adb, launch, wait_for_artwork

P3_RED = (234, 51, 35)


def pull(device, name):
    return subprocess.run([ADB, "-s", device, "exec-out", "run-as", PACKAGE, "cat", f"cache/exports/{name}"],
                          check=True, capture_output=True).stdout


def export(device, channel, name, format_id, cmyk=None):
    adb(device, "shell", "run-as", PACKAGE, "sh", "-c", f"'mkdir -p cache/exports && : > cache/exports/{name}'")
    arguments = {"uri": f"content://{PACKAGE}.files/exports/{name}", "format": format_id}
    if cmyk:
        arguments["cmykProfile"] = cmyk
    channel.execute("export.image", arguments)
    return pull(device, name)


def push_to_exports(device, local_path, name):
    adb(device, "push", local_path, f"/data/local/tmp/{name}")
    adb(device, "shell", "run-as", PACKAGE, "sh", "-c", f"'mkdir -p cache/exports && cp /data/local/tmp/{name} cache/exports/{name}'")
    return f"content://{PACKAGE}.files/exports/{name}"


def imported_profile(channel, uri):
    artwork = channel.execute("gallery.import", {"uri": uri})["id"]
    channel.execute("gallery.open", {"id": artwork})
    wait_for_artwork(channel, artwork)
    deadline = time.time() + 10
    while True:
        color = channel.execute("color.eyedropper", {"x": 256, "y": 256})
        values = tuple(round(color[name] * 255) for name in ("red", "green", "blue"))
        if close_enough(values, P3_RED) or time.time() > deadline:
            break
        time.sleep(0.5)
    return channel.execute("colorProfile.list")["documentProfile"], values


def description(icc):
    if not icc:
        return None
    try:
        return ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(icc))).strip()
    except Exception as error:
        return f"unreadable: {error}"


def png_info(data):
    offset, depth, icc = 8, None, None
    while offset + 8 <= len(data):
        length, kind = struct.unpack(">I4s", data[offset:offset + 8])
        body = data[offset + 8:offset + 8 + length]
        if kind == b"IHDR":
            depth = body[8]
        elif kind == b"iCCP":
            name_end = body.index(0)
            icc = zlib.decompress(body[name_end + 2:])
        offset += 12 + length
    return depth, icc


def tiff_tags(data):
    offset = struct.unpack("<I", data[4:8])[0]
    count = struct.unpack("<H", data[offset:offset + 2])[0]
    tags = {}
    for index in range(count):
        tag, kind, _, value = struct.unpack("<HHII", data[offset + 2 + index * 12:offset + 14 + index * 12])
        tags[tag] = value & 0xFFFF if kind == 3 else value
    return tags


def center_pixel(data):
    image = Image.open(io.BytesIO(data))
    return image.convert("RGB").getpixel((image.width // 2, image.height // 2))


def close_enough(a, b, tolerance=3):
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
    artwork = channel.execute("gallery.newCanvas", {"width": 512, "height": 512, "name": "Profile export"})["id"]
    wait_for_artwork(channel, artwork)
    channel.execute("color.setPrimary", {"hex": "FF0000"})
    channel.execute("layer.fill")
    wait_for_artwork(channel, artwork)
    channel.execute("canvas.convertProfile", {"profileId": "display-p3"})
    wait_for_artwork(channel, artwork)
    files = {format_id: export(device, channel, f"profile.{format_id}", format_id)
             for format_id in ("png", "jpeg", "webp", "tiff", "psd", "pdf")}
    channel.execute("canvas.colorDepth", {"bitsPerChannel": 16})
    wait_for_artwork(channel, artwork)
    deep = {format_id: export(device, channel, f"deep.{format_id}", format_id) for format_id in ("png", "tiff", "psd")}
    synthetic = os.path.join(tempfile.gettempdir(), "nanquim_synthetic_cmyk.icc")
    cmyk_id = channel.execute("colorProfile.import", {"uri": push_to_exports(device, synthetic, "synthetic_cmyk.icc")})["id"]
    cmyk = {format_id: export(device, channel, f"cmyk.{format_id}", format_id, cmyk_id) for format_id in ("tiff", "psd", "pdf")}
    channel.execute("gallery.close")
    time.sleep(3)
    imported = {
        "png": imported_profile(channel, f"content://{PACKAGE}.files/exports/profile.png"),
        "tiff": imported_profile(channel, f"content://{PACKAGE}.files/exports/profile.tiff"),
    }
    channel.close()

    folder = tempfile.mkdtemp(prefix="nanquim_profile_export_")
    for prefix, group in (("", files), ("deep_", deep), ("cmyk_", cmyk)):
        for format_id, data in group.items():
            with open(os.path.join(folder, f"{prefix}profile.{format_id}"), "wb") as file:
                file.write(data)
    print("files in", folder)

    png_depth, png_icc = png_info(files["png"])
    deep_png_depth, deep_png_icc = png_info(deep["png"])
    jpeg = Image.open(io.BytesIO(files["jpeg"]))
    tiff = Image.open(io.BytesIO(files["tiff"]))
    deep_tiff = Image.open(io.BytesIO(deep["tiff"]))
    webp = Image.open(io.BytesIO(files["webp"]))
    psd_icc_found = png_icc is not None and png_icc in files["psd"]
    results = {
        "png": (png_depth, description(png_icc), center_pixel(files["png"])),
        "jpeg": (description(jpeg.info.get("icc_profile")), center_pixel(files["jpeg"])),
        "webp": (description(webp.info.get("icc_profile")), center_pixel(files["webp"])),
        "tiff": (description(tiff.info.get("icc_profile")), tiff.tag_v2.get(258), center_pixel(files["tiff"])),
        "psd": (struct.unpack(">H", files["psd"][22:24])[0], psd_icc_found),
        "pdf": (b"/ICCBased" in files["pdf"], png_icc is not None and png_icc in files["pdf"]),
        "deep png": (deep_png_depth, description(deep_png_icc)),
        "deep tiff": (deep_tiff.tag_v2.get(258), description(deep_tiff.info.get("icc_profile"))),
        "deep psd": (struct.unpack(">H", deep["psd"][22:24])[0], png_icc is not None and png_icc in deep["psd"]),
        "cmyk tiff": (tiff_tags(cmyk["tiff"]).get(262), tiff_tags(cmyk["tiff"]).get(277)),
        "cmyk psd": (struct.unpack(">H", cmyk["psd"][12:14])[0], struct.unpack(">H", cmyk["psd"][24:26])[0]),
        "cmyk pdf": b"/N 4 /Alternate /DeviceCMYK" in cmyk["pdf"],
        "imported": imported,
    }
    for name, value in results.items():
        print(name, value)
    p3 = lambda text: text is not None and "P3" in text  # noqa: E731
    checks = {
        "PNG: 8-bit, Display P3 profile, document values": results["png"][0] == 8 and p3(results["png"][1])
        and close_enough(results["png"][2], P3_RED),
        "JPEG: Display P3 profile, document values": p3(results["jpeg"][0]) and close_enough(results["jpeg"][1], P3_RED, 6),
        "WebP: converted to sRGB red (no profile or sRGB)": (results["webp"][0] is None or "sRGB" in results["webp"][0])
        and close_enough(results["webp"][1], (255, 0, 0), 6),
        "TIFF: Display P3 profile, document values": p3(results["tiff"][0]) and close_enough(results["tiff"][2], P3_RED),
        "PSD: 8-bit with the profile": results["psd"] == (8, True),
        "PDF: ICCBased with the profile": results["pdf"] == (True, True),
        "16-bit PNG": results["deep png"][0] == 16 and p3(results["deep png"][1]),
        "16-bit TIFF": tuple(results["deep tiff"][0] or ())[:1] == (16,) and p3(results["deep tiff"][1]),
        "16-bit PSD": results["deep psd"] == (16, True),
        "CMYK TIFF (separated, 5 samples)": results["cmyk tiff"] == (5, 5),
        "CMYK PSD (5 channels, CMYK mode)": results["cmyk psd"] == (5, 4),
        "CMYK PDF (ICCBased, 4 components)": results["cmyk pdf"],
        "imported PNG and TIFF take Display P3 (the library's name) and keep the values": all(
            name == "Display P3" and close_enough(values, P3_RED) for name, values in imported.values()
        ),
    }
    for name, passed in checks.items():
        print(("PASS " if passed else "FAIL ") + name)
    print("RESULT", "ok" if all(checks.values()) else "failed")


if __name__ == "__main__":
    main()
