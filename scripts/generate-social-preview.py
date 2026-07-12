#!/usr/bin/env python3
"""Build docs/social-preview.png (1280x640) for Discord/GitHub Open Graph cards."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "social-preview.png"
SHOTS = ROOT / "docs" / "screenshots"
ICON = ROOT / "app" / "src" / "main" / "res" / "mipmap-xxxhdpi" / "ic_launcher.webp"

W, H = 1280, 640
BG = (15, 18, 24)
CARD = (28, 32, 40)
ACCENT = (56, 189, 248)
TEXT = (248, 250, 252)
MUTED = (148, 163, 184)


def load_font(size: int, bold: bool = False) -> ImageFont.ImageFont:
    candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
        if bold
        else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"
        if bold
        else "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    ]
    for path in candidates:
        if Path(path).is_file():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def phone_frame(im: Image.Image, max_h: int) -> Image.Image:
    """Scale tall screenshot and draw a simple rounded device chrome."""
    im = im.convert("RGBA")
    ratio = max_h / im.height
    nw, nh = max(1, int(im.width * ratio)), max(1, int(im.height * ratio))
    screen = im.resize((nw, nh), Image.Resampling.LANCZOS)

    pad = 10
    radius = 28
    frame_w, frame_h = nw + pad * 2, nh + pad * 2
    frame = Image.new("RGBA", (frame_w, frame_h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(frame)
    # device body
    draw.rounded_rectangle([0, 0, frame_w - 1, frame_h - 1], radius=radius, fill=(30, 30, 34, 255))
    # screen
    frame.paste(screen, (pad, pad), screen)
    # subtle border
    draw.rounded_rectangle(
        [0, 0, frame_w - 1, frame_h - 1],
        radius=radius,
        outline=(80, 80, 90, 255),
        width=2,
    )
    return frame


def drop_shadow(im: Image.Image, radius: int = 16, offset: tuple[int, int] = (0, 12)) -> Image.Image:
    ox, oy = offset
    pad = radius * 2 + max(abs(ox), abs(oy)) + 4
    canvas = Image.new("RGBA", (im.width + pad * 2, im.height + pad * 2), (0, 0, 0, 0))
    alpha = im.split()[-1] if im.mode == "RGBA" else Image.new("L", im.size, 255)
    sh = Image.new("RGBA", im.size, (0, 0, 0, 160))
    sh.putalpha(alpha)
    sh = sh.filter(ImageFilter.GaussianBlur(radius))
    canvas.paste(sh, (pad + ox, pad + oy), sh)
    canvas.paste(im, (pad, pad), im if im.mode == "RGBA" else None)
    return canvas


def main() -> None:
    base = Image.new("RGB", (W, H), BG)
    draw = ImageDraw.Draw(base)
    draw.rectangle([0, 0, 8, H], fill=ACCENT)

    icon_x, icon_y = 40, 36
    text_x = 40
    if ICON.is_file():
        icon = Image.open(ICON).convert("RGBA").resize((64, 64), Image.Resampling.LANCZOS)
        # circular-ish mask optional; paste square is fine
        base.paste(icon, (icon_x, icon_y), icon)
        text_x = icon_x + 80

    title_font = load_font(48, bold=True)
    sub_font = load_font(24, bold=False)
    draw.text((text_x, 40), "TextImageCleaner", font=title_font, fill=TEXT)
    draw.text(
        (text_x, 100),
        "Bulk-clean MMS photos & videos on Android 15+",
        font=sub_font,
        fill=MUTED,
    )

    names = [
        "01-cleaner-grid.png",
        "02-selection.png",
        "03-settings.png",
    ]
    phones: list[Image.Image] = []
    for name in names:
        path = SHOTS / name
        if path.is_file():
            phones.append(phone_frame(Image.open(path), max_h=420))

    if phones:
        # Lay phones left-to-right under the title, slightly overlapping
        total_w = sum(p.width for p in phones) - 40 * (len(phones) - 1)
        x = max(40, (W - total_w) // 2)
        y = 160
        for i, phone in enumerate(phones):
            shadowed = drop_shadow(phone)
            base.paste(shadowed, (x, y), shadowed)
            x += phone.width - 36

    draw.rectangle([0, H - 44, W, H], fill=CARD)
    foot = load_font(18, bold=False)
    draw.text(
        (40, H - 30),
        "github.com/LinkofHyrule89/TextImageCleaner",
        font=foot,
        fill=MUTED,
    )

    OUT.parent.mkdir(parents=True, exist_ok=True)
    base.save(OUT, "PNG", optimize=True)
    kb = OUT.stat().st_size / 1024
    print(f"Wrote {OUT} ({W}x{H}, {kb:.0f} KB)")


if __name__ == "__main__":
    main()
