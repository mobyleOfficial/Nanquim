#!/usr/bin/env python3
"""Composes the Google Play listing graphics from raw device captures.

    python tools/store/compose.py <captures dir> <output dir>

Captures (PNG, straight from `adb exec-out screencap -p`):
    tablet[_en]/<key>.png  landscape 2560x1600 captures of the Galaxy Tab S7 FE (pt-BR / en-US UI)
    phone[_en]/<key>.png   1080x1920 captures with `wm size 1080x1920` + `wm density 420`
    art/<key>.png      full-resolution renders of the showcase artworks
Writes, per language (pt-BR, en-US): feature graphic, phone, 7" and 10" tablet screenshots.
"""

import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
FONTS = os.path.join(REPO, "assets-src", "fonts")
BOLD = os.path.join(FONTS, "Inter-Bold.ttf")
REGULAR = os.path.join(FONTS, "Inter-Regular.ttf")

INK = (24, 19, 12)
BG_TOP = (46, 36, 20)
BG_BOTTOM = (20, 15, 9)
CREAM = (246, 238, 223)
ACCENT = (232, 120, 96)
MUTED = (205, 190, 165)

# Canvas screens are immersive; only the gallery shows the status bar and the navigation/task bar.
CROPS = {("tablet", "gallery"): (0, 56, 2560, 1496), ("phone", "gallery"): (0, 64, 1080, 1794)}
CAPTURE_DIRS = {"pt-BR": ("tablet", "phone"), "en-US": ("tablet_en", "phone_en")}

COPY = {
    "pt-BR": {
        "hero": ("Pinte como no papel.", "Feito para tablet e caneta stylus"),
        "brushes": ("Mais de 200 pincéis", "Nanquim, aquarela, óleo, carvão, spray e muito mais"),
        "layers": ("Camadas sem limite de criatividade", "27 modos de mesclagem, máscaras e grupos"),
        "colors": ("Cores certeiras", "Disco, harmonias, paletas e conta-gotas"),
        "adjust": ("Efeitos em tempo real", "Curvas, mapa de gradiente, bloom, desfoque e mais"),
        "animation": ("Dê vida aos desenhos", "Animação quadro a quadro com papel vegetal"),
        "guides": ("Simetria e perspectiva", "Guias que deixam cada traço no lugar"),
        "gallery": ("Grátis. Sem anúncios. Sem conta.", "Abre PSD, exporta PNG, PSD, GIF e MP4"),
        "feature_title": "Nanquim",
        "feature_sub": "Pinte do seu jeito",
    },
    "en-US": {
        "hero": ("Paint like it's paper.", "Made for tablets and stylus pens"),
        "brushes": ("200+ brushes", "Ink, watercolor, oil, charcoal, spray and more"),
        "layers": ("Layers, the way pros work", "27 blend modes, masks and groups"),
        "colors": ("Spot-on color", "Color disc, harmonies, palettes and eyedropper"),
        "adjust": ("Live effects", "Curves, gradient map, bloom, blur and more"),
        "animation": ("Bring drawings to life", "Frame-by-frame animation with onion skin"),
        "guides": ("Symmetry and perspective", "Guides that keep every line in place"),
        "gallery": ("Free. No ads. No account.", "Opens PSD, exports PNG, PSD, GIF and MP4"),
        "feature_title": "Nanquim",
        "feature_sub": "Paint your way",
    },
}

ORDER = ["hero", "brushes", "layers", "colors", "adjust", "animation", "guides", "gallery"]


def font(path, size):
    return ImageFont.truetype(path, size)


def gradient(size, top=BG_TOP, bottom=BG_BOTTOM):
    w, h = size
    image = Image.new("RGB", size)
    draw = ImageDraw.Draw(image)
    for y in range(h):
        t = y / max(1, h - 1)
        draw.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return image


def rounded(image, radius):
    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, image.width - 1, image.height - 1], radius, fill=255)
    out = Image.new("RGBA", image.size)
    out.paste(image, (0, 0), mask)
    return out


def device(screen, bezel, radius):
    """Wraps a screen capture in a simple dark device frame with a soft shadow."""
    w, h = screen.width + 2 * bezel, screen.height + 2 * bezel
    pad = bezel * 3
    canvas = Image.new("RGBA", (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([pad, pad + bezel, pad + w, pad + h + bezel], radius + bezel, fill=(0, 0, 0, 170))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(bezel * 1.6)))
    body = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(body).rounded_rectangle([0, 0, w - 1, h - 1], radius + bezel, fill=(14, 13, 12, 255), outline=(70, 64, 56, 255), width=max(2, bezel // 8))
    body.alpha_composite(rounded(screen.convert("RGB"), radius), (bezel, bezel))
    canvas.alpha_composite(body, (pad, pad))
    return canvas, pad


def fit_text(draw, text, path, size, max_width):
    while size > 10:
        f = font(path, size)
        if draw.textlength(text, font=f) <= max_width:
            return f
        size -= 2
    return font(path, size)


def captioned(size, screen, title, subtitle, *, bezel, radius, title_size, sub_size, top_frac, side_frac):
    w, h = size
    image = gradient(size).convert("RGBA")
    draw = ImageDraw.Draw(image)
    margin = int(w * 0.06)
    tf = fit_text(draw, title, BOLD, title_size, w - 2 * margin)
    sf = fit_text(draw, subtitle, REGULAR, sub_size, w - 2 * margin)
    y = int(h * 0.045)
    draw.text((w / 2, y), title, font=tf, fill=CREAM, anchor="ma")
    y += int(tf.size * 1.25)
    draw.text((w / 2, y), subtitle, font=sf, fill=MUTED, anchor="ma")
    top = int(h * top_frac)
    avail_w = int(w * (1 - 2 * side_frac))
    avail_h = h - top + bezel * 4
    scale = min(avail_w / (screen.width + 2 * bezel), avail_h / (screen.height + 2 * bezel))
    shot = screen.resize((int(screen.width * scale), int(screen.height * scale)), Image.LANCZOS)
    framed, pad = device(shot, max(6, int(bezel * scale * 1.6)), max(8, int(radius * scale * 1.6)))
    image.alpha_composite(framed, ((w - framed.width) // 2, top - pad))
    return image.convert("RGB")


def hero_tablet(size, art, screen, title, subtitle):
    """First screenshot: the artwork full-bleed, the app's canvas in front, big claim."""
    w, h = size
    bg = art.convert("RGB").resize((w, int(art.height * w / art.width)), Image.LANCZOS)
    bg = bg.crop((0, (bg.height - h) // 2, w, (bg.height - h) // 2 + h)) if bg.height >= h else bg.resize(size)
    bg = bg.filter(ImageFilter.GaussianBlur(w // 90))
    shade = Image.new("RGBA", size, (16, 12, 8, 150))
    image = bg.convert("RGBA")
    image.alpha_composite(shade)
    draw = ImageDraw.Draw(image)
    margin = int(w * 0.06)
    tf = fit_text(draw, title, BOLD, int(h * 0.085), w - 2 * margin)
    sf = fit_text(draw, subtitle, REGULAR, int(h * 0.04), w - 2 * margin)
    draw.text((w / 2, int(h * 0.05)), title, font=tf, fill=CREAM, anchor="ma")
    draw.text((w / 2, int(h * 0.05) + int(tf.size * 1.25)), subtitle, font=sf, fill=MUTED, anchor="ma")
    top = int(h * 0.24)
    scale = min(w * 0.86 / screen.width, (h - top + h * 0.06) / screen.height)
    shot = screen.resize((int(screen.width * scale), int(screen.height * scale)), Image.LANCZOS)
    framed, pad = device(shot, int(w * 0.012), int(w * 0.012))
    image.alpha_composite(framed, ((w - framed.width) // 2, top - pad))
    return image.convert("RGB")


def feature_graphic(art, title, subtitle, icon):
    w, h = 1024, 500
    bg = art.convert("RGB")
    bg = bg.resize((w, int(bg.height * w / bg.width)), Image.LANCZOS)
    bg = bg.crop((0, (bg.height - h) // 2, w, (bg.height - h) // 2 + h))
    image = bg.convert("RGBA")
    shade = Image.new("RGBA", (w, h))
    sd = ImageDraw.Draw(shade)
    for x in range(w):
        t = x / w
        # Solid behind the text, then a long smoothstep fade into the artwork.
        u = min(1.0, max(0.0, (t - 0.30) / 0.45))
        a = int(248 * (1 - u * u * (3 - 2 * u)))
        sd.line([(x, 0), (x, h)], fill=(22, 17, 11, a))
    image.alpha_composite(shade)
    draw = ImageDraw.Draw(image)
    ic = rounded(icon.convert("RGB").resize((112, 112), Image.LANCZOS), 26)
    image.alpha_composite(ic, (56, 110))
    draw.text((56, 238), title, font=font(BOLD, 72), fill=CREAM)
    draw.text((58, 334), subtitle, font=font(REGULAR, 40), fill=MUTED)
    return image.convert("RGB")


def main():
    captures, output = sys.argv[1], sys.argv[2]
    load = lambda *p: Image.open(os.path.join(captures, *p))  # noqa: E731
    icon = Image.open(os.path.join(REPO, "art", "app-icon", "ic_nanquim_app_icon.jpeg"))
    hero_art = load("art", "cherry.png")
    for lang, copy in COPY.items():
        out = os.path.join(output, lang)
        for sub in ("phone", "tablet-7", "tablet-10"):
            os.makedirs(os.path.join(out, sub), exist_ok=True)
        feature_graphic(load("art", "feature.png") if os.path.exists(os.path.join(captures, "art", "feature.png")) else hero_art,
                        copy["feature_title"], copy["feature_sub"], icon).save(os.path.join(out, "feature-graphic-1024x500.png"))
        for index, key in enumerate(ORDER, 1):
            title, subtitle = copy[key]
            tablet_dir, phone_dir = CAPTURE_DIRS[lang]
            tab = os.path.join(captures, tablet_dir, f"{key}.png")
            if os.path.exists(tab):
                screen = Image.open(tab).convert("RGB")
                if ("tablet", key) in CROPS:
                    screen = screen.crop(CROPS[("tablet", key)])
                if key == "hero":
                    img = hero_tablet((2560, 1600), hero_art, screen, title, subtitle)
                else:
                    img = captioned((2560, 1600), screen, title, subtitle, bezel=40, radius=36,
                                    title_size=96, sub_size=50, top_frac=0.215, side_frac=0.07)
                img.save(os.path.join(out, "tablet-10", f"{index:02d}-{key}.png"))
                img.resize((1920, 1200), Image.LANCZOS).save(os.path.join(out, "tablet-7", f"{index:02d}-{key}.png"))
            ph = os.path.join(captures, phone_dir, f"{key}.png")
            if os.path.exists(ph):
                screen = Image.open(ph).convert("RGB")
                if ("phone", key) in CROPS:
                    screen = screen.crop(CROPS[("phone", key)])
                img = captioned((1080, 1920), screen, title, subtitle, bezel=34, radius=60,
                                title_size=88, sub_size=46, top_frac=0.185, side_frac=0.08)
                img.save(os.path.join(out, "phone", f"{index:02d}-{key}.png"))
        icon.convert("RGB").resize((512, 512), Image.LANCZOS).save(os.path.join(out, "icon-512.png"))
        print(lang, "done")


if __name__ == "__main__":
    main()
