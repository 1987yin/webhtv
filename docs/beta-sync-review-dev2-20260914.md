# dev2 beta 合并后复评记录（2026-09-14）

## Recovery anchor

- 目标：合并远端 `origin/beta` 最新代码，复评增量及已提交未推送改动，必要时修复并验证，最后提交、推送、创建中文 PR，并拉取远端最新代码。
- 接受标准：远端 beta 增量已合入且无冲突；相对合并基线的 beta 侧无内容差异；本次改动通过移动端定向测试和 Leanback Java 编译；原子提交、恢复标签、推送、中文 PR、PR 后回拉完成。
- 当前状态：复评记录待随任务守卫收口；后续交付动作待本记录提交后继续。
- 下一步：执行任务守卫 finish，然后推送 `dev2`、创建 base `beta` / head `dev2` 的中文 PR，并在 PR 后拉取远端最新代码。

## 评审范围与结论

- 远端 `origin/beta` @ `058a5e22d1`；本地 `dev2` @ `8ae8bd1ce6`。`git fetch origin beta` 已执行。
- 本轮合并 `fcf0285d0e5f2d470999538dad797a54d4a8c690`，无冲突。该 beta 提交只删除两个陈旧 `FfmpegVc1SupportTest` Java 行为断言并更新 `dev1` 评审文档；当前测试仍保留双 ABI `libavcodec.so` 中 VC-1 decoder 的存在性校验，未把依赖接线缺口伪装成已修复。
- 相对合并基线 `fcf0285d0e5f2d470999538dad797a54d4a8c690`，beta 侧没有非 merge 内容提交；`git diff --stat $(git merge-base dev2 origin/beta)..origin/beta` 为空。当前 `dev2` 侧只有 `1b2dda6b98` 的 3 个路径：leanback/mobile `VideoActivity` 的 `shouldRevealShellWhileLoading()` 让影视原生模式也由播放器窗口表达加载态，以及对应 `VideoActivityLayoutTest` 防回归断言。
- 复评通过：上述 Java/测试改动与合并基线一致，`git diff --check` 无问题；`VideoActivityLayoutTest` 覆盖移动端行为，Leanback Java 编译覆盖 TV 端同源逻辑。

## 验证证据

- `git fetch origin beta`：远端状态确认。
- `git merge fcf0285d0e5f2d470999538dad797a54d4a8c690`：`Merge made by the 'ort' strategy.`，无冲突。
- `./gradlew :app:testMobileArmeabi_v7aDebugUnitTest --tests VideoActivityLayoutTest`：`BUILD SUCCESSFUL in 24s`。
- `./gradlew :app:compileLeanbackArm64_v8aDebugJavaWithJavac`：`BUILD SUCCESSFUL in 8s`。
- `git diff --check`：通过。
- `git diff --stat $(git merge-base dev2 origin/beta)..origin/beta`：空，证明 beta 增量内容已全部收敛到本地。
