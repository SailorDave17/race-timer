"""The Race Timer mark: a sloop's sail plan inside a stopwatch bezel.

**This is the single Python source of truth for the geometry.** It existed as a comment-bound copy
inside render_store_icon.py until the feature graphic needed the same mark; a second restatement
would have made it four copies of one shape held together by nothing but a note asking people to
keep them in step. Anything that draws the mark imports from here.

The remaining copies are the Android vector pair, which is what actually ships in the app:

    wear/src/main/res/drawable/ic_launcher_background.xml   (full-bleed charcoal)
    wear/src/main/res/drawable/ic_launcher_foreground.xml   (the sail plan in its bezel)

Those cannot import this file, so the coupling there is still by hand: **if the vector changes,
change the GEOMETRY block below and re-run both renderers.** The design reasoning - why a sloop's
two sails do the job a stopwatch's two hands would - is recorded in cairn at
memory/projects/race-timer-play-store-release-2026-08-01.md.

Coordinates are the adaptive icon's 108dp canvas, centre (54, 54).
"""

# --- PALETTE: scarlet and grey (owner decision, 2026-08-12) ---
# Ohio State's pair. Scarlet is PMS 200, #BB0000. The field is a charcoal rather than OSU's #666666
# because the sails are the shape doing the work and white on #666666 measures about 3:1.
#
# This replaced navy #1A1A2E and gold #FFD700. Two consequences worth knowing:
#   - The icon background no longer matches the app's in-race base colour (colors.xml bg_normal is
#     still #1A1A2E). That tie was deliberate once and is now broken deliberately; the UI is
#     unchanged, and only the icon and the store artwork moved.
#   - Scarlet sits next to colors.xml bg_final_ten (#8B0000), the "final ten seconds" alarm. Nothing
#     collides today, because an icon is never on screen during a race - but if this scheme is ever
#     taken into the countdown UI, that alarm state stops being distinctive. Read that as a
#     constraint on any future theme change rather than a defect here.
SCARLET = (0xBB, 0x00, 0x00, 255)
GREY_FIELD = (0x2E, 0x2E, 0x2E, 255)
GREY_LIGHT = (0xB8, 0xBD, 0xC0, 255)
WHITE = (0xFF, 0xFF, 0xFF, 255)

# --- GEOMETRY: mirrors ic_launcher_foreground.xml ---
BEZEL_R = 30
BEZEL_STROKE = 4.5
CROWN = (50, 22, 58, 28)
# Sails as (start, corner, corner, quadratic control) - the curved edge closes back to start.
MAINSAIL = ((57, 32), (55, 70), (72, 70), (71, 46))
JIB = ((50, 44), (51, 70), (37, 70), (41, 55))
WATERLINE = ((39, 74.5), (69, 74.5))
WATERLINE_STROKE = 3.5

VISIBLE = 72  # dp of the 108dp canvas a launcher actually shows

# What the drawn mark actually occupies, in canvas dp. Not the same as the bezel: the crown sits
# above it and the waterline below. Used to place and size the mark somewhere other than a square
# icon, where "centre the circle" and "centre the artwork" are different requests.
CONTENT_TOP = CROWN[1]                                  # 22
CONTENT_BOTTOM = WATERLINE[0][1] + WATERLINE_STROKE / 2  # 76.25
CONTENT_LEFT = JIB[2][0] - WATERLINE_STROKE / 2          # 35.25
CONTENT_RIGHT = MAINSAIL[2][0]                           # 72


def transform(px_per_dp, cx_px, cy_px):
    """Map 108dp-canvas coordinates to pixels, with canvas (54, 54) landing on (cx_px, cy_px).

    Returns (to_px, scale). Callers keep their own supersampling: pass px_per_dp already multiplied
    by the supersample factor and the primitives below follow automatically.
    """

    def to_px(x, y):
        return ((x - 54) * px_per_dp + cx_px, (y - 54) * px_per_dp + cy_px)

    def scale(v):
        return v * px_per_dp

    return to_px, scale


def _ring(draw, to_px, scale, cx, cy, r, width, fill):
    x, y = to_px(cx, cy)
    rp, w = scale(r), scale(width)
    draw.ellipse((x - rp, y - rp, x + rp, y + rp), outline=fill, width=round(w))


def _quad(p0, ctrl, p1, steps=64):
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


def _sail(draw, to_px, spec, fill):
    apex, heel, clew, ctrl = spec
    pts = [apex, heel, clew] + _quad(clew, ctrl, apex)
    draw.polygon([to_px(*p) for p in pts], fill=fill)


def _capped_line(draw, to_px, scale, p0, p1, width, fill):
    a, b = to_px(*p0), to_px(*p1)
    w = scale(width)
    draw.line((a, b), fill=fill, width=round(w))
    for x, y in (a, b):
        draw.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=fill)


def draw_mark(draw, to_px, scale, bezel=SCARLET, sail=WHITE):
    """Draw the complete mark. Order matters: sails overlay the bezel, waterline overlays the sails.

    The colours are parameters so a surface can recolour the mark **without** touching the geometry
    or the shipped icon. The launcher icon and the 512 store icon take the defaults and must keep
    taking them: they are what the APK carries, and changing them here would silently restyle the
    installed app. A caller passing something else is choosing to diverge, and should say why.
    """
    _ring(draw, to_px, scale, 54, 54, BEZEL_R, BEZEL_STROKE, bezel)
    x0, y0 = to_px(CROWN[0], CROWN[1])
    x1, y1 = to_px(CROWN[2], CROWN[3])
    draw.rectangle((x0, y0, x1, y1), fill=sail)
    _sail(draw, to_px, MAINSAIL, sail)
    _sail(draw, to_px, JIB, sail)
    _capped_line(draw, to_px, scale, *WATERLINE, WATERLINE_STROKE, bezel)
