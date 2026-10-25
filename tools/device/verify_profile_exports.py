import io
import os
import re
import subprocess
import sys
import tempfile
import zlib

import numpy as np
import tifffile
from PIL import Image, ImageCms

SRGB_RED = (255, 0, 0)
P3_RED = (234, 51, 35)
PROFILES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "assets-src", "profiles")

WIC_SCRIPT = r"""
Add-Type -AssemblyName PresentationCore
$stream = [System.IO.File]::OpenRead($args[0])
$decoder = [System.Windows.Media.Imaging.BitmapDecoder]::Create($stream,
    [System.Windows.Media.Imaging.BitmapCreateOptions]::PreservePixelFormat -bor
    [System.Windows.Media.Imaging.BitmapCreateOptions]::IgnoreColorProfile,
    [System.Windows.Media.Imaging.BitmapCacheOption]::OnLoad)
$frame = $decoder.Frames[0]
if ($frame.ColorContexts -eq $null -or $frame.ColorContexts.Count -eq 0) { Write-Output "noprofile"; exit }
$srgb = New-Object System.Windows.Media.ColorContext([System.Windows.Media.PixelFormats]::Bgra32)
$converted = New-Object System.Windows.Media.Imaging.ColorConvertedBitmap($frame, $frame.ColorContexts[0], $srgb,
    [System.Windows.Media.PixelFormats]::Bgra32)
$pixel = New-Object byte[] 4
$rect = New-Object System.Windows.Int32Rect([int]($frame.PixelWidth / 2), [int]($frame.PixelHeight / 2), 1, 1)
$converted.CopyPixels($rect, $pixel, 4, 0)
Write-Output ("{0} {1} {2} {3}" -f $frame.Format, $pixel[2], $pixel[1], $pixel[0])
"""


def close_enough(a, b, tolerance):
    return a is not None and all(abs(x - y) <= tolerance for x, y in zip(a, b))


def wic(script, path):
    output = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script,
                             os.path.abspath(path)], capture_output=True, text=True).stdout.strip()
    if not output or output == "noprofile":
        return output or "failed", None
    parts = output.split()
    return parts[0], tuple(int(value) for value in parts[1:4])


def profile_bytes(name):
    with open(os.path.join(PROFILES, name), "rb") as file:
        return file.read()


def cms(icc):
    return ImageCms.ImageCmsProfile(io.BytesIO(icc))


def to_srgb(image, icc):
    converted = ImageCms.profileToProfile(image, cms(icc), ImageCms.createProfile("sRGB"),
                                          renderingIntent=ImageCms.Intent.RELATIVE_COLORIMETRIC, outputMode="RGB",
                                          flags=ImageCms.Flags.BLACKPOINTCOMPENSATION)
    return converted.getpixel((converted.width // 2, converted.height // 2))


def expected_inks(icc):
    transform = ImageCms.buildTransform(cms(profile_bytes("DisplayP3-v4.icc")), cms(icc), "RGB", "CMYK",
                                        renderingIntent=ImageCms.Intent.RELATIVE_COLORIMETRIC,
                                        flags=ImageCms.Flags.BLACKPOINTCOMPENSATION)
    return ImageCms.applyTransform(Image.new("RGB", (1, 1), P3_RED), transform).getpixel((0, 0))


def description(icc):
    return ImageCms.getProfileDescription(cms(icc)).strip()


def pillow(path):
    image = Image.open(path)
    image.load()
    icc = image.info.get("icc_profile")
    if not icc:
        return None, None, image.mode
    if image.mode == "CMYK":
        return description(icc), image.getpixel((image.width // 2, image.height // 2)), image.mode
    if image.mode != "RGB":
        image = image.convert("RGB")
    return description(icc), to_srgb(image, icc), image.mode


def tiff_cmyk(path):
    with tifffile.TiffFile(path) as tiff:
        page = tiff.pages[0]
        pixels = page.asarray()
        icc = bytes(page.tags[34675].value)
    center = pixels[pixels.shape[0] // 2, pixels.shape[1] // 2]
    return description(icc), tuple(int(value) for value in center[:4]), f"photometric {int(page.photometric)}", icc


def pdf_objects(data):
    return {int(match.group(1)): match.start() for match in re.finditer(rb"(\d+) 0 obj", data)}


def pdf_dictionary(data, start):
    return data[start:data.index(b"stream", start)]


def pdf_stream(data, objects, number):
    start = objects[number]
    header = pdf_dictionary(data, start)
    length = re.search(rb"/Length (\d+)( 0 R)?", header)
    length = int(re.search(rb"obj\s+(\d+)", data[objects[int(length.group(1))]:]).group(1)) if length.group(2) \
        else int(length.group(1))
    body_start = start + len(header) + len(b"stream")
    body_start += 2 if data[body_start:body_start + 2] == b"\r\n" else 1
    body = data[body_start:body_start + length]
    return header, zlib.decompress(body) if b"/FlateDecode" in header else body


def unpredict(header, raw, width, height, components):
    predictor = re.search(rb"/Predictor (\d+)", header)
    predictor = int(predictor.group(1)) if predictor else 1
    row = width * components
    if predictor == 1:
        return np.frombuffer(raw, np.uint8)[:row * height].reshape(height, width, components)
    if predictor == 2:
        rows = np.frombuffer(raw, np.uint8)[:row * height].reshape(height, width, components).astype(np.uint32)
        return (np.cumsum(rows, axis=1) & 0xFF).astype(np.uint8)
    out = np.zeros((height, row), np.uint8)
    previous = np.zeros(row, np.int32)
    for y in range(height):
        kind, line = raw[y * (row + 1)], np.frombuffer(raw, np.uint8, row, y * (row + 1) + 1).astype(np.int32)
        current = np.zeros(row, np.int32)
        for x in range(row):
            left = current[x - components] if x >= components else 0
            up_left = previous[x - components] if x >= components else 0
            up = previous[x]
            if kind == 0:
                guess = 0
            elif kind == 1:
                guess = left
            elif kind == 2:
                guess = up
            elif kind == 3:
                guess = (left + up) // 2
            else:
                p = left + up - up_left
                guess = min((abs(p - left), 0, left), (abs(p - up), 1, up), (abs(p - up_left), 2, up_left))[2]
            current[x] = (line[x] + guess) & 0xFF
        out[y], previous = current, current
    return out.reshape(height, width, components)


def pdf(path):
    data = open(path, "rb").read()
    objects = pdf_objects(data)
    for number, start in objects.items():
        end = data.find(b"stream", start)
        next_object = data.find(b"endobj", start)
        if end < 0 or end > next_object:
            continue
        header = data[start:end]
        icc_ref = re.search(rb"/ICCBased (\d+) 0 R", header)
        if b"/Subtype /Image" not in header or icc_ref is None:
            continue
        header, raw = pdf_stream(data, objects, number)
        width = int(re.search(rb"/Width (\d+)", header).group(1))
        height = int(re.search(rb"/Height (\d+)", header).group(1))
        bits = int(re.search(rb"/BitsPerComponent (\d+)", header).group(1))
        icc_header, icc = pdf_stream(data, objects, int(icc_ref.group(1)))
        components = int(re.search(rb"/N (\d+)", icc_header).group(1))
        if bits != 8:
            return description(icc), None, f"{bits} bits", icc
        pixels = unpredict(header, raw, width, height, components)
        if components == 4:
            return description(icc), tuple(int(v) for v in pixels[height // 2, width // 2]), "CMYK", icc
        return description(icc), to_srgb(Image.fromarray(pixels, "RGB"), icc), "RGB", icc
    return None, None, "no ICCBased image", None


def poppler_spaces(path):
    listing = subprocess.run(["pdfimages", "-list", path], capture_output=True, text=True).stdout.splitlines()[2:]
    return [(line.split()[5], int(line.split()[6])) for line in listing if line.strip()]


def ffmpeg_samples(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-f", "rawvideo", "-pix_fmt", "rgb48le", "-"],
                         capture_output=True, check=True).stdout
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                            "stream=width,height,pix_fmt", "-of", "csv=p=0", path],
                           capture_output=True, text=True, check=True).stdout.strip().split(",")
    width, height = int(probe[0]), int(probe[1])
    samples = np.frombuffer(raw, dtype="<u2").reshape(height, width, 3)
    return probe[2], samples[height // 2, width // 2].astype(np.float64) / 65535.0


def xyz_matrix(primaries, white):
    columns = np.array([[x / y, 1.0, (1 - x - y) / y] for x, y in primaries]).T
    white_xyz = np.array([white[0] / white[1], 1.0, (1 - white[0] - white[1]) / white[1]])
    return columns * np.linalg.solve(columns, white_xyz)


D65 = (0.3127, 0.3290)
P3_TO_SRGB = np.linalg.solve(xyz_matrix(((0.640, 0.330), (0.300, 0.600), (0.150, 0.060)), D65),
                             xyz_matrix(((0.680, 0.320), (0.265, 0.690), (0.150, 0.060)), D65))


def decode(v):
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def encode(v):
    v = np.clip(v, 0.0, 1.0)
    return np.where(v <= 0.0031308, v * 12.92, 1.055 * v ** (1 / 2.4) - 0.055)


def matrix_p3_to_srgb(samples):
    return tuple(int(round(value * 255)) for value in encode(P3_TO_SRGB @ decode(samples)))


def main():
    folder = sys.argv[1]
    path = lambda name: os.path.join(folder, name)  # noqa: E731
    checks = {}
    with tempfile.TemporaryDirectory() as scratch:
        script = os.path.join(scratch, "wic.ps1")
        with open(script, "w", newline="") as file:
            file.write(WIC_SCRIPT)
        control = os.path.join(scratch, "control.png")
        Image.new("RGB", (64, 64), P3_RED).save(control, icc_profile=profile_bytes("DisplayP3-v4.icc"))
        deep_control = os.path.join(scratch, "control16.tiff")
        tifffile.imwrite(deep_control, np.full((64, 64, 4), [value * 257 for value in P3_RED] + [65535], np.uint16),
                         photometric="rgb", extrasamples=["unassalpha"],
                         extratags=[(34675, 7, None, profile_bytes("DisplayP3-v4.icc"), False)])
        references = {"": wic(script, control)[1], "deep_": wic(script, deep_control)[1]}
        print(f"WIC     control (Pillow PNG, Display P3 {P3_RED}) -> {references['']}")
        print(f"WIC     control (tifffile 16-bit TIFF, same values) -> {references['deep_']}")
        for name in ("profile.png", "profile.jpeg", "profile.tiff", "deep_profile.png", "deep_profile.tiff"):
            source_format, pixel = wic(script, path(name))
            reference = references["deep_" if name.startswith("deep_") else ""]
            print(f"WIC     {name:20} {source_format:10} {pixel}")
            checks[f"WIC {name} = control"] = close_enough(pixel, reference, 2 if "jpeg" in name else 1) and \
                close_enough(pixel, SRGB_RED, 25)
    for name in ("profile.png", "profile.jpeg", "profile.tiff", "profile.psd"):
        profile, pixel, mode = pillow(path(name))
        print(f"Pillow  {name:20} {mode:5} {profile!r:30} {pixel}")
        checks[f"Pillow {name} -> sRGB red"] = profile is not None and "P3" in profile and \
            close_enough(pixel, SRGB_RED, 6 if name.endswith("jpeg") else 2)
    profile, pixel, mode, icc = pdf(path("profile.pdf"))
    print(f"PDF     {'profile.pdf':20} {mode:5} {profile!r:30} {pixel}")
    checks["PDF profile.pdf -> sRGB red"] = profile is not None and "P3" in profile and \
        close_enough(pixel, SRGB_RED, 6)
    for name, components in (("profile.pdf", 3), ("cmyk_profile.pdf", 4)):
        spaces = poppler_spaces(path(name))
        print(f"Poppler {name:20} {spaces}")
        checks[f"Poppler {name}: ICC image, {components} components"] = ("icc", components) in spaces
    for name in ("deep_profile.png", "deep_profile.tiff"):
        pixel_format, samples = ffmpeg_samples(path(name))
        srgb = matrix_p3_to_srgb(samples)
        print(f"ffmpeg  {name:20} {pixel_format:10} {np.round(samples * 65535).astype(int)} -> {srgb}")
        checks[f"ffmpeg {name}: 16 bits, P3 matrix -> sRGB red"] = ("48" in pixel_format or "64" in pixel_format) \
            and close_enough(srgb, SRGB_RED, 2)
    for name, reader in (("cmyk_profile.tiff", tiff_cmyk), ("cmyk_profile.psd", None), ("cmyk_profile.pdf", pdf)):
        if reader is None:
            profile, inks, mode = pillow(path(name))
            icc = Image.open(path(name)).info.get("icc_profile")
        else:
            profile, inks, mode, icc = reader(path(name))
        expected = expected_inks(icc) if icc else None
        print(f"CMYK    {name:20} {mode:14} {profile!r:26} inks {inks}, expected {expected}")
        checks[f"CMYK {name}: inks of the embedded profile"] = expected is not None and \
            close_enough(inks, expected, 2)
    for name, passed in checks.items():
        print(("PASS " if passed else "FAIL ") + name)
    print("RESULT", "ok" if all(checks.values()) else "failed")


if __name__ == "__main__":
    main()
