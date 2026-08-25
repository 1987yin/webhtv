# E-SP3：BUFFERING 停滞看门狗

- 任务 ID：`E-SP3`
- 类别：Exo 性能专项
- 唯一文档：`docs/E-SP3-exo-buffering-stall-watchdog.md`
- 状态：已实施并通过目标验证；待实机验收。
- 下一动作：在能复现原症状的设备上确认停滞后自动降级触发；另立单元处理 `E-SP1` 文档记载更正与 `chaquo` Spider 漏 close。

## 用户观察到的失败

电视端最新测试版（beta，含上游合并 `c72d09092a`）：进入播放后转圈长时间不消失，随后整个界面无响应，只能结束应用；EXO 硬解自动。拖拽进度后需要很久才出画面与声音，且「不像加载中」——转圈已消失、进度条显示已缓存完毕，但无声无画。

## 根因

三条独立事实叠加，前两条是既有缺陷，第三条把它放大成不可用。

### 1. BUFFERING 一进入就撤掉启播超时（FongMi 原版既有）

`PlayerManager.onPlaybackStateChanged()`：

```java
if (state != Player.STATE_IDLE) App.removeCallbacks(runnable);
```

`runnable` 即 `onPlaybackTimeout`（`Constant.TIMEOUT_PLAY` = 15s）。`STATE_BUFFERING` 也满足 `!= STATE_IDLE`，因此播放器一进缓冲就解除唯一的启播保护。`git log -L` 追溯显示该行来自最初导入提交，非本地引入。

### 2. BUFFERING 停滞没有任何通用兜底

- `PlaybackBufferingTracker` 只统计 `rebufferCount`/`rebufferTotalMs`，不含超时或恢复动作。
- 唯一的停滞看门狗 `ExoTunnelingProgressWatchdog`（3s）要求隧道启用 + 已出首帧 + `STATE_READY`；而 `PlayerSetting.isTunnel()` 默认 false，命中不了。

因此一旦停在 BUFFERING，`fallbackPlayback()` 的自动降解码/切内核链（用户正用「硬解自动」）永不触发，表现为彻底卡死而非自动切换。

### 3. 首帧到达时再撤一次超时（上游 `f2721c43b6` 引入，本次回归来源）

```java
public void onRenderedFirstFrame() {
    if (isExo()) App.removeCallbacks(runnable);
```

上游意图是避免「慢音频轨让 Exo 短暂留在 BUFFERING 时被误判为连接超时」。但首帧 ≠ 可播放：项目自身的 `PlaybackStartupPolicy.resolve()` 在 `!ready` 时返回 `Completion.NONE`，即必须 `STATE_READY` 才算起播完成。音频轨若不只是「短暂」慢，就永久停在 BUFFERING 且再无保护。

该提交的 Task-Guard 为 `exo-dv7-timeout-after-first-frame`（DV7 任务），但改的是通用启播超时，影响所有 Exo 播放。`E-SP1` 文档将其计入自身实现，却记载「超时取消保持不变」且声明「不修改 `STATE_READY`、`PlaybackStartupPolicy`、缓冲参数、seek」——记载与代码不符，本任务同时纠正该记录。

### seek 侧的连带表现

- `PlayerManager.seekTo()` 无任何超时兜底，只能被动等 LoadControl 阈值；重缓冲阈值上限 `ExoPlaybackThresholdPolicy.MAX_STREAMING_REBUFFER_MS` = 15s。
- 电视版 `VideoActivity.hideSeekProgressIfReady()` 是一次性 500ms 回调，且仅在 `STATE_READY` 时收圈，超时不重试；此后收圈只靠 `onStateChanged(STATE_READY)`。
- 上游 `PlaybackActivity.onExoFirstFrame()` 在首帧即把 `R.id.progress` 设为 GONE，绕过 `VideoActivity.hideProgress()`。于是 seek 后转圈被提前抹掉，而播放器仍在缓冲，观感即「画面静止、无转圈、无声音」。
- `getBufferedPercentage()` 已改为计入 `PlaybackDiskBufferStore` 磁盘区间，与 Exo 实际起播判据（内存 SampleQueue 时长）不一致，故进度条显示「已缓存完毕」。

## 方案对比

| 方案 | 说明 | 结论 |
| --- | --- | --- |
| 无改动 | 保持现状 | 拒绝。BUFFERING 停滞无兜底，自动降级链失效。 |
| 回退 `f2721c43b6` | 删掉首帧撤超时那行 | 拒绝。会退回上游要解决的「慢音频轨误报连接超时」。 |
| 直接放宽 `TIMEOUT_PLAY` | 把 15s 调大 | 拒绝。既不解决停滞无兜底，又拖慢真实失败的降级。 |
| **换防（采纳）** | 首帧到达时把「启播超时」换成「BUFFERING 停滞看门狗」，而非解除保护 | 采纳。既保住上游意图，又消除裸奔窗口。 |

## 设计

新增纯逻辑类 `ExoBufferingStallWatchdog`，形态对齐既有 `ExoTunnelingProgressWatchdog`（`arm`/`observe`/`shouldTimeout`/`reset`），便于单测且不持有 Player 引用。

判据必须**同时**满足两条，缺一不可：

- `positionMs` 未前进（正常缓冲时 position 本就不动，只看它会误杀）
- `bufferedPositionMs` 未增长（仍在进数据就不算停滞）

阈值取 `STALL_TIMEOUT_MS = 20_000`，必须**大于**重缓冲阈值上限 15s，否则会在 LoadControl 正常填充缓冲期间误杀。

第三个条件用于避开 `E-SP2` 的延后 Cues：远程 MKV 首次 seek 会先取文件尾部 Cues，期间 position 与 buffered 都合法地不动且产不出样本，仅用前两条会把一次正常抓取误判为停滞并触发多余降级。因此当 `player.isLoading()` 为真时改用更长的 `LOADING_STALL_TIMEOUT_MS = 60_000` 上限，而不是直接豁免——否则挂死的 socket 读会让 `isLoading()` 永真，看门狗永不触发，恰好放过本任务要修的场景。

`bufferedPositionMs` 必须取 `player.getBufferedPosition()` 原生值，**不可**用 `getEffectiveBufferedPosition()`——后者含磁盘区间，会让停滞看起来仍在增长。

停滞入口独立于 `onPlaybackTimeout()`，只复用 `fallbackPlayback(e)` + `callback.onError()`。不得复用 `retryExoDv7FirstFrameTimeout()`、`retryLutWarmupByRefresh()`、`completeIjkBufferManagedReload(false, "timeout", ...)`——这些是启播语义，播放中途停滞时重复触发会引出新问题（尤其 DV7 那条会再走一次 rebuild + 1200ms 延迟启动）。

## 边界

只改「超时保护的装/撤时机」与「新增停滞检测」。不修改：`STATE_READY` 语义、`PlaybackStartupPolicy`、缓冲参数与阈值策略、解码器/渲染器选择、DV7→P8.1/HDR10 fallback、TrueHD/直通、Range/cache、软解降载、MPV 输出策略、`setSeekParameters`（seek 精度属产品取舍，另议）。

不在本任务修的已知问题（另立单元）：

- `chaquo/src/main/java/com/fongmi/chaquo/Spider.java` 漏 close 的临时 PyObject（第 40/41/121/129/138/147 行）。dev2 与 beta 字节相同，非本次回归来源。
- `E-SP2` 延后 Cues 的实机性能/seek 验收（索引第 7 行标注仍未完成，却已随 beta 分发）。
- `getBufferedPercentage()` 计入磁盘区间导致进度条显示「已缓存完毕」，与实际起播判据不一致；同时使 `PlayerOsdController` 的「缓冲偏少」提示不再触发。
- `retryExoDv7FirstFrameTimeout()` 的 1200ms 延迟回调若命中 `seq != prepareSeq || spec != target || engine != exo || player == null` 提前返回，则既不 `engine.start()` 也不重投 `runnable`，而函数入口已执行 `App.removeCallbacks(runnable)` 与 `rebuildPlayer(true)`——播放器已重建但从未启动且无超时保护。缺一个补投看门狗的 else 分支。本任务的停滞看门狗不覆盖该路径（那里停在 IDLE 而非 BUFFERING）。

## 验收标准

- `ExoBufferingStallWatchdogTest` 覆盖：position 与 buffered 均不动才超时；仅 buffered 增长不超时；仅 position 前进不超时；`reset` 后不超时；未 `arm` 时不超时。
- Mobile 与 Leanback arm64 debug Java 编译通过。
- 代码层可证：任一 BUFFERING 停滞路径都存在已装载的看门狗（首帧后、seek 后、起播中）。

验证边界：编译与单测证明判据与装撤时机正确，不等同于设备上的首帧耗时、音频初始化或缓冲性能结论。实机验收需在受影响设备上复现原症状后确认自动降级触发。

## 验证结果

- `./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests "...ExoBufferingStallWatchdogTest"`：`tests="10" failures="0" errors="0" skipped="0"`。
- `./gradlew :app:compileLeanbackArm64_v8aDebugJavaWithJavac`：`BUILD SUCCESSFUL`。
- `./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac`（随单测任务执行）：`BUILD SUCCESSFUL`。
- `git diff --check`：通过。

代码层可证的装载覆盖：起播中（`setMediaItem` 的 `runnable` 撤除后由 BUFFERING 分支接手）、首帧未到 READY（`onRenderedFirstFrame` 换防）、seek 后（`seekTo` 尾部装载）、播放中途重缓冲（BUFFERING 分支）。`reset()` 与 `release()` 均已取消轮询，避免越过会话存活。

## 回滚

恢复本任务的原子提交即可。不涉及依赖锁、AAR、native 二进制或 patch。
