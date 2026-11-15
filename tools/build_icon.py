"""Wind Chime icon (design appIcon A): a sky-blue #8ECAE6 disc (no other app on the line uses it), a five-tube brushed
brass #C9A15A chime under a walnut #4A3222 disc, its sail blown to the right, one white wind stroke at the upper left.
No text.   ~/.factory-venv/bin/python tools/build_icon.py [--icon512 out.png] [--preview out.png]
Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same polygons)."""
import math, os, sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
SKY = (0x8E, 0xCA, 0xE6)
WALNUT = (0x4A, 0x32, 0x22)
BRASS = (0xC9, 0xA1, 0x5A)
BRASS_HI = (0xE8, 0xC9, 0x88)
CORD = (0x2B, 0x24, 0x20)
WHITE = (0xFF, 0xFF, 0xFF)


def rect(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def rot(pts, cx, cy, deg):
    a = math.radians(deg); c, s = math.cos(a), math.sin(a)
    return [(cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c) for x, y in pts]


TILT = -6  # the chime leans a little: the wind comes from the left
CX, TOP = 52, 30
DISC = rot(rect(36, 27, 68, 32), CX, TOP, TILT)
HANG = rot(rect(51.4, 18, 52.6, 27), CX, TOP, TILT)
TUBES, CORDS, HIS = [], [], []
for i, (x, l) in enumerate([(39.5, 40), (45.5, 37), (51.5, 34), (57.5, 31), (63.5, 28)]):
    CORDS.append(rot(rect(x + 1.4, 32, x + 2.0, 36), CX, TOP, TILT))
    TUBES.append(rot(rect(x, 36, x + 3.4, 36 + l), CX, TOP, TILT))
    HIS.append(rot(rect(x + 0.6, 37, x + 1.3, 35 + l), CX, TOP, TILT))
CLAP_CORD = rot(rect(51.4, 32, 52.6, 62), CX, TOP, TILT)
# the sail, blown to the right on its cord
SAIL_CORD = [(54.5, 62), (66, 72), (65.2, 73), (53.7, 63)]
SAIL = [(64, 70), (73, 70), (77, 78), (73, 89), (66, 92), (61, 84), (61, 75)]
WIND = []
for k in range(0, 21):  # a thick arc at the upper left
    a = math.radians(200 + k * 4.5)
    WIND.append((30 + 15 * math.cos(a), 30 + 12 * math.sin(a)))
inner = []
for k in range(20, -1, -1):
    a = math.radians(200 + k * 4.5)
    inner.append((30 + 12 * math.cos(a), 30 + 9.2 * math.sin(a)))
WIND = WIND + inner


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def sc(pts, k):
    return [(x * k, y * k) for x, y in pts]


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=SKY + (255,)))


def layer_chime():
    def d(dr, k):
        dr.polygon(sc(HANG, k), fill=CORD + (255,))
        for c in CORDS: dr.polygon(sc(c, k), fill=CORD + (255,))
        dr.polygon(sc(CLAP_CORD, k), fill=CORD + (255,))
        for t in TUBES: dr.polygon(sc(t, k), fill=BRASS + (255,))
        for h in HIS: dr.polygon(sc(h, k), fill=BRASS_HI + (255,))
        dr.polygon(sc(DISC, k), fill=WALNUT + (255,))
    return big(d)


def layer_sail():
    def d(dr, k):
        dr.polygon(sc(SAIL_CORD, k), fill=CORD + (255,))
        dr.polygon(sc(SAIL, k), fill=WALNUT + (255,))
        dr.polygon(sc(WIND, k), fill=WHITE + (255,))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def path(pts):
    return "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    p = lambda c, polys: '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(c), "".join(path(x) for x in polys))
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- Wind Chime launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
             p(CORD, [HANG] + CORDS + [CLAP_CORD, SAIL_CORD]), p(BRASS, TUBES), p(BRASS_HI, HIS), p(WALNUT, [DISC, SAIL]), p(WHITE, [WIND]), '</vector>']
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_chime(), layer_sail()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers: flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
