# 详情直放播放回归修正记录（2026-09-15）

## Recovery anchor

- 目标：详情直放模式点击播放必须进入内嵌全屏播放器，不能误入沉浸融合内嵌播放。
- 基线：`06442ea996fc8e964f51da973507b8ac3fa8d186`。
- 范围：`TmdbDetailActivity.getDetailMode()` 单处模式还原、`DetailModeControllerTest` 回归用例、本任务记录。
- 当前状态：代码修正已应用并通过定向验证；待收口提交与恢复标签。

## 根因与修正

- 根因：`getDetailMode()` 在既无 `detail_mode` 也无 `fusion` 标记时固定返回炫彩详情（`DETAIL_OPEN_ENHANCED`）。详情直放模式通过无标记入口载入后，点击播放误判成沉浸融合并调用 `playInline()`，而不是进入详情全屏播放器。
- 修正：无 `detail_mode` 标记时改用 `Setting.getDetailOpenMode()` 还原用户当前选择；保留融合入口的 `fusion=true` 标记行为，已标记 `detail_mode` 的入口不受影响。
- 防回归：`DetailModeControllerTest.playerDetailMode_keepsFullscreenInlinePlayback` 锁定 `onPlay()` 的 `isFusionMode() -> playInline()` / `isPlayerMode() -> playDetailFullscreen()` 分支和无标记模式还原契约。

## 验证

- `./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests DetailModeControllerTest --tests TmdbDetailActivityLayoutTest --offline` 通过，`BUILD SUCCESSFUL in 15s`；生产 Java 编译随用例执行通过。

## 回滚

- 回退 `TmdbDetailActivity.getDetailMode()` 单处修改和 `DetailModeControllerTest.playerDetailMode_keepsFullscreenInlinePlayback` 用例即可恢复基线行为。
