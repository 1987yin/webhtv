# Exo 硬解卡顿的关键缺失分析

日期：2026-06-27

## 前提修正

用户反馈的卡顿场景始终使用 `Exo` 硬解。因此，不能把主因继续归到“用户选择软解导致 FFmpeg video 抢 4K 解码”。

硬解模式下我们当前代码的 render mode 是：

```java
decode == PlayerEngine.HARD
        ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
```

`EXTENSION_RENDERER_MODE_ON` 会把扩展 renderer 放在系统 MediaCodec renderer 后面。也就是说，在正常硬解路径中，系统 MediaCodec 仍应优先被选择。`NextRenderersFactory` 的 FFmpeg video 仍是风险，但不是当前最关键主结论。

## 最关键缺失部分

最关键缺失不是某一行业务代码，而是：我们没有拿到原版影视当前源码实际期望的完整二开 Media3 硬解产物。

原版影视 `ExoUtil` 直接调用：

```java
DefaultRenderersFactory factory = new DefaultRenderersFactory(App.get()) {
    @Override
    protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
        return ExoUtil.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams);
    }
};
return factory
        .setFfmpegAudioPrefer(audioPrefer)
        .setFfmpegVideoPrefer(videoPrefer)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(renderMode);
```

这说明原版不是官方标准 `DefaultRenderersFactory`，而是一个被二开过的 `androidx.media3.exoplayer.DefaultRenderersFactory`。这条路径即使在硬解下也一定会被调用，因为 ExoPlayer 构建 renderer 列表时必须经过：

```text
ExoPlayer.Builder
  -> RenderersFactory.createRenderers()
  -> DefaultRenderersFactory.buildVideoRenderers()
  -> MediaCodecVideoRenderer / MediaCodecAdapterFactory
```

我们当前本地 `third_party/maven/androidx.media3:1.10.1-fongmi` 的 `DefaultRenderersFactory` 没有：

```java
setFfmpegAudioPrefer(boolean)
setFfmpegVideoPrefer(boolean)
```

公开 `FongMi/media` 仓库也没有这两个 API。由此可以判断：原版作者实际发布或本地构建用的 Media3 产物，和我们当前 `third_party/maven` 中的 Media3 产物不是同一个完整实现。

对“始终硬解”的用户来说，最值得怀疑的是这个二开 Media3 产物里可能包含但我们缺失的硬解相关改动：

- `DefaultRenderersFactory` 的 renderer 插入、排序、过滤逻辑。
- `MediaCodecVideoRenderer` 的 codec 初始化、fallback、丢帧、首帧、Surface 切换处理。
- `DefaultMediaCodecAdapterFactory` 或 codec adapter 的异步/同步队列策略。
- 设备 codec 黑名单、白名单、profile/level 兼容修正。
- 4K HEVC/H264 在部分设备上的 `MediaCodecInfo` 能力判断修正。
- tunnel + SurfaceView 的约束和降级策略。

这部分是硬解用户真正会调用的链路。缺失它，比 FFmpeg 软解是否存在更关键。

## 硬解路径差异总表

| 优先级 | 差异项 | 原版影视 | 我们当前 | 对 Exo 硬解的影响 | 结论 |
|---|---|---|---|---|---|
| P0 | Media3 `DefaultRenderersFactory` 产物 | 源码调用 `setFfmpegAudioPrefer()`、`setFfmpegVideoPrefer()`，说明依赖二开产物 | 当前 `third_party/maven` AAR 不存在这两个 API | 硬解构建 renderer 列表必须经过该类；如果原版二开还包含 codec/renderer 修正，我们全部缺失 | 最关键缺失 |
| P0 | `MediaCodecVideoRenderer` / codec adapter 二开补丁 | 可能在原版私有 Media3 产物中存在 | 当前无法确认具备同等补丁 | 用户始终硬解时，真正解 4K 的就是 MediaCodec renderer；这里的差异可直接导致 dropped frames 或初始化 fallback | 最需要补齐或绕开 |
| P0 | tunnel 与 SurfaceView 强绑定 | `isTunnelingEnabled() = isTunnel() && getRender() == RENDER_SURFACE`；开启 tunnel 会强制 render 为 Surface | `setTunnelingEnabled(PlayerSetting.isTunnel())`，没有确保 Surface | tunnel 在 TextureView 或异常 render 设置下可能造成硬解输出路径异常、掉帧或兼容问题 | 应优先对齐 |
| P1 | RenderersFactory 类型 | 原版使用二开 `DefaultRenderersFactory` | 我们使用 `NextRenderersFactory` | 硬解时扩展 renderer 排在系统后面，一般不抢首选；但 renderer 列表和 fallback 行为仍不同 | 应回到原版风格 |
| P1 | FFmpeg video 扩展 | 公开 `FongMi/media` 的 `ExperimentalFfmpegVideoRenderer.supportsFormat()` 返回不支持 | `NextRenderersFactory` 插入可用 `FfmpegVideoRenderer` | 硬解首选不是它，但系统 codec 初始化失败时可能 fallback 到 FFmpeg video，4K 会像 PPT | 降级为 fallback 风险 |
| P1 | `PlaybackActivity.attachSurface()` | 原版 attach 逻辑简单，播放器为空才 setPlayer | 我们会根据 render 变化 `setPlayer(null)`、`setRender()`、再 setPlayer，并有 shutter 同步 | Surface detach/attach 时机差异可能影响硬解 Surface 生命周期，特别是 4K、全屏、投屏、后台恢复 | 需要继续对齐 |
| P2 | `LoadControl` | 原版没有自定义 `LoadControl` | 我们使用 `buildLoadControl()`，支持用户 buffer 倍数/bytes/backBuffer | 主要影响缓冲，不是解码能力；若已排除 BUFFERING，优先级低于硬解 renderer | 保留观察 |
| P2 | 字幕语言选择 | 原版 `LangUtil.getPreferredTextLanguages()` | 我们 `Locale.getDefault().getISO3Language()` | 不影响 4K 硬解卡顿 | 非关键 |
| P2 | AudioSink | 原版 override `buildAudioSink()`，含 passthrough 输出策略 | 我们当前未在 `NextRenderersFactory` 路径 override AudioSink | 主要影响音频输出/直通；一般不是视频 PPT 主因，但也是原版路径差异 | 后续对齐 |

## 为什么 P0 是“Media3 硬解产物缺失”

如果用户始终硬解，真正决定 4K 是否能稳定播放的是系统硬解 renderer，而不是 FFmpeg video renderer。

系统硬解 renderer 的关键行为包括：

- 选择哪个硬件 decoder，例如 `c2.qti.*`、`OMX.MTK.*`、`OMX.Exynos.*`、`OMX.amlogic.*`。
- 判断当前 4K codec profile/level 是否支持。
- 是否启用 decoder fallback。
- codec 初始化失败后 fallback 到哪个 renderer。
- output surface 是 `SurfaceView` 还是 `TextureView`。
- tunnel mode 是否只在合法输出链路启用。
- decoder queue 和 render thread 是否容易阻塞。
- dropped frame 策略是否激进或保守。

这些都在 Media3 的 `DefaultRenderersFactory`、`MediaCodecVideoRenderer`、`MediaCodecAdapterFactory`、`MediaCodecSelector` 一层。原版源码明确依赖一个我们没有的二开 `DefaultRenderersFactory` API，所以不能假设我们当前 Media3 与原版硬解行为一致。

## 对 `NextRenderersFactory` 的重新定位

在硬解前提下，`NextRenderersFactory` 不是“首要抢解码”的主因，因为 `EXTENSION_RENDERER_MODE_ON` 下系统 MediaCodec 排在前面。

但它仍然不是原版路径，风险在于：

- 它插入的是可用的 FFmpeg video renderer。
- 原版公开 Media3 的 `ExperimentalFfmpegVideoRenderer` 明确不支持任何格式。
- 当系统 MediaCodec 不支持、初始化失败或触发 fallback 时，我们可能落到 FFmpeg video，而原版未必会。
- 它没有复刻原版私有 `setFfmpegVideoPrefer()` 的真实语义。

所以它应归为 P1：不是“硬解用户首选路径”，但会改变 fallback 行为。

## 当前最接近原版的修复方向

1. 优先补齐 `PlayerSetting.isTunnelingEnabled()`，确保 tunnel 只在 SurfaceView 下启用，并在开启 tunnel 时强制 render 为 Surface。
2. 将播放主路径从 `NextRenderersFactory` 逐步改回 `DefaultRenderersFactory` 风格，至少不要让可用 FFmpeg video 进入硬解 fallback 链路。
3. 补一个本地兼容 factory，模拟原版公开行为：保留 FFmpeg audio 能力，视频扩展默认不参与 4K 硬解竞争。
4. 对齐原版 `AudioSink` override，减少播放链路差异。
5. 继续用实机日志确认硬解用户的 `decoderName`、`droppedFrames`、`state=BUFFERING`、`surface/render`、`tunnel`。
6. 若仍是硬解 decoder 且无 BUFFERING 但掉帧严重，再重点追 `MediaCodecVideoRenderer`/adapter 层差异，考虑从公开 Media3 最佳实践中补 codec selector、异步队列策略或设备兼容规则。

## 最短结论

用户始终是 Exo 硬解时，最关键缺失部分是：

```text
原版影视实际依赖的二开 Media3 硬解 renderer/codec 产物
```

具体落在：

```text
DefaultRenderersFactory
MediaCodecVideoRenderer
MediaCodecAdapterFactory
MediaCodecSelector / codec fallback 策略
tunnel + SurfaceView 约束
```

我们当前 `third_party/maven` 里的 Media3 不包含原版源码正在调用的 `setFfmpegAudioPrefer()`、`setFfmpegVideoPrefer()`，因此不能认为硬解链路已经和原版一致。这个缺失比“FFmpeg video 软解抢 4K”更关键。
