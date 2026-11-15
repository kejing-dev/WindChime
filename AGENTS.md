# 风铃 Windchime: project guidance

A desk wind chime for PICO OS 6 Shared Space (Spatial SDK BOM 6.1.9), package `io.embodit.windchime`, v0.1.2.
Design contract: spatial-app-factory `apps/wind-chime/design-package.json` v1.3 (gate B passed 2026-11-15, defaults:
Shared Space, still wind by default, brass A / bamboo A / glass A, icon A). Skeleton: the factory's shared volume
template (volume + `Placement.right` control window, native TabBar, theme, board-input template).

## Surfaces
- **Chime volume** (default container `WindChimeVolume`, Volumetric 0.60 x 0.70 x 0.60 m, fixed world scale, gravity aligned,
  uniform resize: the root follows the measured size, see `domain/VolumeScale.kt`). Origin = volume centre, +Z toward the user. Walnut base / column / arm, paper screen (ash frame),
  the chime (disc, five tubes on a 0.065 m ring with the pentagon vertex at the back, clapper, sail), five base tiles
  (宫商角徵羽 / letters, tilted 35 deg), the 「面板 / Panel」 tile at the screen's lower right, six wind lines, a warm key light.
- **Control window** (`WindChimeControl`, Planar 560 x 640 dp, `Placement.right(volume)`), native TabBar:
  玩 Play / 怎么玩 How to play / 设置 Settings. Quit closes both containers, removes the tasks and exits.

## Code
| Path | What |
|---|---|
| `domain/Chimes.kt` | chime kinds, notes, tube lengths, settings, `VolumeSnap`, `TapRule` (2 cm / 0.35 s) |
| `domain/Geo.kt` | every measure of the volume, eye positions, strips, the screen-tap ray fallback, occlusion solids |
| `domain/Physics.kt` | `ChimeSim`: seven pendulums at 1/360 s, wind + gusts, clapper / tube / cord contacts, limits, rate limits |
| `domain/Flow.kt` | `ChimeFlow`: S00..S08 + STRIKE / SWING / GUST, taps, drags (sail grab, gust swipe), swap timeline |
| `domain/Palette.kt` | UI colours + WCAG contrast (unit-tested) |
| `ui/scene/*` | `ChimeScene` (entities, per-frame pose copy from the ECS `ChimeFrameSystem`), `ChimeScreen` (SpatialView + board-input), `Meshes` (true round meshes), `Labels` (tile / sail text) |
| `ui/control/*` | window pages; every Text logs `OVERFLOW <tag>` when cut; every page logs `LAYOUT <page> blank=<%>` |
| `domain/Mixer.kt` | the mix bus (v0.1.1): new notes at their own level, older tails ducked just enough (attack peak +-0.42 dB), look-ahead limiter at -1 dBFS; `platform/Platform.kt` SoundPlayer decodes the OGGs once and feeds one AudioTrack |
| `domain/VolumeScale.kt` | measured volume size -> root scale and hand travel / hit point -> content metres (0.5x / 1x / 3x in VolumeScaleTest) |
| `tools/` | `make_textures.py`, `make_sounds.py` (CC0 recordings -> 30 notes), `loudness.py`, `build_icon.py` |

Input: the factory board-input template (`ui/scene/input/BoardInput.kt`, DragTotal accumulates the per-frame
`dragAmount`). Holders: `tube:1..5`, `token:1..5`, `strip:1..5` (invisible, behind the ring), `sail`, `screen`, `panel`.
A press < 2 cm and < 0.35 s = tap; >= 2 cm = drag (sail = grab S03; screen / strip / tube = gust swipe); a still hold = nothing.

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest
~/.factory-venv/bin/python tools/make_textures.py; ~/.factory-venv/bin/python tools/make_sounds.py --samples <dir>
~/.factory-venv/bin/python tools/loudness.py <factory>/apps/wind-chime/qa v0.1.2   # after the unit tests (MixerRenderTest / MixerStressTest render the bus with the real samples, needs ffmpeg; stress report app/build/loudness/stress-v0.1.2.txt)
~/.factory-venv/bin/python tools/build_icon.py --icon512 <factory>/apps/wind-chime/icon.png
```
Emulators are shared: `python3 -I bin/emu_borrow.py borrow wind-chime` in the factory, `return` after.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.windchime.DEBUG -p io.embodit.windchime --es cmd <cmd> --es a "<arg>"`. Log tag `WindChime`.
Every command calls the same `ChimeFlow` methods as the window and the 3D input.

| State | Commands (preconditions in order) |
|---|---|
| S00 准备中 | `s00hold on`, force-stop + launch, capture within 10 s, then `s00hold off` (or `goto S00` for the panel only) |
| S01 第一次 | `fresh` + force-stop + launch (or `goto S01`) |
| S02 玩 | `goto S02`; per chime: `chime brass|bamboo|glass`, wait 1 s |
| S02+W2 / W3 | `goto S02`, `wind 2` (or 3), wait 5 s |
| S02+STRIKE | `goto S02`, `strike 3`, `hold STRIKE` (then `hold off`) |
| S03 拉住风帆 | `goto S03` (pulls the sail 0.12 m right over 0.6 s through the drag path and holds) ; `release` ends it |
| S02+SWING | `goto S03`, `release`, wait 0.3 s, `hold SWING` |
| S02+GUST | `goto S02`, `seed 7`, `gust "1.0 +x"`, wait 0.3 s, `hold GUST` ; add `reduced on` first for +RM |
| S04 换风铃 | `goto S04` (swaps to the next chime and freezes half way) ; `hold off` to finish |
| S05 设置 / S06 退出确认 / S08 怎么玩 | `goto S05` / `goto S06` / `goto S08` |
| S07 面板关了 | `goto S07` (system-closes the window; the panel tile glows 2 s) ; `tap panel` reopens it |
| MUTE / LANG | `mute on|off`, `lang zh|en|system` |
Other: `strike n`, `token n`, `tap <holder>`, `tapscreen "<x> <y>"`, `grab` / `drag "dx dy dz"` / `release`, `swipe "dx dz"`,
`gustbutton`, `vol 0..1`, `gustsound on|off`, `page play|howto|settings`, `exitask`, `closepanel`, `reopen`, `testf on|off`,
`vscale <k>|off` (pretend a k x resize), `lightfloor on|off` (emissive floor), `envlight <0..1>` (environment light scale)
(F-up test on the tiles, panel tile and paper sail), `state` / `dump`.

## Known
- 3D pinches / drags can't be sent by `pico-cli interact` on the Mac emulator: driven by the debug broadcast through the same
  flow calls; hand ray / controller / eye-hand on the volume need the headset.
- The app never reads the head pose; the window opens beside the volume (Placement.right).
