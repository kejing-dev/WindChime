"""Procedural PBR base-colour textures for Wind Chime (design referenceRepo.assets: everything generated, no Blender).
   ~/.factory-venv/bin/python tools/make_textures.py   -> app/src/main/assets/textures/*.png (512 x 512)
walnut / ash (wood grain), brass (brushed), bamboo (lengthwise fibres), glass (pale celadon with baked refraction
stripes of the screen and stand behind it), paper (xuan paper fibres + one faint ink mountain), tile (light wood)."""
import os
import numpy as np
from PIL import Image, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "textures")
N = 512
rng = np.random.default_rng(7)


def noise(scale, octaves=4, seed=0):
    r = np.random.default_rng(seed)
    acc = np.zeros((N, N)); amp = 1.0; tot = 0
    for o in range(octaves):
        s = max(2, int(N / scale * 2 ** o))
        g = r.random((s + 1, s + 1))
        img = Image.fromarray((g * 255).astype(np.uint8)).resize((N, N), Image.BICUBIC)
        acc += amp * np.asarray(img, dtype=np.float64) / 255; tot += amp; amp *= 0.5
    return acc / tot


def save(name, rgb):
    a = np.clip(rgb, 0, 255).astype(np.uint8)
    Image.fromarray(a, "RGB").save(os.path.join(OUT, name + ".png"))
    print(name, a.reshape(-1, 3).mean(0).round(1))


def wood(base, dark, seed):
    y, x = np.mgrid[0:N, 0:N] / N
    warp = noise(64, 4, seed) * 6
    rings = np.sin((x * 18 + warp + noise(16, 2, seed + 1) * 1.5) * np.pi) * 0.5 + 0.5
    fine = noise(4, 2, seed + 2)
    t = (rings ** 3) * 0.7 + fine * 0.3
    base = np.array(base, float); dark = np.array(dark, float)
    return base * (1 - t[..., None]) + dark * t[..., None]


def brushed(base, seed):
    streak = np.random.default_rng(seed).random((N, 1))
    img = np.repeat(streak, N, axis=1)  # vertical streaks? brushed along the tube (v): vary across u
    img = img.T
    img = np.asarray(Image.fromarray((img * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.6)), float) / 255
    mottled = noise(128, 3, seed + 3)
    t = 0.75 + 0.18 * img + 0.12 * (mottled - 0.5)
    return np.array(base, float) * t[..., None]


def bamboo():
    y, x = np.mgrid[0:N, 0:N] / N
    fib = np.random.default_rng(11).random((1, N)).repeat(N, axis=0)
    fib = np.asarray(Image.fromarray((fib * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.0)), float) / 255
    blot = noise(96, 3, 12)
    base = np.array([200, 178, 106], float)  # C8B26A
    green = np.array([150, 160, 80], float)
    t = 0.25 * blot
    col = base * (1 - t[..., None]) + green * t[..., None]
    return col * (0.88 + 0.16 * fib)[..., None]


def glass():
    y, x = np.mgrid[0:N, 0:N] / N
    base = np.array([207, 232, 228], float)  # CFE8E4
    # baked refraction: the paper screen and the dark stand bent into vertical bands, a little colour fringe
    band = 0.5 + 0.5 * np.sin((x * 6.0 + 0.15 * np.sin(y * 9)) * np.pi * 2)
    edge = np.exp(-((x - 0.5) / 0.08) ** 2)  # the column seen through the middle
    r = base[0] * (0.86 + 0.10 * band) - 40 * edge
    g = base[1] * (0.88 + 0.09 * np.roll(band, 3, axis=1)) - 34 * edge
    b = base[2] * (0.90 + 0.08 * np.roll(band, 6, axis=1)) - 28 * edge
    hi = np.clip((band - 0.92) * 8, 0, 1) * 30
    return np.stack([r + hi, g + hi, b + hi], -1)


def paper():
    base = np.array([243, 235, 221], float)  # F3EBDD
    fib = noise(6, 3, 21)
    col = base * (0.95 + 0.07 * fib)[..., None]
    # one faint ink mountain in the lower-left corner
    y, x = np.mgrid[0:N, 0:N] / N
    ridge = 0.78 - 0.16 * np.exp(-((x - 0.22) / 0.13) ** 2) - 0.09 * np.exp(-((x - 0.40) / 0.10) ** 2) + 0.01 * (noise(32, 3, 22) - 0.5)
    m = np.clip((y - ridge) * 14, 0, 1) * np.clip((0.62 - x) * 5, 0, 1)
    wash = m * (0.16 + 0.10 * noise(24, 3, 23))
    ink = np.array([60, 66, 72], float)
    return col * (1 - wash[..., None]) + ink * wash[..., None]


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    save("walnut", wood([118, 78, 50], [62, 40, 26], 1))
    save("ash", wood([226, 210, 182], [184, 160, 126], 2))
    save("tile", wood([227, 201, 160], [200, 170, 126], 3))
    save("brass", brushed([201, 161, 90], 4))
    save("bamboo", bamboo())
    save("glass", glass())
    save("paper", paper())
