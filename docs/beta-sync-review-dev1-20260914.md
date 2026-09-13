# dev1 合并 beta 后复评记录（2026-09-14）

## Recovery anchor

- 目标：在 `dev1` 合并最新 `origin/beta` 后，修复复评发现的合并覆盖/重复逻辑，完成定向验证并交付 PR。
- 基线：合并前 `910a7b819aa7f5d0e9595a87b1dce0ddcdeda94c`；合并提交：`c742953845d3911b2082800d671471f06b8c8421`；复评交付提交：`2805a8df89ebc78957160178e590d64af65782c1`；VC-1 归因复核 guard：`beta-sync-review-dev1-20260914-vc1-classification`。
- 接受标准：源码重复项清零；Backup/历史/播放器焦点行为保持父提交与 beta 新增能力；移动端与 TV Java 编译通过；相关源测试通过；VC-1 依赖问题完成来源判定；原子提交、恢复标签、推送和中文 PR 完成。
- 当前状态：代码复评已提交并推送，PR #270 已创建；本次追加只记录 VC-1 失败归因，不修改测试、依赖、锁、AAR 或运行时行为。
- 已完成：重复监听器清理；恢复 APP_PREFS 的父提交设置键并合入 beta 新键；恢复移动历史删除态集名显示、移动零进度播放历史持久化；恢复 TV episode header 焦点/点击监听、episode range 选段焦点回调；移除已由 `PlayerButtonSetting` 管理的死手工 pan diagnostic 放置；保护 TMDB 跨源续播期间不覆盖历史位置。
- 验证：定向 8 个测试类首轮剩余 6 项，其中 4 项已修复；随后 193 项相关测试全部通过；Mobile/Leanback Arm64 Java 编译全部通过；`git diff --check` 和 guard check 通过。
- 未解决风险：`FfmpegVc1SupportTest` 的两个 Java 行为断言暴露了当前锁定 `nextlib ... ffmpeg901-r3` 的真实接线缺口，而非本次 beta 合并回归。native `libavcodec.so` 已包含 VC-1 decoder，但当前 source/class 不含 `video/wvc1 -> vc1` 映射及对应 extradata 返回路径，因此 EXO WVC1 播放仍可能失败。本轮不把“所有测试通过”作为结论，也不通过删除或放宽测试掩盖该问题。
- 下一步：本次 beta 合并复评无需再改代码或重复测试。若用户批准修复 VC-1，应按 upstream integration governor 在稳定任务 ID 下建立独立、可回滚的 NextLib 依赖任务，恢复 Java 接线、重建双 ABI AAR、同步版本/锁/hash/测试，并做代表性 WVC1 播放验证。

## 变更与证据

### 1. beta 合并覆盖修复

- 移动端 `VideoActivity` 保留单一蓝光菜单点击/长按、全屏和视频触摸监听，避免后注册监听覆盖先注册逻辑。
- TV `VideoActivity` 恢复 episode header 三个按钮的点击及方向键监听；恢复 episode range RecyclerView focus 回调；删除由共享按钮目录接管后的 `placePanDiagnosticAction`。
- `applyActionButtonVisibility()` 先刷新运行时可见性，再应用共享目录，确保 action focus 链使用最新状态。

### 2. 设置与历史

- `Backup.APP_PREFS` 以合并前父提交的完整设置键为基线，合入 beta 的更新源、OCI、蓝光菜单及性能键；138 项无重复。
- 恢复移动/TV 历史卡片在删除态显示不同集名的语义。
- 移动端 `saveHistory` 对已连接且非空播放器按“已播放内容”保存，即使位置尚未推进。
- TMDB 跨源续播 pending 时不写回旧播放器的位置。

### 3. VC-1 依赖复评

当前应用依赖 `1.10.0-0.12.1-fongmi-softload-av3a-ffmpeg901-r3`，锁定 nextlib commit 为 `6ff6cf9d0820382b3c233d018c52e4163b09d345`，FFmpeg commit 为 `177f090e0503b7e013922ca903bde14b1c375f18`。历史实现 `07280efc1a4bc1a842623cbe763b2887b2ba90e0` / `6c1e104b6d573ea80a658dc5ebecb06aeb062e53` 曾补充 Java VC-1 映射和 extradata；当前 r3 patch 文件不再包含该 hunk，当前 AAR 也不满足测试的 Java/`vc1dec.c` 证据。该问题属于上游/native binary provenance 范围，暂不在普通合并复评中伪造修复；应在独立 upstream task 中重新生成并锁定产物后验证。

归因复核结论：

- **不是本次 beta 合并引入。** 祖先提交 `636dbcea31ebe48896f1c6430d706831b3f17d65` 已记录合并前全量测试中完全相同的两个失败，且它是本轮合并前父提交 `910a7b819aa7f5d0e9595a87b1dce0ddcdeda94c` 的祖先。
- 从 `910a7b819aa7f5d0e9595a87b1dce0ddcdeda94c` 经 `c742953845d3911b2082800d671471f06b8c8421` 到 `2805a8df89ebc78957160178e590d64af65782c1`，`FfmpegVc1SupportTest.java`、`gradle/libs.versions.toml`、`third_party/media-lock.json` 的 Git blob 均保持一致，相关 NextLib AAR 路径也没有差异。
- **不是仅有测试预期陈旧。** `codecName_mapsWvc1ToVc1` 与 `extraData_returnsFirstInitializationBlockForWvc1` 直接验证 Java renderer 到 FFmpeg 的必要接线；当前 native 库出现 `SMPTE VC-1` 只能证明 decoder 被编入，不能弥补 Java 层无法选择 `vc1` codec 或传入初始化数据的问题。
- 测试中的 `AAR_PATH` 仍指向历史 `...av3a-r1`，这会降低第三个产物断言对当前 r3 的 provenance 精度，但不影响前两个针对实际 r3 classpath 的失败结论。该路径应与 Java 修复、AAR 重建及 lock/hash 更新一起在独立依赖任务中校正，而不应在本 PR 单独改成“通过”。
- 因此最终分类为：**本轮合并前已存在的依赖集成回归/产物来源不一致；不是 beta 合并回归；测试揭示的 Java 行为缺口仍有效。** PR #270 未触碰相关依赖输入，不新增或扩大该缺口，但也不宣称已修复 VC-1。

## 验证记录

- `./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests ...`（相关 8 类）：首次 196 项中 6 项失败；修复后 193 项全部通过（VC-1 provenance 测试未纳入第二次通过集）。
- `./gradlew :app:compileLeanbackArm64_v8aDebugJavaWithJavac :app:compileMobileArm64_v8aDebugJavaWithJavac --no-daemon --console=plain`：`BUILD SUCCESSFUL`。
- `git diff --check`：通过。
- `bash .codex/scripts/task_guard.sh check`：通过。
- VC-1 归因复核未重复执行已经具有确定结果的失败测试；以祖先提交 `636dbcea31ebe48896f1c6430d706831b3f17d65` 的同失败记录、合并前后 blob 身份一致和相关路径零 diff 作为决定性证据。
- 2026-09-14 01:48 CST 重新 fetch 后，`origin/beta` 仍为 `c5a492261b05b5fdc4323d97a3333a3aa88492b9`，`origin/dev1` 与复评交付提交一致；PR #270 为 OPEN/CLEAN、无新评论或 review。
