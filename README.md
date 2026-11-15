# 风铃 Windchime

A five-tube wind chime on your desk for PICO OS 6 Shared Space (Spatial SDK 6.1.9). Tap a tube or a tile on the base
and it rings; pinch the sail at the bottom, pull it back and let go, and the whole chime swings; swipe across the paper
screen behind it for a gust. Brass, bamboo or glass, five pentatonic notes each, four wind levels.

- Package `io.embodit.windchime`, v0.1.2, fully offline, Chinese / English.
- Sounds: CC0 recordings (freesound.org, Kenney) pitched to each tube, see `tools/make_sounds.py`.
- Build: `./gradlew assembleDebug :app:testDebugUnitTest` (JDK 21). Release builds are unsigned (the store re-signs).

See `AGENTS.md` for the code map, the debug broadcast and the screenshot list.
