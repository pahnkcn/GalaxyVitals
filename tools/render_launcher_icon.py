#!/usr/bin/env python3
"""Render GalaxyVitals launcher assets from the canonical branding asset."""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
BRANDING = ROOT / "branding" / "galaxyvitals-logo.png"
RES_ROOTS = (
    ROOT / "app" / "src" / "main" / "res",
    ROOT / "wear" / "src" / "main" / "res",
)

INK = (7, 16, 22, 255)
MINT = (46, 230, 200, 255)
MINT_CORE = (180, 255, 236, 255)
MINT_DIM = (26, 143, 124, 255)
AMBER = (245, 193, 108, 255)
AMBER_CORE = (255, 236, 190, 255)

# Adaptive icon coordinate space is 108dp; content stays inside 21–87.
VIEW = 108.0
SAFE = (21.0, 87.0)


def px(v: float, size: int) -> float:
    return v * size / VIEW


def mix(a: tuple[int, ...], b: tuple[int, ...], t: float) -> tuple[int, int, int, int]:
    return tuple(int(round(x + (y - x) * t)) for x, y in zip(a, b))  # type: ignore[return-value]


def star(cx: float, cy: float, r_out: float, r_in: float, n: int = 4, rot: float = -math.pi / 2):
    pts: list[tuple[float, float]] = []
    for i in range(n * 2):
        r = r_out if i % 2 == 0 else r_in
        a = rot + i * math.pi / n
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def ecg_points(size: int) -> list[tuple[float, float]]:
    # Watch-to-phone bridge: P, QRS, T — stays inside the bezel.
    raw = [
        (30.2, 54.0),
        (35.4, 54.0),
        (38.6, 47.2),
        (41.2, 59.8),
        (44.0, 52.6),
        (54.0, 37.0),
        (62.6, 70.4),
        (66.0, 52.4),
        (69.8, 46.8),
        (73.6, 54.0),
        (77.8, 54.0),
    ]
    return [(px(x, size), px(y, size)) for x, y in raw]


def draw_tick(draw: ImageDraw.ImageDraw, size: int, angle_deg: float, inner: float, outer: float, width: float, color):
    a = math.radians(angle_deg)
    cx, cy = px(54, size), px(54, size)
    x1 = cx + px(inner, size) * math.sin(a)
    y1 = cy - px(inner, size) * math.cos(a)
    x2 = cx + px(outer, size) * math.sin(a)
    y2 = cy - px(outer, size) * math.cos(a)
    draw.line([(x1, y1), (x2, y2)], fill=color, width=max(1, int(round(width))), joint="curve")


def stamp_circle(draw: ImageDraw.ImageDraw, cx: float, cy: float, r: float, fill, outline=None, ow=0):
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=fill, outline=outline, width=ow)


def render_mark(size: int, transparent: bool) -> Image.Image:
    base = (0, 0, 0, 0) if transparent else INK
    img = Image.new("RGBA", (size, size), base)
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ink = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    g = ImageDraw.Draw(glow)
    d = ImageDraw.Draw(ink)

    cx, cy = px(54, size), px(54, size)
    ring_r = px(26.2, size)
    stroke = max(2, int(round(px(2.5, size))))
    inner_stroke = max(1, int(round(px(1.0, size))))

    # Soft galaxy wash inside the bezel.
    wash = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    wdraw = ImageDraw.Draw(wash)
    stamp_circle(wdraw, cx, cy, ring_r * 0.88, (18, 70, 64, 42))
    wash = wash.filter(ImageFilter.GaussianBlur(radius=size * 0.03))
    img = Image.alpha_composite(img, wash)

    # Glow for ring + waveform + orbs.
    glow_stroke = max(stroke * 3, int(round(px(7.5, size))))
    g.ellipse(
        (cx - ring_r, cy - ring_r, cx + ring_r, cy + ring_r),
        outline=(46, 230, 200, 110),
        width=glow_stroke,
    )
    pts = ecg_points(size)
    g.line(pts, fill=(46, 230, 200, 140), width=glow_stroke, joint="curve")
    orb_r = px(2.7, size)
    stamp_circle(g, pts[0][0], pts[0][1], orb_r * 2.4, (46, 230, 200, 90))
    stamp_circle(g, pts[-1][0], pts[-1][1], orb_r * 2.4, (46, 230, 200, 90))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=size * 0.028))

    # Bezel + inner orbit.
    d.ellipse(
        (cx - ring_r, cy - ring_r, cx + ring_r, cy + ring_r),
        outline=MINT,
        width=stroke,
    )
    inner_r = px(21.6, size)
    d.ellipse(
        (cx - inner_r, cy - inner_r, cx + inner_r, cy + inner_r),
        outline=(*MINT_DIM[:3], 200),
        width=inner_stroke,
    )

    tick_w = max(1, int(round(px(0.85, size))))
    major_w = max(2, int(round(px(1.15, size))))
    for i in range(12):
        deg = i * 30
        if deg % 90 == 0:
            draw_tick(d, size, deg, 23.4, 26.5, major_w, MINT)
        else:
            draw_tick(d, size, deg, 24.2, 26.2, tick_w, (*MINT_DIM[:3], 230))

    # ECG bridge.
    wave_w = max(3, int(round(px(2.35, size))))
    core_w = max(2, int(round(px(1.15, size))))
    d.line(pts, fill=MINT, width=wave_w, joint="curve")
    d.line(pts, fill=MINT_CORE, width=core_w, joint="curve")
    joint_r = wave_w / 2
    for x, y in pts[1:-1]:
        stamp_circle(d, x, y, joint_r, MINT)

    stamp_circle(d, pts[0][0], pts[0][1], orb_r, MINT)
    stamp_circle(d, pts[-1][0], pts[-1][1], orb_r, MINT)
    stamp_circle(d, pts[0][0], pts[0][1], orb_r * 0.45, MINT_CORE)
    stamp_circle(d, pts[-1][0], pts[-1][1], orb_r * 0.45, MINT_CORE)

    # Amber spark at the R peak.
    peak = (px(54.0, size), px(33.6, size))
    spark = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    s = ImageDraw.Draw(spark)
    stamp_circle(s, peak[0], peak[1], px(3.4, size), (245, 193, 108, 80))
    spark = spark.filter(ImageFilter.GaussianBlur(radius=size * 0.012))
    s = ImageDraw.Draw(spark)
    s.polygon(star(peak[0], peak[1], px(3.15, size), px(1.05, size)), fill=AMBER)
    s.polygon(star(peak[0], peak[1], px(1.55, size), px(0.45, size)), fill=AMBER_CORE)

    # Two tiny field stars, kept inside the safe zone.
    for sx, sy, r in ((34.5, 35.8, 1.15), (74.0, 37.2, 0.95), (36.8, 73.4, 0.8)):
        d.polygon(star(px(sx, size), px(sy, size), px(r, size), px(r * 0.35, size)), fill=(*MINT[:3], 180))

    img = Image.alpha_composite(img, glow)
    img = Image.alpha_composite(img, spark)
    img = Image.alpha_composite(img, ink)
    return img


def fit_square(im: Image.Image, size: int) -> Image.Image:
    return im.resize((size, size), Image.Resampling.LANCZOS)


def render_branding(size: int, transparent: bool, max_fraction: float) -> Image.Image:
    if not BRANDING.is_file():
        raise FileNotFoundError(f"Missing canonical branding asset: {BRANDING}")

    source = Image.open(BRANDING).convert("RGBA")
    bbox = source.getchannel("A").getbbox()
    if bbox is not None:
        source = source.crop(bbox)
    source.thumbnail(
        (int(round(size * max_fraction)), int(round(size * max_fraction))),
        Image.Resampling.LANCZOS,
    )

    base = Image.new("RGBA", (size, size), (0, 0, 0, 0) if transparent else INK)
    x = (size - source.width) // 2
    y = (size - source.height) // 2
    base.alpha_composite(source, (x, y))
    return base


def save(im: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, "PNG", optimize=True)


def main() -> None:
    master = 1024
    fg = render_branding(master, transparent=True, max_fraction=0.62)
    full = render_branding(master, transparent=False, max_fraction=0.86)

    # 108dp at xxxhdpi (4x) is 432px — enough for the adaptive foreground.
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for res in RES_ROOTS:
        save(fit_square(fg, 432), res / "drawable-xxxhdpi" / "ic_launcher_foreground.png")
        for folder, edge in densities.items():
            icon = fit_square(full, edge)
            save(icon, res / folder / "ic_launcher.png")
            save(icon, res / folder / "ic_launcher_round.png")

    print("wrote launcher assets")


if __name__ == "__main__":
    main()
