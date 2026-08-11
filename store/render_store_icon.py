#!/usr/bin/env python3
"""Render the 512x512 Play Store listing icon from the launcher-icon geometry.

Single source of truth for the mark is the adaptive-icon vector pair:
  wear/src/main/res/drawable/ic_launcher_background.xml  (full-bleed #1A1A2E)
  wear/src/main/res/drawable/ic_launcher_foreground.xml  (sail plan in a stopwatch bezel)
This script re-states that geometry in the GEOMETRY block below — if the vector
changes, change this file and re-run it:

    python store/render_store_icon.py          # needs Pillow: python -m pip install Pillow

Output: store/ic_launcher_512.png — 512x512 32-bit PNG, fully opaque, as the
Play Console requires for the store listing icon. The mark is composed the way
a launcher shows it: the adaptive icon's visible window is the central 72dp of
the 108dp canvas, so canvas coordinates are mapped through that window.
"""

from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 512
SS = 4  # supersample factor; drawn at SIZE*SS, downscaled with Lanczos

NAVY = (0x1A, 0x1A, 0x2E, 255)
WHITE = (0xFF, 0xFF, 0xFF, 255)
GOLD = (0xFF, 0xD7, 0x00, 255)

# --- GEOMETRY: mirrors ic_launcher_foreground.xml (108dp canvas, centre 54,54) ---
BEZEL_R = 30
BEZEL_STROKE = 4.5
CROWN = (50, 22, 58, 28)
# Sails as (start, corner, corner, quadratic control) — the curved edge closes back to start.
MAINSAIL = ((57, 32), (55, 70), (72, 70), (71, 46))
JIB = ((50, 44), (51, 70), (37, 70), (41, 55))
WATERLINE = ((39, 74.5), (69, 74.5))
WATERLINE_STROKE = 3.5
VISIBLE = 72  # dp of the 108dp canvas a launcher actually shows


def to_px(x, y):
    """Map a 108dp-canvas coordinate through the 72dp visible window to pixels."""
    s = SIZE * SS / VISIBLE
    return ((x - 54) * s + SIZE * SS / 2, (y - 54) * s + SIZE * SS / 2)


def scale(v):
    return v * SIZE * SS / VISIBLE


def ring(draw, cx, cy, r, width, fill):
    x, y = to_px(cx, cy)
    rp, w = scale(r), scale(width)
    draw.ellipse((x - rp, y - rp, x + rp, y + rp), outline=fill, width=round(w))


def quad(p0, ctrl, p1, steps=64):
    """Sample a quadratic bezier, matching the vector's Q command."""
    pts = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        pts.append((
            u * u * p0[0] + 2 * u * t * ctrl[0] + t * t * p1[0],
            u * u * p0[1] + 2 * u * t * ctrl[1] + t * t * p1[1],
        ))
    return pts


def sail(draw, spec, fill):
    apex, heel, clew, ctrl = spec
    pts = [apex, heel, clew] + quad(clew, ctrl, apex)
    draw.polygon([to_px(*p) for p in pts], fill=fill)


def capped_line(draw, p0, p1, width, fill):
    a, b = to_px(*p0), to_px(*p1)
    w = scale(width)
    draw.line((a, b), fill=fill, width=round(w))
    for x, y in (a, b):
        draw.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=fill)


def main():
    img = Image.new("RGBA", (SIZE * SS, SIZE * SS), NAVY)
    draw = ImageDraw.Draw(img)

    ring(draw, 54, 54, BEZEL_R, BEZEL_STROKE, GOLD)
    x0, y0 = to_px(CROWN[0], CROWN[1])
    x1, y1 = to_px(CROWN[2], CROWN[3])
    draw.rectangle((x0, y0, x1, y1), fill=WHITE)
    sail(draw, MAINSAIL, WHITE)
    sail(draw, JIB, WHITE)
    capped_line(draw, *WATERLINE, WATERLINE_STROKE, GOLD)

    out = img.resize((SIZE, SIZE), Image.LANCZOS)
    dest = Path(__file__).parent / "ic_launcher_512.png"
    out.save(dest, "PNG")
    print(f"wrote {dest} ({dest.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
