#!/usr/bin/env python3
"""Generates the icon sketch SVG: a closed diary with a flat padlock, top-right corner disintegrating.

usage: python3 tools/icon/gen_icon.py OUT.svg [seed]   (seed defaults to 5, the chosen sketch)
"""
import math
import random
import sys

OUT = sys.argv[1]
SEED = int(sys.argv[2]) if len(sys.argv) > 2 else 5

INK = "#4A5C92"      # app primary / icon background
PAPER = "#FBF8F3"    # app background / book cover
SPINE_C = "#B8C5F0"  # spine band: a light tint of the ink
TINT = "#DBE1FF"     # primaryContainer, for some of the flying chunks
MID = "#9DAEE6"      # between ink and tint, for the farthest chunks

# --- Book geometry (108x108 grid; Android adaptive-icon safe zone is the central 66x66) ---
C = 2                          # cell size of the pixel grid
X0, Y0 = 24, 31                # book top-left
COLS, ROWS = 23, 27            # 46 x 54
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
    bx, by = cx - bw / 2, cy - bh / 2 + 3.5
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
    return top, sw, body, key


def main():
    top, sw, body, key = padlock()
    p = []
    p.append('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">')
    p.append('  <title>secrt icon sketch: closed diary, padlock, disintegrating corner</title>')
    p.append(f'  <rect width="108" height="108" rx="24" fill="{INK}"/>')
    p.append('  <g id="diary">')
    p.append(f'    <path d="{book_path()}" fill="{PAPER}"/>')
    p.append(f'    <path d="{spine_path()}" fill="{SPINE_C}"/>')
    p.append('  </g>')
    p.append('  <g id="lock">')
    p.append(f'    <path d="{top}" fill="none" stroke="{INK}" stroke-width="{sw}"/>')
    p.append(f'    <path d="{body}" fill="{INK}"/>')
    p.append(f'    <path d="{key}" fill="{PAPER}"/>')
    p.append('  </g>')
    p.append('  <g id="dust">')
    for far, d, col, op in dust():
        o = "" if op >= 0.995 else f' fill-opacity="{fmt(op)}"'
        p.append(f'    <path d="{d}" fill="{col}"{o}/>')
    p.append('  </g>')
    p.append('</svg>')
    with open(OUT, "w") as f:
        f.write("\n".join(p) + "\n")


main()
