"""Render the loudness evidence (design sound.loudness, build.md 只有短促反馈音 / 响度要有证据文件):
   ./gradlew :app:testDebugUnitTest   (LoudnessEventsTest writes app/build/loudness/events-<chime>.csv)
   ~/.factory-venv/bin/python tools/loudness.py <out_dir> <version>
Mixes the app's own OGG samples at the app's gain law (SoundPlayer: volume 100 %, strike volume curve, at most 2 notes
per tube, linear-sum headroom under -1 dBFS with the same per-chime decay constants, pan +-0.3 by tube x) into a stereo
16-bit WAV per chime: <out_dir>/loudness-<version>-<chime>.wav, and the brass one also as loudness-<version>.wav.
Rendered from the sound code, not recorded. Prints RMS over the sounding windows (20 ms, > peak - 30 dB) and peak."""
import csv, os, subprocess, sys, shutil
import numpy as np

SR = 44100
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SND = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
TUBE_X = [-0.062, -0.038, 0.0, 0.038, 0.062]
TAU = {"brass": 0.8, "bamboo": 0.2, "glass": 0.5}
CEIL = 0.891
PEAK = 0.891


def ogg(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.int16).astype(np.float64) / 32768


def levels(y):
    mono = y.mean(axis=1) if y.ndim == 2 else y
    pk = np.abs(y).max()
    w = int(0.02 * SR)
    n = len(mono) // w
    rms = np.sqrt((mono[: n * w].reshape(n, w) ** 2).mean(axis=1))
    on = rms[rms > pk * 10 ** (-30 / 20)]
    return 20 * np.log10(np.sqrt((on ** 2).mean())), 20 * np.log10(pk)


def render(kind, events):
    out = np.zeros((int(SR * 31), 2))
    nxt = {}; active = []
    for t, tube, vol in events:
        if vol <= 0.001: continue
        v = nxt.get(tube, 0); nxt[tube] = (v + 1) % 2
        x = ogg(os.path.join(SND, f"{kind}_{tube + 1}_{v + 1}.ogg"))
        active = [a for a in active if t - a[0] < 7]
        same = [a for a in active if a[1] == tube]
        if len(same) >= 2: active.remove(min(same))
        others = sum(a[2] * np.exp(-(t - a[0]) / TAU[kind]) for a in active)
        g = min(1.0, max(0.25, (CEIL - others) / (vol * PEAK)))
        pan = TUBE_X[tube] / 0.062 * 0.3
        l = vol * g * min(1.0, 1 - pan); r = vol * g * min(1.0, 1 + pan)
        i = int(t * SR); n = min(len(x), len(out) - i)
        out[i:i + n, 0] += x[:n] * l; out[i:i + n, 1] += x[:n] * r
        active.append((t, tube, vol * g * PEAK))
    return out[: int(SR * 30)]


def write(path, y):
    import wave
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(y, -1, 1) * 32767).astype(np.int16).tobytes())


if __name__ == "__main__":
    out_dir, ver = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    for kind in ("brass", "bamboo", "glass"):
        with open(os.path.join(ROOT, "app", "build", "loudness", f"events-{kind}.csv")) as f:
            ev = [(float(r["t"]), int(r["tube"]), float(r["vol"])) for r in csv.DictReader(f)]
        y = render(kind, ev)
        clipped = int((np.abs(y) > 0.999).sum())
        p = os.path.join(out_dir, f"loudness-{ver}-{kind}.wav")
        write(p, y)
        rms, pk = levels(y)
        print(f"{kind}: {len(ev)} strikes in 30 s, sounding-window RMS {rms:.1f} dBFS, peak {pk:.1f} dBFS, clipped samples {clipped}")
        if kind == "brass": shutil.copy(p, os.path.join(out_dir, f"loudness-{ver}.wav"))
