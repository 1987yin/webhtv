# E-SP6：软硬解显示反映实际解码器

- 任务 ID：`E-SP6`
- 类别：Exo 性能专项
- 唯一文档：`docs/E-SP6-decode-label-reflects-actual.md`
- 状态：已实施；待实机确认文案。
- 下一动作：用户在电视上确认「配置」行与控制栏解码按钮是否出现 `硬解→软解`。

## 用户观察到的失败

诊断面板自相矛盾：`配置` 行显示 `EXO / 硬解`，而同一面板的 `视频` 行显示 `decoder ffmpegLavc63.3.100-hevc`（FFmpeg 软解）。用户据此无法判断卡顿是否源于落到软解，排查被迫绕远。

## 根因

`PlayerManager.getDecodeText()` → `engine.getDecodeText()` → `ResUtil.getStringArray(R.array.select_decode)[decode]`，即**只反映用户配置的解码档**，与实际运行的解码器无关。

而两者本就可以合法地不一致：硬解档下 `getVideoRenderMode()` 返回 `EXTENSION_RENDERER_MODE_OFF`，`getFfmpegVideoRenderMode()` 又把它转成 `ON`，FFmpeg 视频渲染器仍作为兜底装入以接 MediaCodec 拒绝的编码（见 [`E-SP5`](E-SP5-exo-ffmpeg-fallback-load-shedding.md)）。设备缺该编码硬解时，解码就落到软解，而标签仍写「硬解」。

实际解码器名字本就在同一面板可得（`PlayerOsdController` 的 `视频` 行已用 `snapshot.videoDecoderName()`），缺的只是判定与对照。

## 实现

新增纯逻辑类 `ExoDecoderKindPolicy`：

- `classify(name)` 判定 `SOFTWARE`／`HARDWARE`／`UNKNOWN`，分两类规则：
  - **前缀**匹配 `omx.google.`、`c2.android.`（平台自带软解）。
  - **子串**匹配 `ffmpeg`、`libvpx`、`libgav1`。用子串而非前缀，是因为部分机型把 FFmpeg 包在 OMX 名字下暴露为 `OMX.ffmpeg.*`，只做前缀匹配会漏判成硬解，正好隐藏本策略要暴露的那种不一致。厂商硬解名字不含这些 token，故子串匹配不会造成误报（已用 `c2.mtk.`／`OMX.amlogic.`／`c2.qti.`／`OMX.SEC.`／`c2.rk.`／`OMX.hisi.` 六个真实厂商名做反向断言）。
  - 大小写不敏感并去空白。默认判 `HARDWARE`，使无法识别的名字**漏报而非误报**。
- 名字缺失时返回 `UNKNOWN` 且**绝不下判断** —— 起播早期尚无解码器名，不能据此误报。
- `decodeLabel(configured, hardwareProfile, decoderName)` 仅在「配置为硬解且确认运行软解」时返回 `硬解→软解`，其余原样返回。

接入点选在 `PlayerManager.getDecodeText()` 这一处漏斗：全部六个控制栏调用点（leanback 的 `CastActivity:175`／`LiveActivity:256`／`VideoActivity:1624`，mobile 的 `LiveActivity:389`／`VideoActivity:1822`，以及 `TmdbDetailActivity:7399`）与 OSD `配置` 行都经由它，改一处即全部一致，无需逐个改动。

`isHardDecode()` **保持只反映配置档**，因为它被用于行为判定（LUT 可用性、回退链等），不能被标签逻辑污染；新增 `isHardProfileRunningSoftware()` 供 UI 使用。

顺带修正两处显示：

- `getSoftDecodeTuneText()` 原先在 `isHardDecode()` 为真时直接返回空，导致硬解回退到软解时看不到降负载状态 —— 而 `E-SP5` 刚让该兜底渲染器启用降负载，恰恰此时最需要确认。改为「配置为硬解且未运行软解」才隐藏。
- 该文案原写「EXO跳帧/滤波/低分辨」，但 `E-SP5` 已把 `skipFrame` 改为 `AVDISCARD_DEFAULT`（不再丢帧），故删去「跳帧」。

## 边界

只改显示与判定，不改任何解码器选路、渲染器构造或回退行为。`isHardDecode()` 的语义未变。

## 验证

- `ExoDecoderKindPolicyTest`：11 个用例，覆盖 nextlib／平台软解／`OMX.ffmpeg.*` 包装／libvpx・libgav1 识别、六个真实厂商硬解名的反向断言、大小写与空白、名字缺失不下判断、仅在硬解档遇软解才报不一致、标签两侧显示。
- `compileLeanbackArm64_v8aDebugJavaWithJavac` 与 `compileMobileArm64_v8aDebugJavaWithJavac` 均 `BUILD SUCCESSFUL`。
- 空值安全：`PlaybackAnalyticsListener.snapshot` 为 `volatile` 且初始 `Snapshot.empty()`，永不为 null；内部名字为空时分类器返回 `UNKNOWN`。

验证边界：单测与编译证明判定与接线正确，**不等同于**已在设备上确认文案渲染与截断表现。控制栏按钮宽度有限，`硬解→软解` 是否被截断需实机确认。

## 回滚

恢复本任务的原子提交即可。不涉及依赖锁、AAR、native 二进制或 patch。
