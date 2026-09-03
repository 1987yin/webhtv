# C10：播放器二进制与依赖输入以上游为准

- 任务 ID：`C10`
- 类别：通用/播放器供应链
- 用户决定：只同步二进制、AAR、lock、MPV native override 和相关构建输入；不修改 Exo、MPV、IJK Java 播放行为。
- 状态：已验证；提交与 recovery tag 由 task guard 原子生成并以 Git 历史为准。
- 本地基线：`dev4@80ded1386a108dc8d1b08610c5b616d4d0f1f77f`
- 上游基线：`fish2018/webhtv:main@ec478b0b697422a7785171c7b51a35b7a526564e`
- 回滚锚点：`80ded1386a108dc8d1b08610c5b616d4d0f1f77f`；提交后使用本任务 recovery tag 或 `git revert`。

## 目标与边界

把以下实际参与播放器供应链的输入恢复为上游版本：

- Nextlib Media3 extension 的 AAR、sources/module sidecar；
- Media3 ExoPlayer 本地 Maven 产物及校验 sidecar；
- `third_party/media-lock.json`、`third_party/ijk-native-lock.json`；
- Media3 E-AC3 patch 的上游文件名和引用；
- 上游不存在且未被正式 MPV 构建脚本引用的 GPU timing/release-acquire/single-backend 补丁；
- Media3 patch 的上游文件名、构建脚本引用和 lock 条目。

明确不在本任务内：

- `app/src/main/java`、MPV/IJK/Exo Java 策略、`PlayerManager` 和运行时 watchdog；
- `gradle/libs.versions.toml` 中与播放器无关的应用依赖整理；
- `app/src/main/jniLibs` 的 sherpa/实验资产、`aimagereader-v556` 离线验证夹具，以及当前已与上游相同的 APK 内 MPV/IJK `.so`；
- MPV/FFmpeg 重新编译。若已打包的 native asset hash 相同，先不做无收益的重建。

## 决策与替代方案

| 方案 | 处理 | 结论 |
| --- | --- | --- |
| 不变 | 保留本地 AAR、lock 和 stale patch 集合 | 拒绝；会继续产生源码/锁/产物来源分叉，且旧 patch 引用已与上游文件名不一致 |
| 完全覆盖 | 将选定的 AAR、校验文件、lock、patch 和 native override 全部取上游树 | 作为二进制输入基线；能恢复可追溯的上游文件集合 |
| WebHTV 窄适配 | 对选定输入取上游；保留 `build_media_deps.sh` 的 Windows/CRLF patch 应用兼容辅助，仅把实际 patch 名称改为上游名称 | 采用；不改变已发布资产的 Java/API 行为，也避免在本机重建时因换行格式失败 |

## 证据

| 问题 | 来源/版本 | 等级 | 结论 |
| --- | --- | --- | --- |
| AAR、Maven sidecar 和锁是否应作为一个单元 | `upstream/main@ec478b0b697422a7785171c7b51a35b7a526564e` 的 `third_party/maven`、`third_party/media-lock.json` | A | 采用上游同一文件集合和 blob，避免坐标、产物和校验值不一致 |
| MPV native 输入是否已经影响当前 APK 资产 | 当前 `HEAD` 与上游的 `app/src/*/assets/mpv-libs`、`libijkplayer.so` blob 对照 | A | 已打包 MPV/IJK 资产相同；本任务只同步未来重建输入，不宣称重新验证设备行为 |
| 本地独有 v556 输入是否被当前构建脚本需要 | `scripts/build_mpv_native.sh`、`scripts/verify_mpv_v556_shader_contract.py` 与 `third_party/mpv-native-overrides` 引用审计 | A | 主构建脚本使用 stable override；v556 文件只被离线验证脚本使用，保留为测试夹具；三项额外 patch 没有正式构建引用，删除它们 |
| IJK lock 精确性 | 本地与上游 `third_party/ijk-native-lock.json` 完整内容对照 | A | 以上游 lock 为准；不在本任务重新构建 IJK native |

## 实施清单

1. 保持已有上游覆盖的 AAR、Maven sidecar、Nextlib module、Media3/Nextlib lock 和 patch 内容。
2. 删除三项未被正式构建引用的本地 MPV patch、旧 Media3 patch 文件；保留 `aimagereader-v556` 离线验证夹具，并使用上游同名 Media3 patch。
3. 将 `scripts/build_media_deps.sh` 的 Media3 patch 路径改为 `media3-exo-pixel-eac3-joc-guard.patch`，保留本地仅用于主机换行兼容的 helper。
4. 保留只服务于 v556 的离线验证脚本和夹具，明确它们不进入正式 MPV 构建；不改变已打包 native asset。
5. 验证选定路径与 `upstream/main` 的文件集合/blob 完全一致；验证 lock JSON、patch 引用、AAR sidecar 和构建脚本引用；运行受影响的 Java 编译和必要的 artifact/ELF 静态门禁，不重建 native。

## 验收与风险

- 选定的 AAR、sources/module/POM sidecar、lock 和 patch 输入与上游完全一致。
- `scripts/build_media_deps.sh` 不再引用不存在的旧 patch 名称；所有 lock 中的 patch 路径均存在且 SHA-256 匹配。
- 不改变 `app/src/main/java`、Exo FFmpeg renderer 选择、MPV/IJK Java 状态机或已发布 APK native asset。
- 风险：恢复上游 AAR/patch 输入后，未来重建可能失去本地未上游化的 codec/容错差异；通过完整路径对照和后续目标设备播放回归暴露，不在本任务隐式保留。
- 风险：IJK lock 从完整 commit 字段回到上游的 tag/短配置记录会降低本地记录粒度；这是“以上游为准”的明确取舍，若要恢复可复现性应另开 lock/provenance 任务。

## Checkpoint 1：2026-09-03 二进制输入对齐进行中

- 已完成：接管上一轮暂存的 21 个 AAR/lock/patch 改动；确认当前 HEAD `80ded1386a108dc8d1b08610c5b616d4d0f1f77f`、上游 `ec478b0b697422a7785171c7b51a35b7a526564e`。
- 已确认：当前已打包 MPV/IJK native asset 与上游相同；本地独有文件为 v556 override、三项 MPV patch 和旧 Media3 patch 名称。
- 当前文件：AAR/lock/sidecar 已按上游暂存，3 个未引用 MPV patch 已删除，Media3 patch 已改为上游名称；v556 验证夹具明确不进入正式构建。
- 下一动作：运行 lock/AAR/patch 引用和 native asset 一致性检查，再做受影响的 Java 编译。

## Checkpoint 2：2026-09-03 正式二进制输入完成对齐

- 选定输入的暂存索引与 `upstream/main@ec478b0b697422a7785171c7b51a35b7a526564e` 对照无 local-only/upstream-only/content-diff：Media3 ExoPlayer 产物、Nextlib module/sources、Media3/Nextlib patch、MPV stable override 和两份 lock 均一致。
- Active Nextlib 坐标仍为 `1.10.0-0.12.1-fongmi-softload-av3a-ffmpeg901-r1`；active AAR SHA-256 为 `89ac342c534a862743dde58ffa2803e9fa1eecd2462c25d6d6b1b5f6ea048d00`，未改变；历史 softload 产物已恢复为上游 blob。
- Media3 patch 已改为 `media3-exo-pixel-eac3-joc-guard.patch`，构建脚本同步改名；`media-lock.json` 中 13 个 patch 路径均存在，两个 lock JSON 可解析，旧 patch 名称引用为 0。
- 已删除上游不存在且未被正式构建脚本引用的三个 MPV patch；已打包 MPV/IJK player asset 与上游 blob 相同，未重建 native。`aimagereader-v556` 夹具和验证脚本仍保留在非发布范围。
- 暂存改动不包含 `app/src/main/java`、JNI、APK native asset 或无关 Gradle 应用依赖；`git diff --cached --check` 已通过。
- 下一动作：运行可用环境下的 MPV asset 门禁和 Mobile/Leanback Arm64 Java 编译，然后完成 C10 提交与 recovery tag。

## Checkpoint 3：2026-09-03 验证通过并进入提交收口

- MPV native 资产门禁：使用 Python 3.13.2、NDK `28.2.13676358` 的 `llvm-readelf`/`llvm-strings` 运行 `scripts/verify_mpv_native_assets.sh --require-elf`；stable Vulkan shader、P2 generic UV、arm64-v8a、armeabi-v7a、SONAME/DT_NEEDED 与打包规则全部通过。
- Java 接口验证：`:app:compileMobileArm64_v8aDebugJavaWithJavac --no-daemon` 通过，`BUILD SUCCESSFUL in 2m 3s`；`:app:compileLeanbackArm64_v8aDebugJavaWithJavac --no-daemon` 通过，`BUILD SUCCESSFUL in 1m 39s`。
- 供应链静态验证：Media3/Nextlib/patch/stable override/lock 选定集合与 `upstream/main` 无文件集合或 blob 差异；两个 lock JSON 可解析，13 个 patch 路径均存在，AAR SHA-256 sidecar 一致，旧 Media3 patch 名称无活动引用。
- 范围验证：无 `app/src/main/java`、JNI、已打包播放器 `.so` 或无关 Gradle 依赖改动；`git diff --cached --check` 与 task guard scope check 通过。
- 既有提示：Gradle deprecation 与 `CXX5202` 32 位 native library warning 仍存在，不是本任务引入。
- 未验证边界：未重建 native、未运行目标电视播放 A/B；当前结论限于上游输入对齐、静态二进制门禁和 Java 接口兼容。
- C10 实施提交：`79597d2c688a804f2f6f4f3b27815f5c60595da8`。
- C10 recovery tag：`recovery/C10-binary-upstream-align/20260903111337-79597d2c688a`。
- 当前状态：二进制/依赖输入上游对齐已完成并闭合；不推送，后续仅在目标设备上补充播放 A/B 证据。

## Checkpoint 4：2026-09-03 C10 文档收口

- 已完成提交：`79597d2c688a804f2f6f4f3b27815f5c60595da8`。
- 已完成恢复标签：`recovery/C10-binary-upstream-align/20260903111337-79597d2c688a`。
- 任务状态：C10 已关闭；本次只同步正式播放器二进制/依赖输入，未修改 Exo、MPV、IJK Java 行为。
