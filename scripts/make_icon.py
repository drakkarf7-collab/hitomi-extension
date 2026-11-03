"""Generates the launcher icons (run once, the PNGs are committed). Requires Pillow."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

RES = Path(__file__).resolve().parents[1] / "hitomi" / "src" / "main" / "res"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def draw(size: int) -> Image.Image:
    scale = 4
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, s - 1, s - 1), radius=s // 5, fill=(232, 62, 140, 255))
    try:
        font = ImageFont.truetype("arialbd.ttf", int(s * 0.62))
    except OSError:
        font = ImageFont.load_default(size=int(s * 0.62))
    box = d.textbbox((0, 0), "H", font=font)
    w, h = box[2] - box[0], box[3] - box[1]
    d.text(((s - w) / 2 - box[0], (s - h) / 2 - box[1]), "H", font=font, fill=(255, 255, 255, 255))
    return img.resize((size, size), Image.LANCZOS)


for density, size in SIZES.items():
    out = RES / f"mipmap-{density}" / "ic_launcher.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    draw(size).save(out)
    print(out)
