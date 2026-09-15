# 详情直放播放回归修正记录（2026-09-15）

## Recovery anchor

- 目标：详情直放模式点击播放必须进入内嵌全屏播放器，不能误入沉浸融合内嵌播放。
- 基线：`06442ea996fc8e964f51da973507b8ac3fa8d186`。
- 范围：`TmdbDetailActivity.getDetailMode()` 单处模式还原、`DetailModeControllerTest` 回归用例、本任务记录。
- 当前状态：模式还原修正已提交；运行时复测发现首播待全屏状态被异步播放器启动清掉，已完成二次修正、测试和模拟器验证；待收口提交与恢复标签。

## 根因与修正

- 根因：`getDetailMode()` 在既无 `detail_mode` 也无 `fusion` 标记时固定返回炫彩详情（`DETAIL_OPEN_ENHANCED`）。详情直放模式通过无标记入口载入后，点击播放误判成沉浸融合并调用 `playInline()`，而不是进入详情全屏播放器。
- 修正：无 `detail_mode` 标记时改用 `Setting.getDetailOpenMode()` 还原用户当前选择；保留融合入口的 `fusion=true` 标记行为，已标记 `detail_mode` 的入口不受影响。
- 防回归：`DetailModeControllerTest.playerDetailMode_keepsFullscreenInlinePlayback` 锁定 `onPlay()` 的 `isFusionMode() -> playInline()` / `isPlayerMode() -> playDetailFullscreen()` 分支和无标记模式还原契约。

## 验证

- `./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests DetailModeControllerTest --tests TmdbDetailActivityLayoutTest --offline` 通过，`BUILD SUCCESSFUL in 15s`；生产 Java 编译随用例执行通过。

## 运行时复测与二次修正（2026-09-15）

- 首次设备复测：模拟器 `192.168.50.3:5559` 已安装包含提交 `01c5798b0f` 的 APK，设置 `detail_open_mode=4`；点击“继续播放”后播放器仍以 252dp 内嵌卡片显示，详情页内容可见，说明前一版 Java 分支测试不足以覆盖首播异步时序。
- 根因：`playDetailFullscreen()` 在首播无视频尺寸时先设置 `inlineFullscreenDeferred=true`，但 `startInlinePlayer()` 随后无条件清零该标记；`STATE_READY` 到达后无法调用 `enterInlineFullscreen()`。
- 修正：在 `playDetailFullscreen()` 作出首播形态决策前清除旧的 deferred 状态；保留本次首播设置的状态，不在异步 `startInlinePlayer()` 中覆盖它。
- 防回归：`DetailModeControllerTest.playerDetailMode_preservesDeferredFullscreenUntilPlayerReady` 先在旧代码上失败，再在修正后通过，锁定 deferred 状态的生命周期。
- 最终验证：`./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.ui.detail.DetailModeControllerTest --tests com.fongmi.android.tv.ui.activity.TmdbDetailActivityLayoutTest --offline` 通过，`BUILD SUCCESSFUL in 9s`；`git diff --check` 通过。
- 设备验证：重新打包并安装 `app-mobile-arm64_v8a-debug.apk` 后，从首页打开“早春晴朗”详情，点击“继续播放”；截图 `/tmp/webhtv-detail-direct-fixed.png` 显示播放器铺满 `1920x1080` 窗口，详情背景不再可见，播放标题为“早春晴朗：2. 职场不是过家家”，播放正常。

## 回滚

- 回退 `TmdbDetailActivity.getDetailMode()` 单处修改和 `DetailModeControllerTest.playerDetailMode_keepsFullscreenInlinePlayback` 用例即可恢复基线行为。
