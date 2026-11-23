"""Render the app icon and TV banner as PNGs (drawn at 4x, then downsampled for smooth edges)."""
import os
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

RES = sys.argv[1]
S = 4  # supersampling factor

TOP = (58, 32, 140)      # deep indigo
BOTTOM = (124, 77, 255)  # violet
ACCENT = (255, 255, 255)
GLOW = (190, 170, 255)


def gradient(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (0.65 * y / h + 0.35 * x / w)
            px[x, y] = tuple(int(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3))
    return img


def mark(size):
    """A cloud built from circles, with an equaliser of rounded bars knocked out of it."""
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    u = size / 100
    # cloud body
    d.ellipse([14 * u, 40 * u, 46 * u, 72 * u], fill=255)
    d.ellipse([30 * u, 22 * u, 70 * u, 62 * u], fill=255)
    d.ellipse([56 * u, 36 * u, 88 * u, 68 * u], fill=255)
    d.rounded_rectangle([22 * u, 50 * u, 80 * u, 72 * u], radius=11 * u, fill=255)
    # equaliser bars cut out of the cloud
    bars = [(31, 8), (40, 14), (50, 19), (60, 12), (69, 6)]
    for cx, half in bars:
        d.rounded_rectangle([(cx - 3) * u, (52 - half) * u, (cx + 3) * u, (52 + half) * u], radius=3 * u, fill=0)
    return m


def icon(px):
    big = px * S
    bg = gradient(big, big)
    m = mark(int(big * 0.78))
    off = (big - m.size[0]) // 2, int(big * 0.5 - m.size[1] * 0.47)
    glow = Image.new("L", (big, big), 0)
    glow.paste(m, off)
    glow = glow.filter(ImageFilter.GaussianBlur(big * 0.03))
    bg.paste(Image.new("RGB", (big, big), GLOW), (0, 0), glow.point(lambda v: v * 0.45))
    bg.paste(Image.new("RGB", m.size, ACCENT), off, m)
    # rounded-square shape so it looks right where the launcher doesn't mask icons
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.22), fill=255)
    out = Image.new("RGBA", (big, big))
    out.paste(bg, (0, 0), mask)
    return out.resize((px, px), Image.LANCZOS)


def banner(w, h):
    W, H = w * S, h * S
    bg = gradient(W, H)
    m = mark(int(H * 0.72))
    off = (int(W * 0.04), (H - m.size[1]) // 2 + int(H * 0.02))
    glow = Image.new("L", (W, H), 0)
    glow.paste(m, off)
    glow = glow.filter(ImageFilter.GaussianBlur(H * 0.04))
    bg.paste(Image.new("RGB", (W, H), GLOW), (0, 0), glow.point(lambda v: v * 0.45))
    bg.paste(Image.new("RGB", m.size, ACCENT), off, m)
    d = ImageDraw.Draw(bg)
    font_path = "/System/Library/Fonts/Avenir Next.ttc"
    title = ImageFont.truetype(font_path, int(H * 0.19), index=2)   # Demi Bold
    sub = ImageFont.truetype(font_path, int(H * 0.13), index=0)     # Regular
    x = off[0] + m.size[0] + int(W * 0.015)
    d.text((x, int(H * 0.27)), "Mixcloud", font=title, fill=ACCENT)
    d.text((x + int(W * 0.004), int(H * 0.53)), "for TV", font=sub, fill=(225, 215, 255))
    return bg.resize((w, h), Image.LANCZOS)


def save(img, rel):
    path = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print(path, img.size)


save(banner(640, 360), "drawable-xhdpi/banner.png")
for folder, px in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
    save(icon(px), f"{folder}/ic_launcher.png")
save(icon(512), "../../../../build/icon-preview-512.png")
