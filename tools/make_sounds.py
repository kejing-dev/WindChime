"""Wind Chime sounds from CC0 recordings only (B20 / D51: physical sounds are real recordings, no synthesis).
   ~/.factory-venv/bin/python tools/make_sounds.py [--samples DIR]
Sources: ~/Downloads/cc0-sounds (freesound.org pages that state Creative Commons 0; Kenney CC0 packs). Each tube note
= a struck transient recording + the free ring of a real metal bowl / glass / wood, both pitch-shifted by resampling so
their strongest partial sits on the tube's note (design chimes table), mixed ("CC0 recordings spliced", not synthesis).
Output: app/src/main/assets/sounds/<chime>_<n>_<v>.ogg (mono 44.1 kHz, peak -1 dBFS), gust.ogg, rustle.ogg."""
import os, subprocess, sys
from fractions import Fraction
import numpy as np
from scipy.signal import resample_poly, butter, sosfiltfilt

SR = 44100
LIB = os.path.expanduser("~/Downloads/cc0-sounds")
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "sounds")
NOTES = {
    "brass": [523.25, 587.33, 659.26, 783.99, 880.00],
    "bamboo": [392.00, 440.00, 523.25, 587.33, 659.26],
    "glass": [659.26, 783.99, 880.00, 1046.50, 1174.66],
}


def load(rel, start=0.0, dur=None):
    args = ["ffmpeg", "-v", "error", "-ss", str(start)] + (["-t", str(dur)] if dur else []) + ["-i", os.path.join(LIB, rel), "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"]
    x = np.frombuffer(subprocess.run(args, capture_output=True, check=True).stdout, dtype=np.int16).astype(np.float64) / 32768
    return x


def peak_freq(x, lo=150, hi=3000):
    seg = x[: min(len(x), 32768)]
    w = np.hanning(len(seg)); S = np.abs(np.fft.rfft(seg * w)); f = np.fft.rfftfreq(len(seg), 1 / SR)
    m = (f > lo) & (f < hi)
    return float(f[m][np.argmax(S[m])])


def shift(x, ratio):
    """Resample so every partial is multiplied by [ratio] (and the sound is 1/ratio as long)."""
    fr = Fraction(1 / ratio).limit_denominator(400)
    return resample_poly(x, fr.numerator, fr.denominator)


def band(x, lo=None, hi=None):
    if lo: x = sosfiltfilt(butter(4, lo, "highpass", fs=SR, output="sos"), x)
    if hi: x = sosfiltfilt(butter(4, hi, "lowpass", fs=SR, output="sos"), x)
    return x


def onset(x):
    env = np.convolve(np.abs(x), np.ones(64) / 64, "same")
    return int(np.argmax(env > 0.2 * env.max()))


def fit(x, n):
    return np.pad(x, (0, max(0, n - len(x))))[:n]


def note(kind, hz, variant):
    """One tube note: transient + ring, shaped to the chime's decay (brass T60 ~6 s, glass ~3 s, bamboo natural)."""
    if kind == "bamboo":
        src = ["freesound-zen-orb/218460_3344538.mp3", "kenney_impact-sounds/Audio/impactWood_light_002.ogg"][variant]
        x = load(src); x = x[onset(x):]
        y = shift(x, hz / peak_freq(x, 200, 1500))
        y = band(y, lo=120, hi=9000)
        n = int(SR * 1.2); y = fit(y, n)
        t = np.arange(n) / SR
        return y * np.exp(-t * 6.9 / 0.9) ** 0.5  # gentle extra decay toward T60 0.9 s (design assets)
    if kind == "brass":
        hit = load(["kenney_impact-sounds/Audio/impactMetal_light_003.ogg", "kenney_impact-sounds/Audio/impactMetal_light_000.ogg"][variant])
        ring_src, ring_at = [("freesound-zen-orb/196365_1847371.mp3", 19.95), ("freesound-zen-orb/439235_2557312.mp3", 12.65)][variant]
        t60, dur, ring_gain = 6.0, 5.5, 1.0
    else:
        hit = load(["kenney_impact-sounds/Audio/impactGlass_light_001.ogg", "kenney_impact-sounds/Audio/impactGlass_light_003.ogg"][variant])
        ring_src, ring_at = [("freesound-zen-orb/707797_14713637.mp3", 1.35), ("freesound-zen-orb/196365_1847371.mp3", 19.95)][variant]
        t60, dur, ring_gain = 3.0, 3.2, 0.55
    ring = load(ring_src, ring_at, 14)
    r = shift(ring, hz / peak_freq(ring))
    n = int(SR * dur)
    r = fit(r, n)
    r *= np.minimum(1, np.arange(n) / (0.004 * SR))  # 4 ms fade-in: the ring starts with the hit
    t = np.arange(n) / SR
    r *= np.exp(-t * 6.9 / t60)  # the bowl's own decay plus the tube's T60
    hit = hit[onset(hit):]
    h = shift(hit, hz / peak_freq(hit, 300, 6000)) if kind == "glass" else hit
    h = band(fit(h, n), lo=200)
    rr = r / (np.abs(r).max() + 1e-9) * ring_gain
    hh = h / (np.abs(h).max() + 1e-9) * (0.55 if kind == "brass" else 0.8)
    y = band(rr + hh, lo=280, hi=12000)
    y[-int(0.3 * SR):] *= np.linspace(1, 0, int(0.3 * SR)) ** 2  # tail fades to silence, no click
    return y


def norm(y, peak_db=-1.0):
    return y / (np.abs(y).max() + 1e-12) * 10 ** (peak_db / 20)


def write(name, y, out=OUT):
    y = norm(y)
    pcm = (np.clip(y, -1, 1) * 32767).astype(np.int16).tobytes()
    path = os.path.join(out, name + ".ogg")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", str(SR), "-ac", "1", "-i", "-", "-c:a", "libvorbis", "-q:a", "6", path], input=pcm, check=True)
    return y


def wav(path, y):
    import wave
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(y, -1, 1) * 32767).astype(np.int16).tobytes())


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    samples = sys.argv[sys.argv.index("--samples") + 1] if "--samples" in sys.argv else None
    if samples: os.makedirs(samples, exist_ok=True)
    for kind, notes in NOTES.items():
        for i, hz in enumerate(notes):
            for v in range(2):
                y = note(kind, hz, v)
                # second pass: put the strongest partial of the finished note exactly on the tube's pitch
                for _ in range(2):
                    got = peak_freq(y[int(0.02 * SR):], 250, 3000)
                    if 0.75 < hz / got < 1.33 and abs(hz / got - 1) > 0.005:
                        n = len(y); y = fit(shift(y, hz / got), n); y[-int(0.3 * SR):] *= np.linspace(1, 0, int(0.3 * SR)) ** 2
                y = write(f"{kind}_{i + 1}_{v + 1}", y)
                got = peak_freq(y[int(0.02 * SR):], 250, 3000)
                print(f"{kind}_{i + 1}_{v + 1}: target {hz:.1f} Hz, strongest {got:.1f} Hz, {len(y) / SR:.2f} s")
                if samples and v == 0: wav(os.path.join(samples, f"{kind}_{i + 1}.wav"), y)
    # gust: 0.6 s of a real wind recording, band-passed, soft in / out
    w = load("freesound-weather/wind_726319.mp3", 3.0, 0.9)
    w = band(w, 200, 4000)[: int(0.6 * SR)]
    env = np.sin(np.linspace(0, np.pi, len(w))) ** 1.5
    write("gust", w * env)
    # rustle: a card sliding on felt (Kenney casino, CC0) = the paper screen puffing
    r = load("kenney_casino-audio/Audio/card-slide-2.ogg")
    write("rustle", band(r[onset(r):][: int(0.35 * SR)], 300, 8000))
    print("done ->", OUT)
