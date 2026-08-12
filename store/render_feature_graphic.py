#!/usr/bin/env python3
"""Render the 1024x500 Play Store feature graphic.

    python store/render_feature_graphic.py     # needs Pillow: python -m pip install Pillow

Output: store/feature_graphic_1024x500.png - exactly 1024x500, mode RGB, so the PNG carries no
alpha channel. Play rejects a feature graphic with transparency, and the failure arrives as a
Console upload error rather than anything visible in the image, so the mode is asserted here rather
than trusted.

The mark comes from store/mark.py, the same geometry the launcher icon and the 512 store icon draw,
which is what makes "consistent with the app icon" a structural property instead of something judged
by eye and re-judged every time either file changes.

Composition, and the constraints behind it:

  - Play crops and overlays this image differently across surfaces, so nothing that carries meaning
    sits within 10% of any edge. The script ASSERTS this rather than leaving it to the layout: every
    drawn element's bounding box is checked against the safe rectangle before the file is written.
  - A play button can be superimposed over the centre when a promo video is attached. There is no
    promo video, so this is a latent rather than live constraint - but the mark is left of centre and
    the copy right of it, so a centred overlay lands in the gap between them rather than on either.
  - Text is drawn at final size while the mark is supersampled and downscaled. Downscaling glyph
    rendering softens it; the mark has no such problem and wants the smoothing.
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from mark import (CONTENT_BOTTOM, CONTENT_LEFT, CONTENT_RIGHT, CONTENT_TOP,
                  GREY_FIELD, GREY_LIGHT, SCARLET, WHITE, draw_mark, transform)

# Palette comes from mark.py so the icon and this banner cannot drift apart.

W, H = 1024, 500
SS = 4                      # supersample factor for the mark layer only
SAFE = 0.10                 # keep meaning out of the outer 10% on every edge

MARK_LAYER = 400            # square scratch canvas the mark is drawn into, final px
MARK_HEIGHT = 280           # how tall the drawn mark should end up, final px
MARK_CENTRE = (235, 250)    # where the mark's artwork centre lands on the graphic

TEXT_X = 400
TEXT_RIGHT = 890            # the widest element may reach here and still clear the crop margin
TITLE = "Mad Cow Race Timer"
TITLE_SIZE = 52           # 56 overshoots; the assertion below is what settles it
TITLE_Y = 178

# A promise, not a feature. Two earlier drafts were weaker for the same reason: "The start sequence,
# on your wrist" described the whole category (Regatta Timer, Regatta Racer and Wear Sailing Race
# Timer are all on your wrist), and "Every start. No phone on the water." named a mechanism rather
# than what it buys you. Being late off the line is the failure every racing sailor already fears,
# so the banner names that instead - the strip beside it shows how, and this says why.
TAGLINE = "Never late to the start."
TAGLINE_SIZE = 32           # a shorter line buys size back; 28 went soft at 160px, and this clears it
TAGLINE_Y = 228

# The 5-4-1-Go sequence, spaced by the time it actually takes rather than evenly. The long gap
# between the prep signal and the one-minute is the shape a sailor already knows, so the strip reads
# as a start sequence rather than as decoration - which is what turns this from a picture of a boat
# into a picture of a start.
SEQUENCE = [("5:00", 5), ("4:00", 4), ("1:00", 1), ("GO", 0)]
SEQUENCE_MINUTES = 5
STRIP_Y = 308
STRIP_TICK = 9
STRIP_LABEL_Y = 336
STRIP_LABEL_SIZE = 21

# Bold first: the title carries the graphic at thumbnail size. A missing font is a hard stop rather
# than a silent fallback to PIL's bitmap default, which would render the title unreadably small and
# still write a file.
FONT_CANDIDATES = [
    "C:/Windows/Fonts/segoeuib.ttf",
    "C:/Windows/Fonts/arialbd.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def load_font(size):
    for path in FONT_CANDIDATES:
        try:
            return ImageFont.truetype(path, size), path
        except OSError:
            continue
    raise SystemExit(
        "no bold font found - tried:\n  " + "\n  ".join(FONT_CANDIDATES) +
        "\nAdd one for this platform rather than letting PIL fall back to its bitmap default."
    )


def safe_rect():
    return (W * SAFE, H * SAFE, W * (1 - SAFE), H * (1 - SAFE))


def check_inside(name, box, boxes):
    """Record a drawn element's bounds, and fail if it strays into the crop margin."""
    sx0, sy0, sx1, sy1 = safe_rect()
    x0, y0, x1, y1 = box
    boxes.append((name, box))
    if x0 < sx0 or y0 < sy0 or x1 > sx1 or y1 > sy1:
        raise SystemExit(
            f"{name} at {tuple(round(v) for v in box)} leaves the safe area "
            f"{tuple(round(v) for v in safe_rect())} - Play may crop it"
        )


def render_mark_layer():
    """Draw the mark into its own transparent square, supersampled, and return it downscaled."""
    px_per_dp = MARK_HEIGHT / (CONTENT_BOTTOM - CONTENT_TOP)
    art_cx = (CONTENT_LEFT + CONTENT_RIGHT) / 2
    art_cy = (CONTENT_TOP + CONTENT_BOTTOM) / 2

    layer = Image.new("RGBA", (MARK_LAYER * SS, MARK_LAYER * SS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    # Put the artwork's centre at the layer's centre. Canvas (54, 54) is NOT that centre - the crown
    # sits above the bezel and the waterline below it - which is the whole reason mark.py exports
    # CONTENT_* separately from the bezel.
    centre = MARK_LAYER * SS / 2
    to_px, scale = transform(
        px_per_dp * SS,
        centre + (54 - art_cx) * px_per_dp * SS,
        centre + (54 - art_cy) * px_per_dp * SS,
    )
    draw_mark(draw, to_px, scale, bezel=SCARLET, sail=WHITE)

    art_w = (CONTENT_RIGHT - CONTENT_LEFT) * px_per_dp
    return layer.resize((MARK_LAYER, MARK_LAYER), Image.LANCZOS), art_w


def draw_sequence_strip(draw, boxes):
    """The start sequence as a timeline: scarlet rule, a tick per signal, GO picked out in white."""
    font, _ = load_font(STRIP_LABEL_SIZE)
    width = TEXT_RIGHT - TEXT_X

    draw.line((TEXT_X, STRIP_Y, TEXT_RIGHT, STRIP_Y), fill=SCARLET[:3], width=3)

    for label, minutes in SEQUENCE:
        x = TEXT_X + (SEQUENCE_MINUTES - minutes) / SEQUENCE_MINUTES * width
        draw.line((x, STRIP_Y - STRIP_TICK, x, STRIP_Y + STRIP_TICK), fill=SCARLET[:3], width=3)
        # GO is the gun. It is the only mark on the strip that is not a warning about a later one.
        colour = WHITE if label == "GO" else GREY_LIGHT
        draw.text((x, STRIP_LABEL_Y), label, font=font, fill=colour[:3], anchor="mm")
        check_inside(
            f"strip {label}",
            draw.textbbox((x, STRIP_LABEL_Y), label, font=font, anchor="mm"),
            boxes,
        )

    check_inside("strip rule", (TEXT_X, STRIP_Y - STRIP_TICK, TEXT_RIGHT, STRIP_Y + STRIP_TICK), boxes)


def main():
    img = Image.new("RGB", (W, H), GREY_FIELD[:3])
    draw = ImageDraw.Draw(img)
    boxes = []

    layer, art_w = render_mark_layer()
    img.paste(layer, (MARK_CENTRE[0] - MARK_LAYER // 2, MARK_CENTRE[1] - MARK_LAYER // 2), layer)
    check_inside("mark", (
        MARK_CENTRE[0] - art_w / 2, MARK_CENTRE[1] - MARK_HEIGHT / 2,
        MARK_CENTRE[0] + art_w / 2, MARK_CENTRE[1] + MARK_HEIGHT / 2,
    ), boxes)

    title_font, font_path = load_font(TITLE_SIZE)
    tagline_font, _ = load_font(TAGLINE_SIZE)

    draw.text((TEXT_X, TITLE_Y), TITLE, font=title_font, fill=WHITE[:3], anchor="lm")
    check_inside("title", draw.textbbox((TEXT_X, TITLE_Y), TITLE, font=title_font, anchor="lm"), boxes)

    draw.text((TEXT_X, TAGLINE_Y), TAGLINE, font=tagline_font, fill=GREY_LIGHT[:3], anchor="lm")
    check_inside("tagline", draw.textbbox((TEXT_X, TAGLINE_Y), TAGLINE, font=tagline_font, anchor="lm"), boxes)

    draw_sequence_strip(draw, boxes)

    if img.mode != "RGB":
        raise SystemExit(f"image mode is {img.mode}, not RGB - a feature graphic must carry no alpha")
    if img.size != (W, H):
        raise SystemExit(f"image is {img.size}, not ({W}, {H})")

    dest = Path(__file__).parent / "feature_graphic_1024x500.png"
    img.save(dest, "PNG")

    print(f"wrote {dest} ({dest.stat().st_size} bytes)")
    print(f"  font: {font_path}")
    print(f"  safe area: {tuple(round(v) for v in safe_rect())}")
    for name, box in boxes:
        print(f"  {name:8} {tuple(round(v) for v in box)}")


if __name__ == "__main__":
    main()
