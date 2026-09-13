# dev1 合并 beta 后复评记录（2026-09-14）

## Recovery anchor

- 目标：在 `dev1` 合并最新 `origin/beta` 后，修复复评发现的合并覆盖/重复逻辑，完成定向验证并交付 PR。
- 基线：`fecdda30a2e7dbbf2f9f38184f0206da998811a5`；合并提交：`c742953845d3911b2082800d671471f06b8c8421`；当前 guard：`beta-sync-review-dev1-20260914-final`。
- 接受标准：源码重复项清零；Backup/历史/播放器焦点行为保持父提交与 beta 新增能力；移动端与 TV Java 编译通过；相关源测试通过；VC-1 依赖问题完成来源判定；原子提交、恢复标签、推送和中文 PR 完成。
- 当前变更：`VideoActivity`（移动/TV）、历史适配器/Presenter、`Backup.APP_PREFS`。
- 已完成：重复监听器清理；恢复 APP_PREFS 的父提交设置键并合入 beta 新键；恢复移动历史删除态集名显示、移动零进度播放历史持久化；恢复 TV episode header 焦点/点击监听、episode range 选段焦点回调；移除已由 `PlayerButtonSetting` 管理的死手工 pan diagnostic 放置；保护 TMDB 跨源续播期间不覆盖历史位置。
- 验证：定向 8 个测试类首轮剩余 6 项，其中 4 项已修复；随后 193 项相关测试全部通过；Mobile/Leanback Arm64 Java 编译全部通过；`git diff --check` 和 guard check 通过。
- 未解决风险：`FfmpegVc1SupportTest` 与当前锁定的 `nextlib ... ffmpeg901-r3` AAR/patch provenance 不一致。当前锁定 AAR 的 source 与 class 不含 `video/wvc1` 映射，且 `libavcodec.so` 含 `libavcodec/vc1.c` 而非测试要求的 `vc1dec.c`；历史 `6c1e104b6d...` 版本的 AAR/source 曾包含 Java VC-1 映射，但后续 `79597d2c68...` C10 二进制对齐替换了该产物。未在本轮擅自修改二进制、锁或上游 patch。
- 下一步：保留该依赖问题的明确评审结论，执行最终一次全量曾失败测试类（排除已确定为当前依赖 provenance 不匹配的 VC-1 测试或单独记录其失败）、结构检查、guard finish，然后推送并创建 PR。

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

## 验证记录

- `./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests ...`（相关 8 类）：首次 196 项中 6 项失败；修复后 193 项全部通过（VC-1 provenance 测试未纳入第二次通过集）。
- `./gradlew :app:compileLeanbackArm64_v8aDebugJavaWithJavac :app:compileMobileArm64_v8aDebugJavaWithJavac --no-daemon --console=plain`：`BUILD SUCCESSFUL`。
- `git diff --check`：通过。
- `bash .codex/scripts/task_guard.sh check`：通过。
