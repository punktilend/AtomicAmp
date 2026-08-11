"""Play listing graphics, drawn from the same geometry as the in-app adaptive icon.

Everything is rendered at 4x and downsampled, because PIL has no anti-aliased stroke.
"""
import math, os
from PIL import Image, ImageDraw, ImageFont

OUT = r"C:\Users\adamm\AtomicPowerAmp\store"
os.makedirs(OUT, exist_ok=True)

GROUND = (13, 15, 17)
AMBER = (255, 179, 92)
INK = (231, 233, 235)
MUTED = (139, 147, 155)
# Pre-blended rather than alpha: a polyline drawn with alpha composites each overlapping
# segment separately, which stripes the curve.
ORBIT = (150, 108, 60)
SS = 4  # supersample factor


def draw_mark(d, cx, cy, r, ring_w, orbit=True):
    """An orbit around an amp knob.

    The pointer sits off vertical on purpose: a full ring with a mark at twelve reads as a power
    button, not as a dial that has been turned somewhere.
    """
    if orbit:
        rx, ry, ang = r * 2.05, r * 0.92, math.radians(-26)
        pts = []
        for i in range(241):
            t = 2 * math.pi * i / 240
            x, y = rx * math.cos(t), ry * math.sin(t)
            pts.append((cx + x * math.cos(ang) - y * math.sin(ang),
                        cy + x * math.sin(ang) + y * math.cos(ang)))
        d.line(pts, fill=ORBIT, width=max(1, int(ring_w * 0.45)), joint="curve")
        ex = cx + rx * math.cos(ang)
        ey = cy + rx * math.sin(ang)
        er = r * 0.17
        d.ellipse([ex - er, ey - er, ex + er, ey + er], fill=AMBER)

    # Punch only just past the ring, so the orbit still reads as passing behind the knob.
    punch = r + ring_w * 0.75
    d.ellipse([cx - punch, cy - punch, cx + punch, cy + punch], fill=GROUND)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=AMBER, width=ring_w)

    theta = math.radians(35)  # clockwise from twelve
    px = cx + math.sin(theta) * r * 0.66
    py = cy - math.cos(theta) * r * 0.66
    d.line([(cx, cy), (px, py)], fill=AMBER, width=ring_w)


def icon(size, path, bg=GROUND):
    img = Image.new("RGB", (size * SS, size * SS), bg)
    d = ImageDraw.Draw(img, "RGBA")
    c = size * SS / 2
    draw_mark(d, c, c, r=size * SS * 0.155, ring_w=int(size * SS * 0.052))
    img.resize((size, size), Image.LANCZOS).save(path)
    print("wrote", path)


def font(px, bold=False):
    name = "arialbd.ttf" if bold else "arial.ttf"
    return ImageFont.truetype(rf"C:\Windows\Fonts\{name}", px)


def feature_graphic(path):
    w, h = 1024 * SS, 500 * SS
    img = Image.new("RGB", (w, h), GROUND)
    d = ImageDraw.Draw(img, "RGBA")

    # A faint amber wash from the left, so the flat dark does not read as an unfinished canvas.
    for x in range(w):
        a = int(16 * max(0.0, 1 - x / (w * 0.62)))
        if a:
            d.line([(x, 0), (x, h)], fill=AMBER + (a,))

    draw_mark(d, w * 0.175, h * 0.5, r=h * 0.155, ring_w=int(h * 0.050))

    tx = w * 0.35
    d.text((tx, h * 0.30), "AtomicAmp", font=font(int(h * 0.19), bold=True), fill=INK, anchor="ls")
    d.text((tx, h * 0.44), "A music player built for the dashboard",
           font=font(int(h * 0.072)), fill=AMBER, anchor="ls")
    d.text((tx, h * 0.58), "FLAC and hi-res  \u00b7  10-band EQ  \u00b7  folder library",
           font=font(int(h * 0.058)), fill=MUTED, anchor="ls")
    d.text((tx, h * 0.68), "cue sheets  \u00b7  picks up where the ignition left off",
           font=font(int(h * 0.058)), fill=MUTED, anchor="ls")

    img.resize((1024, 500), Image.LANCZOS).save(path)
    print("wrote", path)


icon(512, os.path.join(OUT, "play-icon-512.png"))
feature_graphic(os.path.join(OUT, "play-feature-graphic-1024x500.png"))
