"""Loudness evidence (design sound.loudness, build.md 只有短促反馈音 / 响度要有证据文件; QA #1 q1-1).
   ./gradlew :app:testDebugUnitTest   (MixerRenderTest renders the app's own Mixer — tail duck + look-ahead limiter at
                                        -1 dBFS — with the real CC0 samples into app/build/loudness/)
   ~/.factory-venv/bin/python tools/loudness.py <out_dir> <version>
Copies the renders to <out_dir> (loudness-<version>-<chime>.wav = 30 s of gale, the brass one also as
loudness-<version>.wav; melody-<chime>-<ms>ms-<version>.wav = 10 tile taps) and re-measures them independently here:
sounding-window RMS (20 ms windows above peak - 30 dB), peak, and the attack peak (first 100 ms) of every tap.
Rendered from the sound code, not recorded."""
import os, sys, shutil, wave
import numpy as np

SR = 44100
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(os.path.dirname(HERE), "app", "build", "loudness")
LATENCY = 48 + 96  # Mixer.DUCK_RAMP + Mixer.LOOKAHEAD frames
BLOCK = 240


def read(path):
    with wave.open(path) as w:
        y = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768
    return y.reshape(-1, 2)


def levels(y):
    mono = y.mean(axis=1)
    pk = np.abs(y).max()
    w = int(0.02 * SR); n = len(mono) // w
    rms = np.sqrt((mono[: n * w].reshape(n, w) ** 2).mean(axis=1))
    on = rms[rms > pk * 10 ** (-30 / 20)]
    return 20 * np.log10(np.sqrt((on ** 2).mean())), 20 * np.log10(pk)


if __name__ == "__main__":
    out_dir, ver = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    lines = [f"# {ver}: re-measured by tools/loudness.py from the MixerRenderTest renders (rendered, not recorded)"]
    for kind in ("brass", "bamboo", "glass"):
        y = read(os.path.join(SRC, f"gale-{kind}.wav"))
        rms, pk = levels(y)
        p = os.path.join(out_dir, f"loudness-{ver}-{kind}.wav"); shutil.copy(os.path.join(SRC, f"gale-{kind}.wav"), p)
        if kind == "brass": shutil.copy(p, os.path.join(out_dir, f"loudness-{ver}.wav"))
        lines.append(f"{kind} gale 30 s: sounding-window RMS {rms:.1f} dBFS, peak {pk:.2f} dBFS, clipped {int((np.abs(y) > 0.999).sum())}")
        for ms in (250, 400):
            src = os.path.join(SRC, f"melody-{kind}-{ms}ms.wav")
            m = read(src)
            shutil.copy(src, os.path.join(out_dir, f"melody-{kind}-{ms}ms-{ver}.wav"))
            pks = []
            for i in range(10):
                t = 0.2 + i * ms / 1000
                f = int(np.floor(t * SR / BLOCK)) * BLOCK + LATENCY  # the 240-frame block the tap lands in, plus the bus latency
                pks.append(float(20 * np.log10(np.abs(m[f:f + int(0.1 * SR)]).max())))
            lines.append(f"{kind} {ms} ms 10-tap melody: attack peaks {[round(v, 2) for v in pks]} | spread {max(pks) - min(pks):.2f} dB | max {max(pks):.2f} dBFS")
    txt = "\n".join(lines) + "\n"
    print(txt, end="")
    open(os.path.join(out_dir, f"melody-peaks-{ver}.txt"), "w").write(txt)
