package com.fongmi.android.tv.ad.feedback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.M3u8Evidence;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AdFeedbackControllerTest {

    /** 10 个 10s 切片，下标 3-5 来自广告域名，断点在 3 与 6。 */
    private static M3u8Evidence adBlockPlaylist() {
        List<String> segments = new ArrayList<>();
        List<Float> durations = new ArrayList<>();
        List<Boolean> switches = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            boolean ad = i >= 3 && i <= 5;
            segments.add(ad ? "https://ad-cdn.other.com/ads/" + i + ".ts"
                    : "https://v.example.com/seg/" + i + ".ts");
            durations.add(ad ? 6.4f : 10f);
            switches.add(ad);
        }
        return M3u8Evidence.create(segments, List.of(3, 6), durations, switches);
    }

    @Test
    public void showsPendingSessionBeforeAnalysisThenVerdict() {
        FakeHost host = new FakeHost();
        host.playlist = adBlockPlaylist();
        AdFeedbackController controller = new AdFeedbackController(host);

        AdFeedbackSession session = controller.onMarkedInterval(30_000, 60_000);

        assertNotNull(session);
        // 第一屏先出，第二屏带归因
        assertEquals(2, host.shownSessions.size());
        assertFalse(host.shownSessions.get(0).analysisComplete());
        assertTrue(host.shownSessions.get(1).analysisComplete());
        assertTrue(host.shownSessions.get(1).hasActionablePlan());
    }

    @Test
    public void skipsImmediatelyAndRecordsOutcome() {
        FakeHost host = new FakeHost();
        host.playlist = adBlockPlaylist();
        AdFeedbackController controller = new AdFeedbackController(host);

        AdFeedbackSession session = controller.onMarkedInterval(30_000, 60_000);

        assertTrue(session.skipApplied());
        assertEquals(List.of("30000-60000"), host.skipCalls);
        assertEquals(1, controller.diagnostics()
                .count(AdFeedbackDiagnostics.Code.IMMEDIATE_SKIP_APPLIED));
    }

    @Test
    public void recordsRejectedSkipWithoutFailingTheFeedback() {
        FakeHost host = new FakeHost();
        host.playlist = adBlockPlaylist();
        host.skipResult = false;
        AdFeedbackController controller = new AdFeedbackController(host);

        AdFeedbackSession session = controller.onMarkedInterval(30_000, 60_000);

        // 未跳过不影响归因继续
        assertFalse(session.skipApplied());
        assertTrue(host.shownSessions.get(1).hasActionablePlan());
        assertEquals(1, controller.diagnostics()
                .count(AdFeedbackDiagnostics.Code.IMMEDIATE_SKIP_REJECTED));
    }

    @Test
    public void quickReportInfersStartFromNearestDiscontinuity() {
        FakeHost host = new FakeHost();
        host.playlist = adBlockPlaylist();
        host.position = 60_000;
        AdFeedbackController controller = new AdFeedbackController(host);

        AdFeedbackSession session = controller.onQuickReport(host.playlist);

        assertNotNull(session);
        // 断点在下标 3 与 6。广告切片是 6.4s，故下标 6 起点为
        // 3×10 + 3×6.4 = 49.2s，是 60s 之前最近的那个断点。
        assertEquals(49_200L, session.startMs());
        assertEquals(StartOrigin.DISCONTINUITY, session.startOrigin());
        assertEquals(1, controller.diagnostics()
                .count(AdFeedbackDiagnostics.Code.START_INFERRED_FROM_DISCONTINUITY));
    }

    @Test
    public void quickReportPicksEarlierDiscontinuityWhenCloserOneIsAhead() {
        FakeHost host = new FakeHost();
        host.playlist = adBlockPlaylist();
        // 位置落在广告块中间：只有下标 3 的断点在它之前
        host.position = 40_000;
        AdFeedbackController controller = new AdFeedbackController(host);

        AdFeedbackSession session = controller.onQuickReport(host.playlist);

        assertEquals(30_000L, session.startMs());
        assertEquals(StartOrigin.DISCONTINUITY, session.startOrigin());
    }

    @Test
    public void quickReportFallsBackToWindowWithoutPlaylist() {
        FakeHost host = new FakeHost();
        host.position = 200_000;
        AdFeedbackController controller = new AdFeedbackController(host);

        AdFeedbackSession session = controller.onQuickReport(null);

        assertEquals(200_000L - AdIntervalMapper.DEFAULT_FALLBACK_WINDOW_MS, session.startMs());
        assertEquals(StartOrigin.FALLBACK_WINDOW, session.startOrigin());
        assertEquals(1, controller.diagnostics()
                .count(AdFeedbackDiagnostics.Code.START_INFERRED_FROM_FALLBACK));
    }

    @Test
    public void rejectsInvalidIntervals() {
        FakeHost host = new FakeHost();
        AdFeedbackController controller = new AdFeedbackController(host);

        assertNull(controller.onMarkedInterval(40_000, 40_000));
        assertNull(controller.onMarkedInterval(50_000, 40_000));
        assertNull(controller.onMarkedInterval(-1_000, 40_000));
        // 起点超出总时长
        assertNull(controller.onMarkedInterval(700_000, 800_000));
        assertTrue(host.skipCalls.isEmpty());
        assertTrue(host.shownSessions.isEmpty());
    }

    @Test
    public void quickReportAtZeroPositionIsRejected() {
        FakeHost host = new FakeHost();
        host.position = 0;
        AdFeedbackController controller = new AdFeedbackController(host);

        assertNull(controller.onQuickReport(null));
    }

    @Test
    public void surfacesAlreadyHandledDiagnosisWhenHostIsBlacklisted() {
        FakeHost host = new FakeHost();
        host.playlist = adBlockPlaylist();
        host.blacklist = List.of("ad-cdn.other.com");
        AdFeedbackController controller = new AdFeedbackController(host);

        controller.onMarkedInterval(30_000, 60_000);

        AdFeedbackSession result = host.shownSessions.get(1);
        assertTrue(result.verdict().diagnostics().stream()
                .anyMatch(a -> a.category() == AdCategory.ALREADY_HANDLED));
        assertEquals(1, controller.diagnostics()
                .count(AdFeedbackDiagnostics.Code.ALREADY_HANDLED_DETECTED));
    }

    @Test
    public void prefersEnablingDisabledRuleOverNewRule() {
        FakeHost host = new FakeHost();
        host.playlist = adBlockPlaylist();
        host.ruleStates = List.of(new ExistingRuleClassifier.RuleState(
                "builtin:x", "x-rule", "实验规则", false, true, List.of("ad-cdn.other.com")));
        AdFeedbackController controller = new AdFeedbackController(host);

        controller.onMarkedInterval(30_000, 60_000);

        assertEquals(RemediationKind.ENABLE_EXISTING_RULE,
                host.shownSessions.get(1).verdict().preferred().remediation());
    }

    @Test
    public void fallsBackToSessionSkipWhenNoChannelHasEvidence() {
        FakeHost host = new FakeHost();
        // 无 playlist、无基线、无规则：全部通道弃权
        host.legacyActive = true;
        AdFeedbackController controller = new AdFeedbackController(host);

        controller.onMarkedInterval(30_000, 60_000);

        AdFeedbackSession result = host.shownSessions.get(1);
        assertTrue(result.analysisComplete());
        assertFalse(result.hasActionablePlan());
        assertEquals(3, controller.diagnostics()
                .count(AdFeedbackDiagnostics.Code.CHANNEL_ABSTAINED));
    }

    @Test
    public void evidenceFailureStillCompletesWithEmptyVerdict() {
        FakeHost host = new FakeHost();
        host.throwOnFetch = true;
        AdFeedbackController controller = new AdFeedbackController(host);

        controller.onMarkedInterval(30_000, 60_000);

        AdFeedbackSession result = host.shownSessions.get(1);
        assertTrue(result.analysisComplete());
        assertTrue(result.verdict().empty());
        assertEquals(1, controller.diagnostics()
                .count(AdFeedbackDiagnostics.Code.EVIDENCE_COLLECT_FAILED));
    }

    @Test
    public void staleAnalysisDoesNotOverwriteNewerFeedback() {
        FakeHost host = new FakeHost();
        host.playlist = adBlockPlaylist();
        host.deferBackground = true;
        AdFeedbackController controller = new AdFeedbackController(host);

        controller.onMarkedInterval(30_000, 60_000);
        controller.onMarkedInterval(70_000, 90_000);
        // 按倒序执行：先跑第二次的分析，再跑第一次的过期分析
        List<Runnable> pending = new ArrayList<>(host.backgroundTasks);
        host.deferBackground = false;
        pending.get(1).run();
        pending.get(0).run();

        // 最后一次展示必须属于第二个区间
        AdFeedbackSession last = host.shownSessions.get(host.shownSessions.size() - 1);
        assertEquals(70_000L, last.startMs());
    }

    private static final class FakeHost implements AdFeedbackController.Host {
        private final List<String> skipCalls = new ArrayList<>();
        private final List<AdFeedbackSession> shownSessions = new ArrayList<>();
        private final List<Runnable> backgroundTasks = new ArrayList<>();
        private M3u8Evidence playlist;
        private long position = 45_000;
        private boolean skipResult = true;
        private boolean legacyActive;
        private boolean throwOnFetch;
        private boolean deferBackground;
        private List<String> blacklist = List.of();
        private List<ExistingRuleClassifier.RuleState> ruleStates = List.of();

        @Override
        public long positionMs() {
            return position;
        }

        @Override
        public long durationMs() {
            return 600_000L;
        }

        @Override
        public AdEvidenceCollector.Context context() {
            return new AdEvidenceCollector.Context("site", "站点", "剧名", "线路", "第 1 集",
                    "https://v.example.com/play/index.m3u8", true);
        }

        @Override
        public M3u8Evidence fetchEvidence() {
            if (throwOnFetch) throw new IllegalStateException("fetch failed");
            return playlist;
        }

        @Override
        public boolean skipInterval(long startMs, long endMs, String feedbackId) {
            skipCalls.add(startMs + "-" + endMs);
            return skipResult;
        }

        @Override
        public List<String> blacklistedHosts() {
            return blacklist;
        }

        @Override
        public List<String> siteBaselineHosts() {
            return List.of("v.example.com");
        }

        @Override
        public List<String> interfaceCandidateHosts() {
            return List.of();
        }

        @Override
        public String interfaceSourceName() {
            return "";
        }

        @Override
        public List<ExistingRuleClassifier.RuleState> hlsRuleStates() {
            return ruleStates;
        }

        @Override
        public List<String> protectingExcludes() {
            return List.of();
        }

        @Override
        public boolean legacyHeuristicActive() {
            return legacyActive;
        }

        @Override
        public void runBackground(Runnable task) {
            backgroundTasks.add(task);
            if (!deferBackground) task.run();
        }

        @Override
        public void runOnUi(Runnable task) {
            task.run();
        }

        @Override
        public void showSession(AdFeedbackSession session) {
            shownSessions.add(session);
        }
    }
}
