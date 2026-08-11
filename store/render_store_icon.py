#!/usr/bin/env python3
"""Render the 512x512 Play Store listing icon from the launcher-icon geometry.

Single source of truth for the mark is the adaptive-icon vector pair:
  wear/src/main/res/drawable/ic_launcher_background.xml  (full-bleed #1A1A2E)
  wear/src/main/res/drawable/ic_launcher_foreground.xml  (stopwatch mark)
This script re-states that geometry in the GEOMETRY block below — if the vector
changes, change this file and re-run it:

    python store/render_store_icon.py

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

# --- GEOMETRY: mirrors ic_launcher_foreground.xml (108dp canvas, center 54,54) ---
FACE_R = 27          # white ring, outer radius
DIAL_R = 22          # navy dial
CROWN = (49, 22, 59, 28)   # x0, y0, x1, y1
MINUTE = ((54, 54), (54, 35))  # white, stroke 5, round caps
HOUR = ((54, 54), (43, 46))    # gold, stroke 5, round caps
STROKE = 5
PIVOT_R = 3.5        # gold center dot
VISIBLE = 72         # dp of the 108dp canvas a launcher actually shows


def to_px(x, y):
    """Map a 108dp-canvas coordinate through the 72dp visible window to pixels."""
    s = SIZE * SS / VISIBLE
    return ((x - 54) * s + SIZE * SS / 2, (y - 54) * s + SIZE * SS / 2)


def scale(v):
    return v * SIZE * SS / VISIBLE


def circle(draw, cx, cy, r, fill):
    x, y = to_px(cx, cy)
    rp = scale(r)
    draw.ellipse((x - rp, y - rp, x + rp, y + rp), fill=fill)


def capped_line(draw, p0, p1, width, fill):
    a, b = to_px(*p0), to_px(*p1)
    w = scale(width)
    draw.line((a, b), fill=fill, width=round(w))
    for x, y in (a, b):
        draw.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=fill)


def main():
    img = Image.new("RGBA", (SIZE * SS, SIZE * SS), NAVY)
    draw = ImageDraw.Draw(img)

    circle(draw, 54, 54, FACE_R, WHITE)
    circle(draw, 54, 54, DIAL_R, NAVY)
    x0, y0 = to_px(CROWN[0], CROWN[1])
    x1, y1 = to_px(CROWN[2], CROWN[3])
    draw.rectangle((x0, y0, x1, y1), fill=WHITE)
    capped_line(draw, *MINUTE, STROKE, WHITE)
    capped_line(draw, *HOUR, STROKE, GOLD)
    circle(draw, 54, 54, PIVOT_R, GOLD)

    out = img.resize((SIZE, SIZE), Image.LANCZOS)
    dest = Path(__file__).parent / "ic_launcher_512.png"
    out.save(dest, "PNG")
    print(f"wrote {dest} ({dest.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
