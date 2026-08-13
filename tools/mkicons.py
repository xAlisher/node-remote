#!/usr/bin/env python3
"""
Render Lucide `blocks` into the two icon sets Node Remote needs.

Two different jobs, two different safe areas:

  Basecamp module icon — 64x64 RGBA, transparent background, near-white strokes. Matches
  radio_ui/icons/radio.png exactly (measured: 64x64 RGBA, bg (0,0,0,0), stroke ~#FFF5F5).

  Android launcher — adaptive icon. The canvas is 108dp but only the CENTRE 72dp is
  guaranteed visible; launchers mask the rest to circles, squircles, rounded squares.
  The glyph is therefore drawn inside 66% of the canvas, well clear of every mask.
  That is the "space from boundaries" the design needs, and it is a hard platform
  constraint rather than taste.

Lucide is stroke art on a 24x24 grid with stroke-width 2, round caps and joins — so the
renderer scales the stroke with the canvas rather than drawing hairlines.
"""
import math
import os
from PIL import Image, ImageDraw

# lucide blocks:
#   <path d="M10 22V7a1 1 0 0 0-1-1H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5a1 1 0 0 0-1-1H2"/>
#   <rect x="14" y="2" width="8" height="8" rx="1"/>
GRID = 24.0


def arc_pts(cx, cy, r, a0, a1, n=10):
    """Sample a circular arc; corner radii in the path are quarter-turns."""
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def blocks_polyline():
    """The big L-shaped path, flattened to a polyline in 24x24 grid units."""
    p = [(10, 22), (10, 8)]                       # down the right edge of the L, up to the r=1 corner
    p += arc_pts(9, 7, 1, 0, -90)                 # a1 corner at (9,7): 10,7 -> 9,6
    p += [(4 + 2, 6)]                             # along the top to the r=2 corner
    p += arc_pts(4, 8, 2, -90, -180)              # a2 corner: 6,6 -> 2,8
    p += [(2, 20 - 2 + 2)]                        # left edge down to the bottom-left corner
    p += arc_pts(4, 20, 2, 180, 90)               # 2,20 -> 4,22
    p += [(16, 22)]                               # along the bottom
    p += arc_pts(16, 20, 2, 90, 0)                # 16,22 -> 18,20
    p += [(18, 15)]                               # right edge up to the r=1 notch
    p += arc_pts(17, 14, 1, 0, -90)               # 18,14 -> 17,13
    p += [(2, 13)]                                # the crossbar back to the left edge
    return p


def rounded_rect(x, y, w, h, r):
    """The 8x8 rx=1 square, as a closed polyline."""
    pts = []
    pts += arc_pts(x + w - r, y + r, r, -90, 0)
    pts += arc_pts(x + w - r, y + h - r, r, 0, 90)
    pts += arc_pts(x + r, y + h - r, r, 90, 180)
    pts += arc_pts(x + r, y + r, r, 180, 270)
    pts.append(pts[0])
    return pts


def grid_2x2_polys():
    """lucide `grid-2x2`: a rounded 18x18 frame with a centred cross.
       <path d="M12 3v18"/> <path d="M3 12h18"/> <rect x=3 y=3 w=18 h=18 rx=2/>"""
    return [rounded_rect(3, 3, 18, 18, 2),
            [(12, 3), (12, 21)],
            [(3, 12), (21, 12)]]


def render(size, colour, coverage, supersample=8, polys=None):
    """Draw the glyph centred, occupying `coverage` of `size`. Supersampled for clean joins."""
    S = size * supersample
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    span = S * coverage
    scale = span / GRID
    off = (S - span) / 2.0
    w = max(1, int(round(2 * scale)))             # lucide stroke-width 2, scaled

    def T(pts):
        return [(off + x * scale, off + y * scale) for x, y in pts]

    for poly in (polys if polys is not None else (blocks_polyline(), rounded_rect(14, 2, 8, 8, 1))):
        pts = T(poly)
        d.line(pts, fill=colour, width=w, joint="curve")
        # joint="curve" does not round the two free ENDS, and lucide is stroke-linecap
        # round — so cap them by hand or the L-path terminates in visible square stubs.
        r = w / 2.0
        for cx, cy in (pts[0], pts[-1]):
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=colour)

    return img.resize((size, size), Image.LANCZOS)


# ── Basecamp module icon ────────────────────────────────────────────────────────
# 64x64, transparent, near-white — byte-compatible with the radio_ui convention.
render(64, (255, 245, 245, 255), 0.72).save(
    "/home/alisher/basecamp/modules/node-remote/node-remote-bc/node_remote_ui/icons/node-remote.png")

# ── Android adaptive launcher icon ──────────────────────────────────────────────
# Foreground only; the background is a flat colour resource, as in Peers.
# 0.36, down from 0.44: 0.44 technically cleared the 72/108 safe circle but read as
# cramped against the mask edge on a real launcher. Adaptive icons are forgiving of a
# glyph that is too small and unforgiving of one that is too large.
for name, px in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
                 ("xxhdpi", 324), ("xxxhdpi", 432)]:
    render(px, (255, 255, 255, 255), 0.36).save(
        f"/home/alisher/basecamp/modules/node-remote/node-remote-android/app/src/main/res/"
        f"mipmap-{name}/ic_launcher_foreground.png")

# Legacy square/round icons for pre-API-26 launchers: same glyph, on the background
# colour, with the legacy 4/48 padding already baked in.
for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                 ("xxhdpi", 144), ("xxxhdpi", 192)]:
    base = Image.new("RGBA", (px, px), (10, 10, 10, 255))
    base.alpha_composite(render(px, (255, 255, 255, 255), 0.46))
    d = f"/home/alisher/basecamp/modules/node-remote/node-remote-android/app/src/main/res/mipmap-{name}"
    base.save(f"{d}/ic_launcher.png")

    rnd = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    m = Image.new("L", (px * 4, px * 4), 0)
    ImageDraw.Draw(m).ellipse([0, 0, px * 4 - 1, px * 4 - 1], fill=255)
    rnd.paste(base, (0, 0), m.resize((px, px), Image.LANCZOS))
    rnd.save(f"{d}/ic_launcher_round.png")

print("icons written")


# ── Blockchain node (logos-blockchain-ui) module icon ───────────────────────────
# lucide `grid-2x2`, same treatment as Node Remote's `blocks`: 64x64, transparent,
# near-white. The two panes sit side by side in Basecamp, so they are generated from one
# script rather than each being drawn its own way — the previous icon was 28x28, which
# rendered soft next to a 64x64 neighbour.
_click = "/home/alisher/basecamp/modules/logos-blockchain-ui/src/icons/blockchain.png"
if os.path.isdir(os.path.dirname(_click)):
    render(64, (255, 245, 245, 255), 0.72, polys=grid_2x2_polys()).save(_click)
    print("blockchain node icon written:", _click)
