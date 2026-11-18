# Changelog / 更新记录

## v0.1.4 · 2026-11-17

- 声音不再噼啪刺耳：头显上 v0.1.3 的混音线程每秒断音约 70 次（AudioTrack underruns），断口就是噪声。混音写线程改为系统音频优先级（URGENT_AUDIO），混音循环不再加锁、不再分配内存，限幅器每帧的开销从 96 次比较降到常数；缓冲按设备 burst 的整数倍开（不再压到 960 帧）；不再走系统空间化线程（声像本来就是应用自己做的左右声道），每 5 秒记一次 underruns 和混音耗时
- 物理线程降回普通优先级、每秒唤醒 120 次（每次 3 个 1/360 s 步），不再和音频、渲染抢 CPU；体积每帧只在发光值变化时才写材质
- 声音本身和 v0.1.2 逐采样一致（同一段 10 s 大风离线渲染对比，qa/ab-gale10-*）
- No more crackle: on the headset v0.1.3's mix thread underran ~70 times a second. The write thread now runs at URGENT_AUDIO, the mix loop takes no locks and allocates nothing, the limiter's look-ahead minimum is O(1); the buffer is sized in device bursts; the track opts out of the system spatializer thread (the stereo pan was always ours); underruns and mix cost are logged every 5 s
- Physics thread back to normal priority, waking at 120 Hz (3 steps of 1/360 s); materials are written only when a glow changes
- The sound itself is sample-identical to v0.1.2 (same 10 s gale rendered by both versions)

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
