"""Builds the Play Store listing images and the website screens from the raw renders.

    ./gradlew -q testDebugUnitTest --tests '*StoreShowcaseTest*' -PstoreShots=true   # -> app/build/store/raw
    python3 -I tools/store/compose.py                                                 # -> docs/store

Writes docs/store/NN_name.png (1080x1920 phone screenshots), docs/store/feature_graphic.png (1024x500)
and docs/store/site/*.webp (bare framed phones for the landing page). Needs Pillow and numpy.
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "app" / "build" / "store" / "raw"
OUT = ROOT / "docs" / "store"
FONTS = ROOT / "app" / "src" / "main" / "res" / "font"
HEAD = FONTS / "opensans_semibold.ttf"
BODY = FONTS / "opensans_regular.ttf"

W, H = 1080, 1920

# (raw file, output name, headline, subhead, gradient start, gradient end, glow colours)
FRAMES = [
    ("01_hero", "01_home_screen", "Your home screen, alive", "Live tiles for messages, music, weather and more",
     "#0a0f3a", "#7b2cbf", ["#00d4ff", "#ff2bd6"]),
    ("02_custom", "02_make_it_yours", "Make it yours", "Photos through tiles, 20 accents, light or dark",
     "#2b0f4a", "#f0507a", ["#ff9a3c", "#8a5cff"]),
    ("03_folder", "03_folders", "Folders that open in place", "Tap to expand right under the row, or show just a photo",
     "#04223d", "#1b8fd6", ["#8a5cff", "#00d4ff"]),
    ("04_search", "04_search", "Find anything, fast", "Apps, contacts, events and quick maths in one search",
     "#07162e", "#0e7c86", ["#2ef2b1", "#1ba1e2"]),
    ("05_app_list", "05_app_list", "Every app, A to Z", "Swipe left, then scrub the alphabet to jump anywhere",
     "#101a3a", "#1b6fd1", ["#00d4ff", "#7c4dff"]),
    ("07_turnstile", "06_turnstile", "Smooth as a turnstile", "Six launch animations, tuned to your taste",
     "#1a0b3d", "#5b2bd9", ["#ff2bd6", "#00d4ff"]),
    ("08_edit", "07_edit_mode", "Arrange it your way", "Press and hold to move, resize or group tiles",
     "#1b0b3a", "#c2185b", ["#ff8a3d", "#7c4dff"]),
    ("09_quick", "08_battery", "Barely touches your battery", "Nothing runs while you're away",
     "#031a0f", "#00a300", ["#6bffb8", "#19d3f5"]),
]

# Extra screens that only appear on the website: (raw file, output name)
SITE_EXTRA = [("02b_full_photo", "full_photo"), ("04b_search_calc", "search_calc"), ("06b_animations", "animations")]


def hex_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def font(path, size):
    return ImageFont.truetype(str(path), size)


def background(w, h, c1, c2, glows):
    """A diagonal gradient with a couple of soft glows."""
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    t = np.clip((0.35 * x / w + 0.65 * y / h), 0, 1)[..., None]
    a, b = np.array(hex_rgb(c1), np.float32), np.array(hex_rgb(c2), np.float32)
    img = a * (1 - t) + b * t
    spots = [(0.1, 0.85, 0.75), (0.95, 0.55, 0.6)]
    for (fx, fy, size), col in zip(spots, glows):
        d = np.sqrt(((x - fx * w) / w) ** 2 + ((y - fy * h) / h) ** 2)
        g = np.exp(-(d / (size * 0.45)) ** 2)[..., None] * 0.55
        img = img * (1 - g) + np.array(hex_rgb(col), np.float32) * g
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def rounded_mask(size, radius, scale=4):
    big = Image.new("L", (size[0] * scale, size[1] * scale), 0)
    ImageDraw.Draw(big).rounded_rectangle((0, 0, big.width - 1, big.height - 1), radius * scale, fill=255)
    return big.resize(size, Image.LANCZOS)


def phone(raw, screen_w, shadow=True):
    """The screen in a thin rounded bezel, on a transparent canvas padded for the shadow."""
    shot = Image.open(raw).convert("RGB")
    screen_h = round(screen_w * shot.height / shot.width)
    shot = shot.resize((screen_w, screen_h), Image.LANCZOS)
    bezel = max(6, round(screen_w * 0.018))
    outer = (screen_w + 2 * bezel, screen_h + 2 * bezel)
    r_out = round(screen_w * 0.105)
    pad = round(screen_w * 0.12) if shadow else 0
    canvas = Image.new("RGBA", (outer[0] + 2 * pad, outer[1] + 2 * pad), (0, 0, 0, 0))
    if shadow:
        sh = Image.new("L", canvas.size, 0)
        sh.paste(rounded_mask(outer, r_out), (pad, pad + round(screen_w * 0.03)))
        sh = sh.filter(ImageFilter.GaussianBlur(pad * 0.45)).point(lambda v: int(v * 0.55))
        canvas.paste(Image.new("RGBA", canvas.size, (0, 0, 0, 255)), (0, 0), sh)
    body = Image.new("RGBA", (outer[0] - 4, outer[1] - 4), (12, 12, 16, 255))
    # A lighter rim so dark screens still read against dark backgrounds.
    rim = Image.new("RGBA", outer, (96, 98, 110, 255))
    inner_rim = rounded_mask((outer[0] - 4, outer[1] - 4), r_out - 2)
    rim.paste(body, (2, 2), inner_rim)
    canvas.paste(rim, (pad, pad), rounded_mask(outer, r_out))
    scr = shot.convert("RGBA")
    scr.putalpha(rounded_mask(shot.size, r_out - bezel))
    canvas.alpha_composite(scr, (pad + bezel, pad + bezel))
    return canvas


def wrap(draw, text, fnt, max_w):
    lines, line = [], ""
    for word in text.split():
        trial = (line + " " + word).strip()
        if draw.textlength(trial, font=fnt) <= max_w or not line:
            line = trial
        else:
            lines.append(line)
            line = word
    lines.append(line)
    return lines


def fit(draw, text, path, sizes, max_w):
    """The biggest size in [sizes] where [text] fits on one line, else two balanced lines at the smallest."""
    for size in sizes:
        f = font(path, size)
        if draw.textlength(text, font=f) <= max_w:
            return f, [text]
    f = font(path, sizes[-1])
    words = text.split()
    best = min(range(1, len(words)), key=lambda i: max(
        draw.textlength(" ".join(words[:i]), font=f), draw.textlength(" ".join(words[i:]), font=f)))
    return f, [" ".join(words[:best]), " ".join(words[best:])]


def centered(draw, lines, fnt, y, fill, width, gap):
    for line in lines:
        draw.text(((width - draw.textlength(line, font=fnt)) / 2, y), line, font=fnt, fill=fill)
        y += fnt.size + gap
    return y


def store_frame(raw, name, head, sub, c1, c2, glows):
    img = background(W, H, c1, c2, glows)
    draw = ImageDraw.Draw(img)
    fh, hl = fit(draw, head, HEAD, [92, 88, 84, 80], 960)
    y = centered(draw, hl, fh, 120, (255, 255, 255, 255), W, 10)
    fs, sl = fit(draw, sub, BODY, [44, 42, 40, 38], 960)
    y = centered(draw, sl, fs, y + 22, (255, 255, 255, 215), W, 8)
    p = phone(RAW / f"{raw}.png", 800)
    top = max(int(y + 64), 440)
    img.alpha_composite(p, ((W - p.width) // 2, top - round(800 * 0.12)))
    img.convert("RGB").save(OUT / f"{name}.png", optimize=True)
    return W, H


def feature_graphic():
    w, h = 1024, 500
    img = background(w, h, "#0a0f3a", "#7b2cbf", ["#00d4ff", "#ff2bd6"])
    # Two tilted phones bleeding off the right edge.
    for raw, width, angle, x, y in [("02_custom", 270, 16, 735, -40), ("01_hero", 290, 12, 575, 15)]:
        p = phone(RAW / f"{raw}.png", width).rotate(angle, Image.BICUBIC, expand=True)
        img.alpha_composite(p, (x, y))
    draw = ImageDraw.Draw(img)
    draw.text((64, 140), "metro launcher", font=font(HEAD, 60), fill=(255, 255, 255, 255))
    tag = font(BODY, 31)
    for i, line in enumerate(["Live tiles. Your photos.", "Almost no battery."]):
        draw.text((66, 232 + i * 46), line, font=tag, fill=(255, 255, 255, 225))
    img.convert("RGB").save(OUT / "feature_graphic.png", optimize=True)


def site_screens():
    site = OUT / "site"
    site.mkdir(parents=True, exist_ok=True)
    for raw, name in [(f[0], f[1].split("_", 1)[1]) for f in FRAMES] + SITE_EXTRA:
        p = phone(RAW / f"{raw}.png", 520)
        p.save(site / f"{name}.webp", quality=88, method=6)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for frame in FRAMES:
        store_frame(*frame)
    feature_graphic()
    site_screens()


if __name__ == "__main__":
    main()
