import io
import os
import plistlib
import struct
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))


def write(target, name, data):
    folder = os.path.join(HERE, "corpus", target)
    os.makedirs(folder, exist_ok=True)
    with open(os.path.join(folder, name), "wb") as file:
        file.write(data)


def zip_bytes(entries, compression=zipfile.ZIP_DEFLATED, force_zip64=False, comment=b""):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=compression) as archive:
        archive.comment = comment
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 15, 0, 0, 0))
            info.compress_type = compression
            with archive.open(info, "w", force_zip64=force_zip64) as entry:
                entry.write(data)
    return buffer.getvalue()


def zip_seeds():
    pattern = bytes((index * 37) % 251 for index in range(3000))
    write("zip", "deflated.zip", zip_bytes([("Document.archive", b"bplist00" + pattern[:200]),
                                            ("a/b/tile.chunk", pattern)]))
    write("zip", "stored.zip", zip_bytes([("one.txt", b"one"), ("two/", b"")],
                                         compression=zipfile.ZIP_STORED))
    write("zip", "zip64.zip", zip_bytes([("big.bin", pattern)], force_zip64=True))
    write("zip", "comment.zip", zip_bytes([("c", b"x" * 10)], comment=b"nanquim seed"))
    write("zip", "empty.zip", zip_bytes([]))


def packbits(row):
    out = bytearray()
    for start in range(0, len(row), 128):
        chunk = row[start:start + 128]
        out.append(len(chunk) - 1)
        out += chunk
    return bytes(out)


def psd_bytes(width, height, layers, compression=1, depth=8, mode=3, channels=3, psb=False):
    be = lambda fmt, *values: struct.pack(">" + fmt, *values)
    length = (lambda value: be("Q", value)) if psb else (lambda value: be("I", value))
    sample = depth // 8

    def channel(data, w, h):
        if compression == 0:
            return be("H", 0) + data
        rows = [packbits(data[y * w * sample:(y + 1) * w * sample]) for y in range(h)]
        counts = b"".join(be("I" if psb else "H", len(row)) for row in rows)
        return be("H", 1) + counts + b"".join(rows)

    records, channel_data = b"", b""
    for name, (top, left, bottom, right), planes, blend, extra in layers:
        encoded = {cid: channel(data, right - left, bottom - top) for cid, data in planes.items()}
        record = be("iiii", top, left, bottom, right) + be("H", len(encoded))
        for cid, data in encoded.items():
            record += be("h", cid) + length(len(data))
        record += b"8BIM" + blend + bytes([255, 0, 0, 0])
        raw_name = name.encode("latin-1")
        padded = bytes([len(raw_name)]) + raw_name
        padded += b"\x00" * ((4 - len(padded) % 4) % 4)
        extra_data = be("I", 0) + be("I", 0) + padded + extra
        record += be("I", len(extra_data)) + extra_data
        records += record
        channel_data += b"".join(encoded.values())
    info = be("h", len(layers)) + records + channel_data if layers else b""
    section = (length(len(info)) + info + be("I", 0)) if layers else b""
    out = b"8BPS" + be("H", 2 if psb else 1) + b"\x00" * 6 + be("HIIHH", channels, height, width, depth, mode)
    out += be("I", 0) + be("I", 0) + length(len(section)) + section
    merged = b"".join(bytes(width * height * sample) for _ in range(channels))
    out += be("H", 0) + merged
    return out


def psd_seeds():
    w, h = 20, 12
    red = bytes([200]) * (w * h)
    zero = bytes(w * h)
    alpha = bytes((x * 12) % 256 for x in range(w * h))
    lsct = lambda kind: b"8BIMlsct" + struct.pack(">I", 12) + struct.pack(">I", kind) + b"8BIMpass"
    luni = b"8BIMluni" + struct.pack(">I", 12) + struct.pack(">I", 4) + "Coré".encode("utf-16-be")
    layers = [("bottom", (0, 0, h, w), {-1: alpha, 0: red, 1: zero, 2: zero}, b"norm", luni),
              ("</Layer group>", (0, 0, 0, 0), {0: b"", 1: b"", 2: b""}, b"norm", lsct(3)),
              ("inner", (2, 3, 8, 9), {0: bytes(36), 1: bytes([90]) * 36, 2: bytes(36)}, b"mul ", b""),
              ("group", (0, 0, 0, 0), {0: b"", 1: b"", 2: b""}, b"pass", lsct(1))]
    write("psd", "layers_rle.psd", psd_bytes(w, h, layers))
    write("psd", "layers_raw.psd", psd_bytes(w, h, layers, compression=0))
    write("psd", "layers.psb", psd_bytes(w, h, layers, psb=True))
    write("psd", "merged_only.psd", psd_bytes(w, h, []))
    gray = [("gray", (0, 0, h, w), {0: bytes(range(w * h))}, b"scrn", b"")]
    write("psd", "gray.psd", psd_bytes(w, h, gray, mode=1, channels=1))


def tiff_lzw(data):
    bits = []

    def emit(code, width):
        bits.extend((code >> shift) & 1 for shift in range(width - 1, -1, -1))

    table = {bytes([value]): value for value in range(256)}
    next_code, width = 258, 9
    emit(256, width)
    current = b""
    for value in data:
        candidate = current + bytes([value])
        if candidate in table:
            current = candidate
            continue
        emit(table[current], width)
        table[candidate] = next_code
        next_code += 1
        if next_code == 4094:
            emit(256, width)
            table = {bytes([v]): v for v in range(256)}
            next_code, width = 258, 9
        elif next_code == (1 << width) and width < 12:
            width += 1
        current = bytes([value])
    if current:
        emit(table[current], width)
        next_code += 1
        if next_code == (1 << width) and width < 12:
            width += 1
    emit(257, width)
    bits.extend([0] * (-len(bits) % 8))
    return bytes(int("".join(map(str, bits[index:index + 8])), 2) for index in range(0, len(bits), 8))


def tiff_bytes(width, height, bits, photometric, rows, compression=1, predictor=1,
               rows_per_strip=None, little=True, extra=None, colormap=None, samples=None):
    order = "<" if little else ">"
    pack = lambda fmt, *values: struct.pack(order + fmt, *values)
    samples = samples or (1 if photometric in (0, 1, 3) else 3 if photometric == 2 else 4) + (1 if extra else 0)
    rows_per_strip = rows_per_strip or height
    strips = []
    for top in range(0, height, rows_per_strip):
        chunk = list(rows[top:top + rows_per_strip])
        if predictor == 2:
            step = samples * (bits // 8)
            unit = bits // 8
            predicted = []
            for row in chunk:
                values = [int.from_bytes(row[i:i + unit], "little" if little else "big") for i in range(0, len(row), unit)]
                deltas = values[:samples] + [(values[i] - values[i - samples]) % (1 << bits) for i in range(samples, len(values))]
                predicted.append(b"".join(v.to_bytes(unit, "little" if little else "big") for v in deltas))
            chunk = predicted
        data = b"".join(chunk)
        if compression == 5:
            data = tiff_lzw(data)
        elif compression == 8:
            data = zlib.compress(data)
        elif compression == 32773:
            data = b"".join(packbits(row) for row in chunk)
        strips.append(data)
    out = bytearray((b"II" if little else b"MM") + pack("HI", 42, 0))
    offsets = []
    for data in strips:
        offsets.append(len(out))
        out += data + (b"\x00" if len(data) % 2 else b"")
    entries = [(256, 4, [width]), (257, 4, [height]), (258, 3, [bits] * samples), (259, 3, [compression]),
               (262, 3, [photometric]), (273, 4, offsets), (277, 3, [samples]), (278, 4, [rows_per_strip]),
               (279, 4, [len(data) for data in strips]), (284, 3, [1])]
    if predictor != 1:
        entries.append((317, 3, [predictor]))
    if colormap:
        entries.append((320, 3, colormap))
    if extra:
        entries.append((338, 3, [extra]))
    entries.sort()
    blobs = []
    for tag, kind, values in entries:
        raw = b"".join(pack("H" if kind == 3 else "I", value) for value in values)
        if len(raw) > 4:
            blobs.append(len(out))
            out += raw
        else:
            blobs.append(raw.ljust(4, b"\x00"))
    directory = len(out)
    out += pack("H", len(entries))
    for (tag, kind, values), blob in zip(entries, blobs):
        out += pack("HHI", tag, kind, len(values))
        out += pack("I", blob) if isinstance(blob, int) else blob
    out += pack("I", 0)
    out[4:8] = pack("I", directory)
    return bytes(out)


def tiff_seeds():
    w, h = 21, 13
    rgb = [bytes((x * 12 + y, y * 19, (x ^ y) * 5) [c] for x in range(w) for c in range(3)) for y in range(h)]
    write("tiff", "rgb_lzw_predictor.tif", tiff_bytes(w, h, 8, 2, rgb, compression=5, predictor=2, rows_per_strip=5))
    rgba16 = [b"".join(struct.pack(">HHHH", x * 3000, y * 5000, 40000, (x + y) * 2000) for x in range(w)) for y in range(h)]
    write("tiff", "rgba16_deflate_big_endian.tif", tiff_bytes(w, h, 16, 2, rgba16, compression=8, little=False, extra=2))
    gray = [bytes((x * y) % 256 for x in range(w)) for y in range(h)]
    write("tiff", "gray_packbits.tif", tiff_bytes(w, h, 8, 1, gray, compression=32773, rows_per_strip=7))
    palette = [bytes(((2 * x) % 16 << 4) | ((2 * x + 1) % 16) for x in range((w + 1) // 2)) for y in range(h)]
    colormap = [i * 4096 for i in range(16)] + [65535 - i * 4096 for i in range(16)] + [30000] * 16
    write("tiff", "palette4.tif", tiff_bytes(w, h, 4, 3, palette, colormap=colormap))
    cmyk = [bytes((x * 10, y * 10, 0, 30)[c] for x in range(w) for c in range(4)) for y in range(h)]
    write("tiff", "cmyk.tif", tiff_bytes(w, h, 8, 5, cmyk))
    bilevel = [bytes(0xAA if y % 2 else 0x0F for _ in range((w + 7) // 8)) for y in range(h)]
    write("tiff", "bilevel_white_is_zero.tif", tiff_bytes(w, h, 1, 0, bilevel, compression=32773))


def lz4_literals(data):
    length = len(data)
    if length < 15:
        return bytes([length << 4]) + data
    out = bytearray([0xF0])
    rest = length - 15
    while rest >= 255:
        out.append(255)
        rest -= 255
    out.append(rest)
    return bytes(out) + data


def bv41(data, block=65536):
    out = bytearray()
    for start in range(0, len(data), block):
        chunk = data[start:start + block]
        encoded = lz4_literals(chunk)
        out += b"bv41" + struct.pack("<II", len(chunk), len(encoded)) + encoded
    return bytes(out + b"bv4$")


class KeyedArchive:
    def __init__(self):
        self.objects = ["$null"]
        self.classes = {}

    def add(self, value):
        self.objects.append(value)
        return plistlib.UID(len(self.objects) - 1)

    def cls(self, name):
        if name not in self.classes:
            self.classes[name] = self.add({"$classname": name, "$classes": [name, "NSObject"]})
        return self.classes[name]

    def string(self, text):
        return self.add(text)

    def array(self, items):
        return self.add({"$class": self.cls("NSArray"), "NS.objects": list(items)})

    def obj(self, name, fields):
        value = {"$class": self.cls(name)}
        value.update(fields)
        return self.add(value)

    def bytes(self, root):
        return plistlib.dumps({"$archiver": "NSKeyedArchiver", "$version": 100000, "$objects": self.objects,
                               "$top": {"root": root}}, fmt=plistlib.FMT_BINARY)


def procreate_bytes(width, height, tree, pixel_of, orientation=1, background=(1.0, 1.0, 1.0, 1.0),
                    background_hidden=False, flipped_horizontally=False, flipped_vertically=False):
    archive = KeyedArchive()
    files = {}
    flat = []
    null = plistlib.UID(0)

    def tiles(uuid, sample, pixel_bytes):
        columns, rows = (width + 255) // 256, (height + 255) // 256
        for row in range(rows):
            for column in range(columns):
                tile_width = min(256, width - column * 256)
                tile_height = min(256, height - row * 256)
                data = bytearray()
                for stored in range(tile_height):
                    y_top = height - 1 - (row * 256 + stored)
                    for x in range(column * 256, column * 256 + tile_width):
                        data += bytes(sample(x, y_top))
                empty = not any(data[3::4]) if pixel_bytes == 4 else all(value == 255 for value in data)
                if not empty:
                    files[f"{uuid}/{column}~{row}.lz4"] = bv41(bytes(data))

    def node(entry):
        if entry[0] == "group":
            _, name, fields, children = entry
            child_refs = [node(child) for child in children]
            values = {"name": archive.string(name), "opacity": 1.0, "isHidden": False, "isLocked": False,
                      "isCollapsed": False, "isClipped": False, "children": archive.array(child_refs)}
            values.update(fields)
            return archive.obj("SilicaGroup", values)
        _, name, fields = entry
        fields = dict(fields)
        mask_of = fields.pop("_mask", None)
        uuid = "00000000-0000-4000-8000-%012d" % (len(flat) + 1)
        values = {"UUID": archive.string(uuid), "name": archive.string(name), "opacity": 1.0, "blend": 0,
                  "extendedBlend": 0, "hidden": False, "locked": False, "clipped": False, "preserve": False,
                  "type": 0, "mask": null, "text": null, "sizeWidth": width, "sizeHeight": height}
        values.update(fields)
        if mask_of is not None:
            mask_uuid = "00000000-0000-4000-9000-%012d" % (len(flat) + 1)
            values["mask"] = archive.obj("SilicaLayer", {"UUID": archive.string(mask_uuid), "hidden": False,
                                                         "opacity": 1.0, "type": 0, "name": null})
            tiles(mask_uuid, lambda x, y: (mask_of(x, y),), 1)
        reference = archive.obj("SilicaLayer", values)
        flat.append(reference)
        tiles(uuid, lambda x, y: pixel_of(name, x, y), 4)
        return reference

    roots = [node(entry) for entry in tree]
    document = archive.obj("SilicaDocument", {
        "size": archive.string("{%d, %d}" % (width, height)), "tileSize": 256, "orientation": orientation,
        "flippedHorizontally": flipped_horizontally, "flippedVertically": flipped_vertically, "SilicaDocumentArchiveDPIKey": 300.0,
        "backgroundColor": struct.pack("<4f", *background), "backgroundHidden": background_hidden,
        "unwrappedLayers": archive.array(roots), "layers": archive.array(flat),
        "colorProfile": archive.obj("ValkyrieColorProfile", {"SiColorProfileArchiveICCNameKey": archive.string("sRGB IEC61966-2.1")}),
        "name": null})
    document_bytes = archive.bytes(document)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive_zip:
        archive_zip.writestr("Document.archive", document_bytes)
        for name, data in files.items():
            archive_zip.writestr(name, data)
    return buffer.getvalue(), document_bytes


def procreate_pixel(name, x, y):
    if name == "Base":
        return (200, 30, 30, 255)
    if name == "Clipped":
        return (0, 120, 0, 120) if x < 150 else (0, 0, 0, 0)
    if name == "Multiply":
        return (0, 0, 128, 128) if 40 <= x < 280 and 20 <= y < 120 else (0, 0, 0, 0)
    if name == "Gradient":
        return (x % 256, y % 256, 50, 255)
    return (0, 0, 0, 0)


def procreate_seeds():
    tree = [("layer", "Multiply", {"blend": 1, "opacity": 0.5}),
            ("group", "Group", {"isCollapsed": True}, [("layer", "Clipped", {"clipped": True}),
                                                      ("layer", "Base", {})])]
    layered, document = procreate_bytes(300, 200, tree, procreate_pixel, background=(0.9, 0.8, 0.7, 1.0))
    write("procreate", "layers.procreate", layered)
    write("plist", "document.archive", document)
    rotated, _ = procreate_bytes(300, 100, [("layer", "Gradient", {})], procreate_pixel, orientation=4)
    write("procreate", "rotated.procreate", rotated)
    unknown, _ = procreate_bytes(20, 20, [("layer", "Odd", {"blend": 7, "preserve": True, "type": 3})], procreate_pixel)
    write("procreate", "unconfirmed.procreate", unknown)
    for orientation in (2, 3):
        turned, _ = procreate_bytes(300, 100, [("layer", "Gradient", {})], procreate_pixel, orientation=orientation)
        write("procreate", f"orientation{orientation}.procreate", turned)
    flipped, _ = procreate_bytes(300, 100, [("layer", "Gradient", {})], procreate_pixel,
                                 flipped_horizontally=True, flipped_vertically=True)
    write("procreate", "flipped.procreate", flipped)
    masked, _ = procreate_bytes(300, 100, [("layer", "Gradient", {"_mask": lambda x, y: 0 if x < 100 else 255})],
                                procreate_pixel)
    write("procreate", "masked.procreate", masked)
    write("plist", "minimal.bplist", plistlib.dumps({"$archiver": "NSKeyedArchiver", "$objects": ["$null", "x"],
                                                     "$top": {"root": plistlib.UID(1)}}, fmt=plistlib.FMT_BINARY))


def utf16_name(text, count_bytes):
    encoded = text.encode("utf-16-be") + b"\x00\x00"
    units = len(encoded) // 2
    return (struct.pack(">I", units) if count_bytes == 4 else struct.pack(">H", units)) + encoded


def palette_seeds():
    colors = [(0, (65535, 32768, 0, 0), "Laranja"), (1, (21845, 65535, 65535, 0), "Verde HSB"),
              (2, (65535, 0, 65535, 65535), "Magenta CMYK"), (7, (5000, 2000, 65536 - 3000, 0), "Lab"),
              (8, (2500, 0, 0, 0), "Cinza")]
    version1 = struct.pack(">HH", 1, len(colors)) + b"".join(
        struct.pack(">HHHHH", space, *values) for space, values, _ in colors)
    version2 = struct.pack(">HH", 2, len(colors)) + b"".join(
        struct.pack(">HHHHH", space, *values) + utf16_name(name, 4) for space, values, name in colors)
    write("palette", "both.aco", version1 + version2)
    write("palette", "version1.aco", version1)
    write("palette", "version2.aco", version2)

    def ase_block(kind, body):
        return struct.pack(">HI", kind, len(body)) + body

    def ase_color(name, model, values):
        return ase_block(0x0001, utf16_name(name, 2) + model + b"".join(struct.pack(">f", v) for v in values)
                         + struct.pack(">H", 2))

    blocks = [ase_block(0xC001, utf16_name("Grupo", 2)), ase_color("Azul", b"RGB ", (0.0, 0.25, 1.0)),
              ase_color("Ciano", b"CMYK", (1.0, 0.0, 0.0, 0.0)), ase_color("Branco", b"LAB ", (1.0, 0.0, 0.0)),
              ase_color("Cinza", b"Gray", (0.5,)), ase_block(0xC002, b"")]
    write("palette", "groups.ase", b"ASEF" + struct.pack(">HHI", 1, 0, len(blocks)) + b"".join(blocks))
    write("palette", "gimp.gpl", "GIMP Palette\nName: Semente\nColumns: 4\n# comentário\n"
                                 "255   0   0\tVermelho\n  0 128 255 Céu\n".encode("utf-8"))


def icc_seeds():
    profiles = os.path.join(HERE, "..", "..", "..", "assets-src", "profiles")
    for name in sorted(os.listdir(profiles)):
        with open(os.path.join(profiles, name), "rb") as file:
            write("icc", name, file.read())


if __name__ == "__main__":
    zip_seeds()
    psd_seeds()
    tiff_seeds()
    procreate_seeds()
    palette_seeds()
    icc_seeds()
