package com.fongmi.android.tv.ad.feedback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class ExistingRuleClassifierTest {

    private static final String PLAYLIST_HOST = "v.example.com";

    private static AdIntervalEvidence evidence(String insideHost, List<String> removedRuleIds) {
        return new AdIntervalEvidence(
                10_000, 40_000, StartOrigin.USER_MARKED,
                "site", "站点", "剧名", "线路", "第 1 集",
                PLAYLIST_HOST, "/play/index.m3u8", true,
                List.of(new SegmentFact(3, insideHost, "/seg/3.ts", 6.4, true)),
                List.of(new SegmentFact(0, PLAYLIST_HOST, "/seg/0.ts", 8.0, false)),
                false, false, removedRuleIds, false, List.of(),
                AudioIntervalFact.unavailable(), SpeechIntervalFact.unavailable());
    }

    @Test
    public void suggestsEnablingDisabledRuleInsteadOfCreatingNew() {
        ExistingRuleClassifier.RuleState disabled = new ExistingRuleClassifier.RuleState(
                "builtin:baofeng", "baofeng-preroll", "暴风片头", false, true,
                List.of("ad-cdn.other.com"));

        AdAttribution attribution = ExistingRuleClassifier.classify(
                evidence("ad-cdn.other.com", List.of()),
                new ExistingRuleClassifier.Input(List.of(disabled), false, List.of()));

        assertNotNull(attribution);
        assertEquals(RemediationKind.ENABLE_EXISTING_RULE, attribution.remediation());
        assertEquals(ExistingRuleClassifier.CONFIDENCE_DISABLED_RULE,
                attribution.confidence(), 0.0001f);
        assertTrue(attribution.actionable());
        assertTrue(attribution.evidence().stream().anyMatch(line -> line.contains("暴风片头")));
    }

    @Test
    public void ignoresAlreadyEnabledRule() {
        ExistingRuleClassifier.RuleState enabled = new ExistingRuleClassifier.RuleState(
                "builtin:baofeng", "baofeng-preroll", "暴风片头", true, true,
                List.of("ad-cdn.other.com"));

        assertNull(ExistingRuleClassifier.classify(
                evidence("ad-cdn.other.com", List.of()),
                new ExistingRuleClassifier.Input(List.of(enabled), false, List.of())));
    }

    @Test
    public void ignoresDisabledRuleThatDoesNotCoverInterval() {
        ExistingRuleClassifier.RuleState unrelated = new ExistingRuleClassifier.RuleState(
                "builtin:other", "other-rule", "其他规则", false, true,
                List.of("unrelated-cdn.com"));

        assertNull(ExistingRuleClassifier.classify(
                evidence("ad-cdn.other.com", List.of()),
                new ExistingRuleClassifier.Input(List.of(unrelated), false, List.of())));
    }

    @Test
    public void reportsInvalidRuleAsDiagnosis() {
        ExistingRuleClassifier.RuleState invalid = new ExistingRuleClassifier.RuleState(
                "builtin:broken", "broken-rule", "坏规则", false, false, List.of("ad-cdn.other.com"));

        AdAttribution attribution = ExistingRuleClassifier.classify(
                evidence("ad-cdn.other.com", List.of()),
                new ExistingRuleClassifier.Input(List.of(invalid), false, List.of()));

        assertNotNull(attribution);
        // 编译失败的规则不能建议启用，只能报诊断
        assertEquals(RemediationKind.NONE, attribution.remediation());
        assertEquals(AdCategory.ALREADY_HANDLED, attribution.category());
        assertTrue(attribution.evidence().stream().anyMatch(line -> line.contains("编译失败")));
    }

    @Test
    public void reportsLegacyHeuristicAndProtectingExcludes() {
        AdAttribution attribution = ExistingRuleClassifier.classify(
                evidence("ad-cdn.other.com", List.of()),
                new ExistingRuleClassifier.Input(List.of(), true, List.of(".*/main/.*")));

        assertNotNull(attribution);
        assertEquals(RemediationKind.NONE, attribution.remediation());
        assertTrue(attribution.evidence().stream().anyMatch(line -> line.contains("旧启发式引擎")));
        assertTrue(attribution.evidence().stream().anyMatch(line -> line.contains("正片保护")));
    }

    @Test
    public void reportsIntervalAlreadyRemovedByStructuredRule() {
        AdAttribution attribution = ExistingRuleClassifier.classify(
                evidence("ad-cdn.other.com", List.of("quantum-block")),
                ExistingRuleClassifier.Input.empty());

        assertNotNull(attribution);
        assertTrue(attribution.evidence().stream().anyMatch(line -> line.contains("quantum-block")));
    }

    @Test
    public void abstainsWhenNothingToReport() {
        assertNull(ExistingRuleClassifier.classify(
                evidence("ad-cdn.other.com", List.of()),
                ExistingRuleClassifier.Input.empty()));
        assertNull(ExistingRuleClassifier.classify(null, ExistingRuleClassifier.Input.empty()));
    }
}
