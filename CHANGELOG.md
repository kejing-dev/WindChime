# Changelog / 更新记录

## v0.1.3 · 2026-11-16

- 大风连续碰撞不再「卡」：物理挪到独立的 360 Hz 线程按真实时钟走，系统把体积降到 10 fps 时铃管也不慢动作；碰撞声按发生时刻排进混音，不再一帧一起响（真机门 C 反馈）
- Gale no longer stutters: physics runs on its own 360 Hz thread by the wall clock, so the chime keeps real speed when the system drops the volume to 10 fps; strike sounds are placed at their simulated times instead of all at once per frame

## v0.1.2 · 2026-11-15

- 同一根管快速连敲 10 下音量一致：前面淡出中的余音也一起压低（模拟器 QA #2 打回项）；420 组压力测试最大峰值差 0.68 dB
- 光照兜底调低，黄铜不再发白，最暗环境也不发黑
- Fast repeated strikes on the same tube stay level (older fading tails are ducked too); 420-case stress test, max spread 0.68 dB
- Lighter light floor: brass keeps its colour, dark rooms stay readable

## v0.1.1 · 2026-11-15

- 混音总线：新音按原音量出，只把旧的余音压低；10 块音名牌连敲峰值差 ≤ 0.9 dB（QA #1 打回项）
- 体积被系统缩放后，内容和拉帆 / 划风手势跟着等比缩放（QA #1）
- 加环境光 + 材质亮度兜底
- Mix bus: new notes at full level, only older tails ducked; volume resize: content and gestures follow the scale; ambient / material light floor

## v0.1.0 · 2026-11-14

- 第一版：桌面风铃体积 + 控制窗（玩 / 怎么玩 / 设置）；黄铜 / 竹 / 玻璃三种风铃，五声音阶；点管、点音名牌、拉帆、划风；四档风；CC0 真实录音；中英双语
- First version: desk chime volume + control window; brass / bamboo / glass; tap, tiles, sail, gust; four wind levels; CC0 recordings; Chinese / English
