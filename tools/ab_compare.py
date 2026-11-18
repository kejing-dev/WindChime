"""A/B sample check (profile D55 补, v0.1.4): the same 10 s gale rendered by two versions' Mixer.
   ./gradlew :app:testDebugUnitTest --tests '*GaleABTest*'      (this version -> app/build/ab/gale10-v<ver>.wav + events csv)
   (the older tag renders the same csv with its own Mixer: git worktree + the same test reading the csv)
   ~/.factory-venv/bin/python tools/ab_compare.py <out_dir> <old.wav> <new.wav> [<more.wav> ...]
Per file: peak dBFS, clipped samples (|x| >= 0.999), samples at the -1 dBFS ceiling, sounding RMS, energy share above
4 kHz, dropouts (>= 1 ms of digital silence while the 50 ms around it sounds) and clicks (a sample-to-sample step
> 6x the local 99.9th percentile step). Spectrograms by ffmpeg showspectrumpic. Rendered, not recorded."""
import os, sys, shutil, subprocess, wave
import numpy as np

def read(p):
    with wave.open(p) as w:
        sr = w.getframerate()
        y = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768
    return sr, y.reshape(-1, 2)

def measure(p):
    sr, y = read(p)
    mono = y.mean(axis=1)
    pk = np.abs(y).max()
    clipped = int((np.abs(y) >= 0.999).sum())
    ceiling = int((np.abs(y) >= 0.891 - 1e-4).sum())
    w = int(0.02 * sr); n = len(mono) // w
    rms = np.sqrt((mono[: n * w].reshape(n, w) ** 2).mean(axis=1))
    on = rms[rms > pk * 10 ** (-30 / 20)]
    srms = 20 * np.log10(np.sqrt((on ** 2).mean()))
    spec = np.abs(np.fft.rfft(mono)) ** 2
    f = np.fft.rfftfreq(len(mono), 1 / sr)
    hi = spec[f > 4000].sum() / spec.sum()
    hi_abs = spec[f > 4000].sum() / len(mono)
    # dropouts: runs of exact zeros >= 1 ms inside sounding regions
    z = (np.abs(y).max(axis=1) == 0).astype(np.int8)
    drops = 0; i = 0; L = len(z); ms1 = int(0.001 * sr); ctx = int(0.05 * sr)
    while i < L:
        if z[i]:
            j = i
            while j < L and z[j]: j += 1
            if j - i >= ms1 and i > ctx and j < L - ctx and np.abs(mono[i - ctx:i]).max() > 1e-3 and np.abs(mono[j:j + ctx]).max() > 1e-3:
                drops += 1
            i = j
        else:
            i += 1
    d = np.abs(np.diff(y, axis=0)).max(axis=1)
    thr = max(np.percentile(d, 99.9) * 6, 0.05)
    clicks = int((d > thr).sum())
    return dict(sr=sr, secs=len(y) / sr, peak=20 * np.log10(pk), clipped=clipped, at_ceiling=ceiling, rms=srms,
                hi_share=hi * 100, hi_energy=hi_abs, dropouts=drops, clicks=clicks)

if __name__ == "__main__":
    out = sys.argv[1]; files = sys.argv[2:]
    os.makedirs(out, exist_ok=True)
    rows = []
    for p in files:
        name = os.path.basename(p)
        dst = os.path.join(out, name)
        if os.path.abspath(p) != os.path.abspath(dst): shutil.copy(p, dst)
        png = os.path.splitext(dst)[0] + "-spectrogram.png"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", dst, "-lavfi", "showspectrumpic=s=1200x500:legend=1:scale=log:fscale=lin:stop=16000", png], check=True)
        m = measure(dst); m["name"] = name; rows.append(m)
    base = rows[0]
    lines = [f"# A/B: same 10 s brass gale event list ({base['secs']:.0f} s render), each version's own Mixer, real CC0 samples (rendered, not recorded)"]
    for m in rows:
        lines.append(f"{m['name']}: peak {m['peak']:.2f} dBFS | clipped {m['clipped']} | at -1 dBFS ceiling {m['at_ceiling']} | sounding RMS {m['rms']:.2f} dBFS | >4 kHz {m['hi_share']:.2f} % of energy (abs {m['hi_energy']:.4e}, {10*np.log10(m['hi_energy']/base['hi_energy']):+.2f} dB vs {base['name']}) | dropouts {m['dropouts']} | clicks {m['clicks']}")
    # sample-level difference to the first file
    _, a = read(files[0])
    for p in files[1:]:
        _, b = read(p); n = min(len(a), len(b))
        diff = np.abs(a[:n] - b[:n]).max()
        lines.append(f"max |{os.path.basename(p)} - {os.path.basename(files[0])}| = {diff:.6f} ({'identical' if diff == 0 else f'{20*np.log10(diff):.1f} dBFS'})")
    txt = "\n".join(lines) + "\n"
    print(txt, end="")
    open(os.path.join(out, "ab-gale10-compare.txt"), "w").write(txt)
