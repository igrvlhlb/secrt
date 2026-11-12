#!/usr/bin/env python3
"""Generates the app icon: a closed diary with a flat padlock, top-right corner disintegrating.

usage: python3 tools/icon/gen_icon.py OUT.{svg,xml} [seed] [feature ...]

The output format follows the extension: .svg (the whole icon: desktop window icon, previews) and
.xml (Android foreground layer).
Seed defaults to 5. Features default to border + ribbon; others: strap, pages, all, none.
"square" draws the tile without rounded corners, for the Google Play listing icon (Play rounds it itself).
"""
import math
import os
import random
import sys

OUT = sys.argv[1]
SEED = int(sys.argv[2]) if len(sys.argv) > 2 else 5
FEATURES = set(sys.argv[3:])
if not FEATURES - {"square"}:
    FEATURES |= {"border", "ribbon"}   # the chosen look; "none" turns all off
if "all" in FEATURES:
    FEATURES |= {"strap", "ribbon", "pages", "border"}

INK = "#4A5C92"      # app primary / icon background
PAPER = "#FBF8F3"    # app background / book cover
SPINE_C = "#B8C5F0"  # spine band: a light tint of the ink
TINT = "#DBE1FF"     # primaryContainer, for some of the flying chunks
TAN = "#EDBD92"      # app secondary container: strap
TAN_D = "#C98B5B"    # a darker tan: ribbon
MID = "#9DAEE6"      # between ink and tint, for the farthest chunks

# --- Book geometry (108x108 grid; Android adaptive-icon safe zone is the central 66x66) ---
C = 2                          # cell size of the pixel grid
COLS, ROWS = 23, 27            # 46 x 54
X0, Y0 = 54 - COLS * C // 2, 54 - ROWS * C // 2   # book centered on the tile; the dust may overflow
X1, Y1 = X0 + COLS * C, Y0 + ROWS * C
R = 4                          # radius of the three intact corners
SPINE = 6                      # spine band width

BITE = 9.0                     # size of the eaten corner, in cells along the diagonal
DIR = (0.78, -0.63)            # direction the dust drifts (up and to the right)

rnd = random.Random(SEED)


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def u_of(i, j):
    """Manhattan distance, in cells, from the top-right corner."""
    return (COLS - 1 - i) + j


def poly(cx, cy, w, h, rot):
    a = math.radians(rot)
    pts = []
    for dx, dy in ((-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)):
        pts.append((cx + dx * math.cos(a) - dy * math.sin(a), cy + dx * math.sin(a) + dy * math.cos(a)))
    return "M" + "L".join(f"{fmt(x)},{fmt(y)}" for x, y in pts) + "z"


# ---- Which cells are gone, per column (the kept part of a column is contiguous down to the bottom) ----
ytop = []                      # first kept row of each column
removed = []                   # (i, j) of every cell eaten off the book
for i in range(COLS):
    top = 0
    while u_of(i, top) + rnd.uniform(-1.4, 1.4) < BITE:
        top += 1
    ytop.append(top)

# crumbling edge: sometimes the first kept cell of a ragged column has already come loose too
loose_edge = []
for i in range(COLS):
    if ytop[i] > 0 and rnd.random() < 0.45:
        loose_edge.append((i, ytop[i]))
        ytop[i] += 1

for i in range(COLS):
    for j in range(ytop[i]):
        removed.append((i, j))


def book_path():
    d = [f"M{X0},{Y0 + R}", f"a{R},{R} 0 0 1 {R},{-R}"]
    x, y = X0 + R, Y0
    for i in range(COLS):
        top = Y0 + ytop[i] * C
        cx0 = X0 + i * C
        cx1 = cx0 + C
        if top != y:
            if cx0 > x:
                d.append(f"H{fmt(cx0)}")
                x = cx0
            d.append(f"V{fmt(top)}")
            y = top
        d.append(f"H{fmt(cx1)}")
        x = cx1
    d.append(f"V{Y1 - R}")
    d.append(f"a{R},{R} 0 0 1 {-R},{R}")
    d.append(f"H{X0 + R}")
    d.append(f"a{R},{R} 0 0 1 {-R},{-R}")
    d.append("z")
    return "".join(d)


def spine_path():
    return (f"M{X0 + R},{Y0}h{SPINE - R}v{ROWS * C}h{-(SPINE - R)}"
            f"a{R},{R} 0 0 1 {-R},{-R}V{Y0 + R}a{R},{R} 0 0 1 {R},{-R}z")


def dust():
    """Every eaten cell may drift away: the closer to the corner it was, the farther it goes."""
    out = []
    cells = [(i, j, True) for (i, j) in removed] + [(i, j, False) for (i, j) in loose_edge]
    for i, j, eaten in cells:
        if eaten and rnd.random() > 0.62:        # not every cell shows up as a chunk
            continue
        u = u_of(i, j)
        depth = max(0.0, BITE - u)               # how deep inside the bite it was
        cx0 = X0 + i * C + C / 2
        cy0 = Y0 + j * C + C / 2
        if eaten:
            dist = 3.5 + 1.9 * depth + rnd.uniform(0, 5)
        else:
            dist = rnd.uniform(1.2, 2.6)         # just cracked loose, hovering at the edge
        lat = rnd.uniform(-1, 1) * (0.8 + 0.35 * dist)
        px, py = -DIR[1], DIR[0]                 # perpendicular
        cx = cx0 + DIR[0] * dist + px * lat
        cy = cy0 + DIR[1] * dist + py * lat
        far = min(1.0, dist / 22)
        size = max(1.5, C * (1.35 - 0.8 * far) * rnd.uniform(0.8, 1.25))
        rot = rnd.uniform(-45, 45) * (0.25 + far)
        opacity = 1 - 0.7 * far
        if far < 0.35:
            col = PAPER
        elif far < 0.65:
            col = PAPER if rnd.random() < 0.35 else TINT
        else:
            col = TINT if rnd.random() < 0.4 else MID
        out.append((far, poly(cx, cy, size, size, rot), col, opacity))
    out.sort(key=lambda t: -t[0])               # far ones first, so near ones sit on top
    return out


def padlock():
    cx = X0 + SPINE + (X1 - X0 - SPINE) / 2     # center of the cover (right of the spine)
    cy = (Y0 + Y1) / 2
    bw, bh = 17, 14
    sr0 = (bw - 5) / 2
    by = cy - (bh - 4 - sr0 - 3.0 / 2) / 2   # center the whole lock (shackle included) on the cover
    bx = cx - bw / 2
    r = 2.6
    sw = 3.0
    sr = (bw - 5) / 2
    top = (f"M{fmt(cx - sr)},{fmt(by + 0.5)}V{fmt(by - 4)}"
           f"a{fmt(sr)},{fmt(sr)} 0 0 1 {fmt(2 * sr)},0V{fmt(by + 0.5)}")
    body = (f"M{fmt(bx + r)},{fmt(by)}h{fmt(bw - 2 * r)}a{r},{r} 0 0 1 {r},{r}v{fmt(bh - 2 * r)}"
            f"a{r},{r} 0 0 1 {-r},{r}h{fmt(-(bw - 2 * r))}a{r},{r} 0 0 1 {-r},{-r}v{fmt(-(bh - 2 * r))}"
            f"a{r},{r} 0 0 1 {r},{-r}z")
    kcy = by + 5.2
    key = f"M{fmt(cx)},{fmt(kcy - 2.1)}a2.1,2.1 0 0 1 1.1,3.9l0.8,3.2h-3.8l0.8,-3.2a2.1,2.1 0 0 1 1.1,-3.9z"
    return top, sw, body, key, cx, by + bh / 2


class Shape:
    """One filled or stroked path of the icon, with the colors used by every output format."""

    def __init__(self, name, d, fill=None, alpha=1.0, stroke=None, stroke_width=0.0):
        self.name, self.d, self.fill, self.alpha = name, d, fill, alpha
        self.stroke, self.stroke_width = stroke, stroke_width


def shapes():
    """The icon without its tile, back to front. Dust can overflow the 108x108 canvas on purpose."""
    top, sw, body, key, lcx, lcy = padlock()
    cl = X0 + SPINE + 3          # left of the cover's inner frame
    out = []
    if "ribbon" in FEATURES:     # bookmark ribbon: behind the book, only its tail shows below it
        out.append(Shape("ribbon", f"M{X1 - 11},{Y1 - 10}h5v16l-2.5,-2.8l-2.5,2.8z", TAN_D))
    out.append(Shape("cover", book_path(), PAPER))
    out.append(Shape("spine", spine_path(), SPINE_C))
    if "border" in FEATURES:     # thin inner frame that dissolves where the corner does
        out.append(Shape("border", f"M{X1 - 24},{Y0 + 3}H{cl}V{Y1 - 3}H{X1 - 3}V{Y0 + 22}",
                         stroke=SPINE_C, stroke_width=0.9))
    if "pages" in FEATURES:      # page block seen along the bottom edge
        for k in range(3):
            out.append(Shape("pages", f"M{cl + 1},{fmt(Y1 - 6.5 + k * 1.6)}H{X1 - 4}",
                             alpha=0.8, stroke=MID, stroke_width=0.7))
    if "strap" in FEATURES:      # clasp strap wrapping around from the right edge to the lock
        out.append(Shape("strap", f"M{fmt(lcx)},{fmt(lcy - 4.5)}H{X1}v9H{fmt(lcx)}z", TAN))
        out.append(Shape("strap-seam", f"M{X1 - 3},{fmt(lcy - 4.5)}v9", stroke=TAN_D, stroke_width=0.8))
    out.append(Shape("shackle", top, stroke=INK, stroke_width=sw))
    out.append(Shape("lock", body, INK))
    out.append(Shape("keyhole", key, PAPER))
    for far, d, col, op in dust():
        out.append(Shape("dust", d, col, op))
    return out


def svg_text():
    p = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">',
         '  <title>secrt icon: closed diary, padlock, disintegrating corner</title>',
         f'  <rect width="108" height="108" rx="{0 if "square" in FEATURES else 24}" fill="{INK}"/>']
    for sh in shapes():
        a = ""
        if sh.fill:
            a += f' fill="{sh.fill}"'
            if sh.alpha < 0.995:
                a += f' fill-opacity="{fmt(sh.alpha)}"'
        else:
            a += ' fill="none"'
        if sh.stroke:
            a += f' stroke="{sh.stroke}" stroke-width="{fmt(sh.stroke_width)}"'
            if sh.alpha < 0.995:
                a += f' opacity="{fmt(sh.alpha)}"'
        p.append(f'  <path id="{sh.name}" d="{sh.d}"{a}/>')
    p.append('</svg>')
    return "\n".join(p) + "\n"


def android_text():
    """res/drawable/ic_launcher_foreground.xml. The tile is the adaptive icon's @color/icon_background."""
    p = ['<?xml version="1.0" encoding="utf-8"?>',
         '<!-- Generated by tools/icon/gen_icon.py: a closed diary with a padlock, its corner turning to dust. -->',
         '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
         '    android:width="108dp"', '    android:height="108dp"',
         '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for sh in shapes():
        a = [f'android:pathData="{sh.d}"']
        if sh.fill:
            a.insert(0, f'android:fillColor="{sh.fill}"')
            if sh.alpha < 0.995:
                a.append(f'android:fillAlpha="{fmt(sh.alpha)}"')
        if sh.stroke:
            a += [f'android:strokeColor="{sh.stroke}"', f'android:strokeWidth="{fmt(sh.stroke_width)}"']
            if sh.alpha < 0.995:
                a.append(f'android:strokeAlpha="{fmt(sh.alpha)}"')
        p.append("    <path\n        " + "\n        ".join(a) + " />")
    p.append('</vector>')
    return "\n".join(p) + "\n"


def main():
    text = {".svg": svg_text, ".xml": android_text}[os.path.splitext(OUT)[1]]()
    with open(OUT, "w") as f:
        f.write(text)


main()
