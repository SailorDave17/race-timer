#!/usr/bin/env python3
"""Render the 512x512 Play Store listing icon from the launcher-icon geometry.

The geometry lives in store/mark.py, which is the single Python source of truth for the mark; this
file only decides how big it is and where it sits. It used to restate the geometry inline - see
mark.py for why that stopped.

    python store/render_store_icon.py          # needs Pillow: python -m pip install Pillow

Output: store/ic_launcher_512.png - 512x512 32-bit PNG, fully opaque, as the Play Console requires
for the store listing icon. The mark is composed the way a launcher shows it: the adaptive icon's
visible window is the central 72dp of the 108dp canvas, so canvas coordinates are mapped through
that window.

Extracting the geometry into mark.py was proven to change nothing: at that commit the re-render came
back byte-identical to the PNG already committed, because a refactor of a renderer that quietly moves
a pixel is invisible in review.

That check is now HISTORY rather than a live assertion - the scarlet-and-grey recolour changed this
output deliberately, the same day. The technique is the part worth keeping: record the hash before a
refactor you believe is inert, so a later intentional redesign breaks it loudly instead of hiding in
the same diff.
"""

from pathlib import Path

from PIL import Image, ImageDraw

from mark import GREY_FIELD, VISIBLE, draw_mark, transform

SIZE = 512
SS = 4  # supersample factor; drawn at SIZE*SS, downscaled with Lanczos


def main():
    img = Image.new("RGBA", (SIZE * SS, SIZE * SS), GREY_FIELD)
    draw = ImageDraw.Draw(img)

    to_px, scale = transform(SIZE * SS / VISIBLE, SIZE * SS / 2, SIZE * SS / 2)
    draw_mark(draw, to_px, scale)

    out = img.resize((SIZE, SIZE), Image.LANCZOS)
    dest = Path(__file__).parent / "ic_launcher_512.png"
    out.save(dest, "PNG")
    print(f"wrote {dest} ({dest.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
