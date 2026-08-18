# 后台前视音频识别集成 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不依赖预发布 AAR 的前提下，为播放器建立可替换的 Probe 前视 Provider、签名 v2 绑定的 v1 sidecar、候选合并器和可实时切换的提示/自动策略。

**Architecture:** 现有 PCM matcher 与未来 Probe adapter 都实现内部 `AdAudioSignalProvider`，输出统一 `AdAudioCandidate`；`AdAudioDetectionMultiplexer` 做 generation 校验和去重；`AdSkipPolicyController` 决策提示或自动，`AdSkipCoordinator` 保持唯一 seek authority。第一阶段只实现契约、验签绑定、Noop/Pcm/fake provider，不引入外部 AAR。

**Tech Stack:** Android Java、Java records/interfaces、JUnit、现有 Gson/Tink 签名包、`PlaybackMediaSignalHub`、现有 Gradle Leanback/Mobile 变体。

---

## 文件结构与职责

将创建以下小文件，避免把第三方 API 或策略继续堆入现有大类：

- `app/src/main/java/com/fongmi/android/tv/ad/audio/ProbeRuleSidecar.java`：不可变 v1 sidecar 数据模型和 digest/revision 绑定校验。
- `app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioSignalProvider.java`：Provider、上下文、候选、生命周期和状态契约。
- `app/src/main/java/com/fongmi/android/tv/ad/audio/NoopAdAudioSignalProvider.java`：能力关闭时的无资源实现。
- `app/src/main/java/com/fongmi/android/tv/ad/audio/PcmAdAudioSignalProvider.java`：将现有 `AdAudioConsumer` 适配到 Provider 契约。
- `app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioDetectionMultiplexer.java`：两个 Provider 的校验、去重、排序和状态机。
- `app/src/main/java/com/fongmi/android/tv/ad/audio/AdSkipPolicyController.java`：线程安全的 PROMPT/AUTO 实时策略。
- `app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioRuleSnapshot.java`：增加可选 sidecar 字段并保持旧构造器兼容。
- `app/src/test/java/com/fongmi/android/tv/ad/audio/ProbeRuleSidecarTest.java`：sidecar 绑定、digest、上限和错配测试。
- `app/src/test/java/com/fongmi/android/tv/ad/audio/AdAudioSignalProviderTest.java`：Noop/Pcm 生命周期和旧回调测试。
- `app/src/test/java/com/fongmi/android/tv/ad/audio/AdAudioDetectionMultiplexerTest.java`：候选去重、排序、generation 和 provider 异常测试。
- `app/src/test/java/com/fongmi/android/tv/ad/audio/AdSkipPolicyControllerTest.java`：实时切换语义测试。
- `docs/superpowers/specs/2026-08-18-headless-ad-audio-probe-integration-design.md`：已批准架构和边界。

现有文件只做最小适配：`AdAudioRuleSnapshot.java`、`AdAudioRuntimeController.java`、必要时 `AdSkipCoordinator.java`。第一阶段不修改外部 AAR 依赖配置，也不修改播放器底层 seek。

## Task 1: 用失败测试锁定 sidecar 合同

**Files:**
- Create: `app/src/test/java/com/fongmi/android/tv/ad/audio/ProbeRuleSidecarTest.java`
- Create: `app/src/main/java/com/fongmi/android/tv/ad/audio/ProbeRuleSidecar.java`

- [ ] **Step 1: 写失败测试**

测试必须覆盖：有效绑定通过、source revision 错配拒绝、source digest 错配拒绝、sidecar digest 错配拒绝、算法不是 `spectral-sequence-v1` 拒绝、canonical bytes 超过上限拒绝、数组 getter 防御性复制。测试构造一个最小 canonical sidecar 字节串和 32 字节 digest，不访问文件或网络。

```java
@Test
public void bindingRejectsRevisionMismatch() {
    byte[] rules = bytes("{\"format\":\"ad-audio-probe-rules\"}");
    ProbeRuleSidecar sidecar = ProbeRuleSidecar.verified(
            8L, digest(1), "converter-1", rules, sha256(rules));
    assertThrows(IllegalArgumentException.class,
            () -> sidecar.requireBoundTo(9L, digest(1)));
}
```

- [ ] **Step 2: 运行测试确认失败**

运行：

```text
rtk proxy cmd.exe /d /c gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.ad.audio.ProbeRuleSidecarTest --no-daemon --no-build-cache --console=plain
```

预期：因 `ProbeRuleSidecar` 不存在或绑定方法未实现而失败。

- [ ] **Step 3: 实现最小 sidecar 模型**

`ProbeRuleSidecar` 使用不可变字段：`sourceRevision`、`sourceDigest`、`algorithm`、`converterVersion`、`canonicalRules`、`sidecarDigest`。构造时复制 byte 数组、限制 source revision 为正数、digest 必须 32 字节、算法必须精确为 `spectral-sequence-v1`、canonical bytes 不超过签名包预算，并以 `MessageDigest.isEqual` 校验 canonical bytes 的 SHA-256。`requireBoundTo` 使用常量时间比较校验 revision 和 source digest；失败抛出 `IllegalArgumentException`。

- [ ] **Step 4: 运行测试确认通过**

重复 Task 1 Step 2，预期该测试类全部通过。

- [ ] **Step 5: 提交**

```text
rtk git add app/src/main/java/com/fongmi/android/tv/ad/audio/ProbeRuleSidecar.java app/src/test/java/com/fongmi/android/tv/ad/audio/ProbeRuleSidecarTest.java
rtk git commit -m "feat: define verified probe rule sidecar"
```

## Task 2: 扩展规则快照并锁定验签绑定入口

**Files:**
- Modify: `app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioRuleSnapshot.java`
- Test: `app/src/test/java/com/fongmi/android/tv/ad/audio/ProbeRuleSidecarTest.java`
- Test: existing `app/src/test/java/com/fongmi/android/tv/ad/audio/AdAudioRuleSourceContractTest.java`

- [ ] **Step 1: 写失败测试**

新增断言：旧五参数构造器得到 `null` sidecar；新构造器保存 sidecar；`withProbeSidecar` 只接受 source revision/digest 已绑定的 sidecar；sidecar 不存在时 `probeAvailable()` 为 false。

```java
@Test
public void legacySnapshotRemainsWithoutProbeSidecar() {
    AdAudioRuleSnapshot snapshot = new AdAudioRuleSnapshot(
            "local", "", AudioFingerprintRuleSet.empty(), List.of(), "");
    assertNull(snapshot.probeSidecar());
    assertFalse(snapshot.probeAvailable());
}
```

- [ ] **Step 2: 运行相关测试确认失败**

```text
rtk proxy cmd.exe /d /c gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.ad.audio.AdAudioRuleSourceContractTest --tests com.fongmi.android.tv.ad.audio.ProbeRuleSidecarTest --no-daemon --no-build-cache --console=plain
```

- [ ] **Step 3: 实现兼容扩展**

保留原五参数 public 构造器，内部委托新构造器并传入 `null`；新增 `ProbeRuleSidecar probeSidecar` 字段、六参数构造器和 `probeAvailable()`。不改变 `hasRules()`、`hasError()` 和现有 source/version 语义。规则 source 在把已验签包转换为 snapshot 时负责传入 sidecar；snapshot 自身不读磁盘。

- [ ] **Step 4: 运行测试确认通过**

重复 Task 2 Step 2，预期现有 source contract 与新断言均通过。

- [ ] **Step 5: 提交**

```text
rtk git add app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioRuleSnapshot.java app/src/test/java/com/fongmi/android/tv/ad/audio/ProbeRuleSidecarTest.java app/src/test/java/com/fongmi/android/tv/ad/audio/AdAudioRuleSourceContractTest.java
rtk git commit -m "feat: expose optional probe sidecar in rule snapshots"
```

## Task 3: 建立 Provider 契约与 Noop 实现

**Files:**
- Create: `app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioSignalProvider.java`
- Create: `app/src/main/java/com/fongmi/android/tv/ad/audio/NoopAdAudioSignalProvider.java`
- Create: `app/src/test/java/com/fongmi/android/tv/ad/audio/AdAudioSignalProviderTest.java`

- [ ] **Step 1: 写失败测试**

覆盖以下契约：Noop `start` 不分配资源、不回调候选；disabled 后不回调；重复 close 安全；旧 generation 的 `onHostPosition` 和 reset 不会改变当前状态；候选回调必须带 session/generation。

- [ ] **Step 2: 运行测试确认失败**

```text
rtk proxy cmd.exe /d /c gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.ad.audio.AdAudioSignalProviderTest --no-daemon --no-build-cache --console=plain
```

- [ ] **Step 3: 实现契约**

定义 `ProviderState { DISABLED, IDLE, RUNNING, DEGRADED, CLOSED }`；定义不可变 `SessionContext(sessionId,generation,mediaId,mediaUrl,headers)`、`HostPosition(sessionId,generation,positionMs,durationMs,seekable,live)`、`TimelineReset(sessionId,generation,reason,mediaAnchorMs)`、`AdAudioCandidate(sessionId,generation,ruleId,startMs,endMs,fullMatch,similarity,providerId)`；定义 `Listener.onCandidate` 和 `Listener.onProviderError`。所有构造器拒绝 null、负时间和越界 generation。

`NoopAdAudioSignalProvider` 只保存状态和 listener，不创建线程、解码器、网络连接或规则副本；所有方法在 `close` 后无操作。

- [ ] **Step 4: 运行测试确认通过**

重复 Task 3 Step 2，预期全部通过。

- [ ] **Step 5: 提交**

```text
rtk git add app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioSignalProvider.java app/src/main/java/com/fongmi/android/tv/ad/audio/NoopAdAudioSignalProvider.java app/src/test/java/com/fongmi/android/tv/ad/audio/AdAudioSignalProviderTest.java
rtk git commit -m "feat: add extensible ad audio signal provider contract"
```

## Task 4: 将现有 PCM matcher 适配到 Provider

**Files:**
- Create: `app/src/main/java/com/fongmi/android/tv/ad/audio/PcmAdAudioSignalProvider.java`
- Modify: `app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioRuntimeController.java`
- Test: `app/src/test/java/com/fongmi/android/tv/ad/audio/AdAudioSignalProviderTest.java`

- [ ] **Step 1: 写失败测试**

使用 `PlaybackMediaSignalHub` 和直接执行 executor 构造 PCM provider：匹配事件转换为 `AdAudioCandidate`；不同 session/generation 的 frame 丢弃；lifecycle reset 结束旧 matcher；matcher exception 变成 provider error，不能抛回 hub；sidecar 是否存在不影响 PCM provider。

- [ ] **Step 2: 运行测试确认失败**

```text
rtk proxy cmd.exe /d /c gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.ad.audio.AdAudioSignalProviderTest --no-daemon --no-build-cache --console=plain
```

- [ ] **Step 3: 实现适配器**

`PcmAdAudioSignalProvider` 内部持有现有 `AdAudioConsumer`，把 `Candidate` 的 matcher event 映射到 `AdAudioCandidate`，在 listener 调用前再次检查 session/generation。`start` 创建新的 consumer；`onTimelineReset` 调用 consumer lifecycle；`close` 释放 consumer。不得修改 `PlaybackMediaSignalHub` 的线程和异常隔离行为。

`AdAudioRuntimeController` 的 PCM 激活逻辑改为创建 Pcm provider，但保留现有 public `start/reloadRules/bindUi/refresh/suspend/stop/close` 行为和诊断计数；第一阶段先只接入 Pcm provider，Probe 使用 Noop。

- [ ] **Step 4: 运行测试确认通过**

重复 Task 4 Step 2，并运行既有 controller 测试：

```text
rtk proxy cmd.exe /d /c gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.ad.audio.AdAudioRuntimeControllerTest --tests com.fongmi.android.tv.ad.audio.AdAudioSignalProviderTest --no-daemon --no-build-cache --console=plain
```

- [ ] **Step 5: 提交**

```text
rtk git add app/src/main/java/com/fongmi/android/tv/ad/audio/PcmAdAudioSignalProvider.java app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioRuntimeController.java app/src/test/java/com/fongmi/android/tv/ad/audio/AdAudioSignalProviderTest.java
rtk git commit -m "refactor: adapt pcm matcher to signal provider"
```

## Task 5: 实现候选合并器

**Files:**
- Create: `app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioDetectionMultiplexer.java`
- Create: `app/src/test/java/com/fongmi/android/tv/ad/audio/AdAudioDetectionMultiplexerTest.java`

- [ ] **Step 1: 写失败测试**

测试两个 provider 同一 key 的候选只发一次；FULL 优先于 START；相同等级选择较早 `endMs`；旧 session/generation 丢弃；负时间、end 小于 start、超长候选被拒绝；provider error 不会让 listener 抛异常；`reset` 清空去重集合。

```java
@Test
public void duplicateCandidatesAreEmittedOnce() {
    List<AdAudioSignalProvider.AdAudioCandidate> emitted = new ArrayList<>();
    AdAudioDetectionMultiplexer mux = new AdAudioDetectionMultiplexer(
            new AdAudioSignalProvider.SessionContext(7L, 2L, "m", "https://x", Map.of()),
            emitted::add, error -> fail(error));
    mux.onCandidate(candidate("pcm", 1000L, 2000L, false));
    mux.onCandidate(candidate("probe", 1000L, 2000L, true));
    assertEquals(1, emitted.size());
    assertTrue(emitted.get(0).fullMatch());
}
```

- [ ] **Step 2: 运行测试确认失败**

```text
rtk proxy cmd.exe /d /c gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.ad.audio.AdAudioDetectionMultiplexerTest --no-daemon --no-build-cache --console=plain
```

- [ ] **Step 3: 实现有界状态机**

使用有界 `LinkedHashMap<CandidateKey, CandidateState>`，key 为 `sessionId/generation/ruleId/startMs/endMs`；容量达到上限时删除最早过期项。`onCandidate` 先做上下文和时间校验，再按 fullMatch、similarity、endMs、providerId 的固定顺序比较；更强结果更新原状态但不重复发射。`onProviderError` 只记录错误并通知隔离 listener。`reset` 只接受当前或更新 generation。

- [ ] **Step 4: 运行测试确认通过**

重复 Task 5 Step 2，预期所有 mux 测试通过。

- [ ] **Step 5: 提交**

```text
rtk git add app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioDetectionMultiplexer.java app/src/test/java/com/fongmi/android/tv/ad/audio/AdAudioDetectionMultiplexerTest.java
rtk git commit -m "feat: deduplicate ad audio detection candidates"
```

## Task 6: 实现实时提示/自动策略

**Files:**
- Create: `app/src/main/java/com/fongmi/android/tv/ad/audio/AdSkipPolicyController.java`
- Create: `app/src/test/java/com/fongmi/android/tv/ad/audio/AdSkipPolicyControllerTest.java`
- Modify: `app/src/main/java/com/fongmi/android/tv/ad/audio/AdSkipCoordinator.java`

- [ ] **Step 1: 写失败测试**

测试默认 `PROMPT`；切换到 `AUTO` 后新候选调用 auto sink；已经进入 prompt 的候选不因切换而自动执行；切回 `PROMPT` 后新候选只提示；generation reset 清空 pending；重复 `setMode` 幂等；关闭后不再分发。

- [ ] **Step 2: 运行测试确认失败**

```text
rtk proxy cmd.exe /d /c gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.ad.audio.AdSkipPolicyControllerTest --no-daemon --no-build-cache --console=plain
```

- [ ] **Step 3: 实现策略与 coordinator 入口**

定义 `Mode { PROMPT, AUTO }`，策略控制器保存当前 mode、已展示 key 集合和 listener。候选进入时先判定是否已经 prompt；PROMPT 调用 `promptSink`，AUTO 只对未处理候选调用 `autoSink`。`setMode` 不回放历史候选。`reset(session,generation)` 清理旧集合。

为 `AdSkipCoordinator` 增加一个受控的 `onAutoCandidate(AdAudioConsumer.Candidate)` 入口，复用当前 `targetFor`、clock、seekability、generation 和 cooldown 校验；自动 seek 成功后进入现有 undo 状态，失败只记录 `SEEK_REJECTED`。不改变已有 `Actions` 和手动确认流程。

- [ ] **Step 4: 运行测试确认通过**

```text
rtk proxy cmd.exe /d /c gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.ad.audio.AdSkipPolicyControllerTest --tests com.fongmi.android.tv.ad.audio.AdSkipCoordinatorTest --no-daemon --no-build-cache --console=plain
```

- [ ] **Step 5: 提交**

```text
rtk git add app/src/main/java/com/fongmi/android/tv/ad/audio/AdSkipPolicyController.java app/src/main/java/com/fongmi/android/tv/ad/audio/AdSkipCoordinator.java app/src/test/java/com/fongmi/android/tv/ad/audio/AdSkipPolicyControllerTest.java
rtk git commit -m "feat: support realtime ad skip policy switching"
```

## Task 7: 组合 Runtime controller 与 fake Probe

**Files:**
- Modify: `app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioRuntimeController.java`
- Modify: `app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioSetting.java`
- Modify: `app/src/test/java/com/fongmi/android/tv/ad/audio/AdAudioRuntimeControllerTest.java`
- Modify: `app/src/test/java/com/fongmi/android/tv/player/PlayerManagerTest.java` only if public binding needs coverage

- [ ] **Step 1: 写失败测试**

使用 fake provider 验证：runtime 在规则有效且 UI 已绑定时同时启动 PCM provider 与 Probe provider；sidecar absent 时 Probe 为 Noop；源切换和 suspend 同时停止两者；候选经过 multiplexer 后只调用 coordinator 一次；切换策略会实时影响新候选；provider 抛异常时 PCM 仍然可用。

- [ ] **Step 2: 运行测试确认失败**

```text
rtk proxy cmd.exe /d /c gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.ad.audio.AdAudioRuntimeControllerTest --no-daemon --no-build-cache --console=plain
```

- [ ] **Step 3: 组合实现**

为 runtime 增加注入式 `ProbeProviderFactory`，默认返回 `NoopAdAudioSignalProvider`；生产代码第一阶段不引用外部 AAR。激活时创建 session context、multiplexer、Pcm provider 和 Probe provider；deactivate 按逆序关闭，并清空 generation 状态。`AdAudioSetting` 增加 `isAutoSkipEnabled` 的纯策略读取/写入，但默认值为 false，不在本阶段强制修改现有设置页面。

保持旧构造器委托到默认 factory，避免 `PlayerManager` 和现有测试破坏。所有 provider 的 listener 都通过 runtime 的当前实例/generation guard 后再交给 coordinator。

- [ ] **Step 4: 运行回归测试**

```text
rtk proxy cmd.exe /d /c gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.ad.audio.AdAudioRuntimeControllerTest --tests com.fongmi.android.tv.ad.audio.AdAudioSignalProviderTest --tests com.fongmi.android.tv.ad.audio.AdAudioDetectionMultiplexerTest --tests com.fongmi.android.tv.ad.audio.AdSkipPolicyControllerTest --no-daemon --no-build-cache --console=plain
```

- [ ] **Step 5: 提交**

```text
rtk git add app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioRuntimeController.java app/src/main/java/com/fongmi/android/tv/ad/audio/AdAudioSetting.java app/src/test/java/com/fongmi/android/tv/ad/audio/AdAudioRuntimeControllerTest.java app/src/test/java/com/fongmi/android/tv/player/PlayerManagerTest.java
rtk git commit -m "feat: compose pcm and probe signal providers"
```

## Task 8: 全量验证与文档收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-08-18-headless-ad-audio-probe-integration-design.md` only for measured results
- Modify: this plan file to check completed steps

- [ ] **Step 1: 运行聚焦测试**

```text
rtk proxy cmd.exe /d /c gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.ad.audio.* --no-daemon --no-build-cache --console=plain
```

预期：所有 ad-audio 测试通过，0 failures/errors/skipped。

- [ ] **Step 2: 运行 Leanback 全量测试并统计 XML**

```text
rtk proxy cmd.exe /d /c gradlew.bat :app:testLeanbackArm64_v8aDebugUnitTest --no-daemon --no-build-cache --console=plain
```

读取 `app/build/test-results/testLeanbackArm64_v8aDebugUnitTest/TEST-*.xml`，统计 suite、test、failure、error、skipped；failure/error/skipped 必须均为 0。

- [ ] **Step 3: 运行 Mobile arm64 编译**

```text
rtk proxy cmd.exe /d /c gradlew.bat :app:compileMobileArm64_v8aDebugJavaWithJavac --no-daemon --no-build-cache --console=plain
```

预期：`BUILD SUCCESSFUL`，无 Java 编译错误。

- [ ] **Step 4: 做静态边界检查**

```text
rtk git diff --check
rtk rg -n "m3u8-ad-audio-probe|ProbeSignalProvider" app/src/main/java
```

第一阶段预期主模块不出现外部 AAR 坐标；只允许内部 Provider 名称和 no-op/fake 路径。

- [ ] **Step 5: 更新设计文档验收结果并提交**

记录实际测试计数、sidecar 绑定覆盖、Probe 默认关闭和降级行为；然后执行：

```text
rtk git add docs/superpowers/specs/2026-08-18-headless-ad-audio-probe-integration-design.md docs/superpowers/plans/2026-08-18-headless-ad-audio-probe-integration.md
rtk git commit -m "docs: record headless probe integration verification"
```

## 自审清单

- 规格覆盖：sidecar 信任、Provider 替换、候选去重、实时策略、生命周期、fail-open、资源边界和回滚分别由 Task 1-8 覆盖。
- 无占位符：每个任务给出了文件、失败测试、命令、实现约束和提交命令；没有以“以后处理”代替实现。
- 类型一致：`AdAudioSignalProvider.AdAudioCandidate` 是 multiplexer、policy 和 runtime 共用的候选类型；`AdAudioRuleSnapshot.probeSidecar()` 是唯一 sidecar 入口；Provider 生命周期统一使用 `SessionContext/TimelineReset`。
- 外部依赖边界：第一阶段没有 AAR 坐标、网络规则或运行时远程 JSON；第二阶段才允许增加独立 adapter。
