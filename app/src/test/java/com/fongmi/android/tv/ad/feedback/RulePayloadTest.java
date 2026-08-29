package com.fongmi.android.tv.ad.feedback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class RulePayloadTest {

    @Test
    public void emptyPayloadIsNotActionable() {
        AdAttribution plan = new AdAttribution("hls", AdCategory.DISCONTINUITY_BLOCK, 0.9f,
                RiskLevel.MEDIUM, List.of("证据"), RemediationKind.HLS_STRUCTURED_RULE,
                RulePayload.empty());

        // 机制可执行但没有数据可写，必须视为不可落地
        assertFalse(plan.actionable());
    }

    @Test
    public void diagnosticConstructorDefaultsToEmptyPayload() {
        AdAttribution plan = new AdAttribution("domain", AdCategory.ALREADY_HANDLED, 0.95f,
                RiskLevel.LOW, List.of("已在黑名单"), RemediationKind.NONE);

        assertTrue(plan.payload().isEmpty());
        assertFalse(plan.actionable());
    }

    @Test
    public void hostPayloadMakesPlanActionable() {
        AdAttribution plan = new AdAttribution("domain", AdCategory.THIRD_PARTY_CDN_SEGMENT, 0.6f,
                RiskLevel.LOW, List.of("证据"), RemediationKind.HOST_BLACKLIST,
                RulePayload.ofHosts(List.of("ad.example.com")));

        assertTrue(plan.actionable());
        assertEquals(List.of("ad.example.com"), plan.payload().hosts());
    }

    @Test
    public void ruleKeyPayloadIsNotEmpty() {
        RulePayload payload = RulePayload.ofRuleKey("builtin|pkg|baofeng");

        assertFalse(payload.isEmpty());
        assertEquals("builtin|pkg|baofeng", payload.ruleKey());
    }

    @Test
    public void durationRangePresenceIsExplicit() {
        assertFalse(RulePayload.ofHosts(List.of("a.com")).hasDurationRange());
        assertTrue(RulePayload.ofHlsRule(List.of("v.a.com"), List.of("ad.a.com"),
                6.3, 6.5, true, true, 2).hasDurationRange());
    }

    @Test
    public void nullFieldsBecomeEmptyCollections() {
        RulePayload payload = new RulePayload(null, null, null, null, null,
                Double.NaN, Double.NaN, false, false, -5);

        assertTrue(payload.playlistHostSuffixes().isEmpty());
        assertTrue(payload.hosts().isEmpty());
        assertTrue(payload.regex().isEmpty());
        assertTrue(payload.exclude().isEmpty());
        assertEquals("", payload.ruleKey());
        assertEquals(0, payload.minimumSignals());
        assertTrue(payload.isEmpty());
    }

    @Test
    public void hlsClassifierScopesRuleToPlaylistHostAndKeepsMinimumTwoSignals() {
        List<SegmentFact> inside = List.of(
                new SegmentFact(10, "ad-cdn.other.com", "/seg/10.ts", 6.4, true),
                new SegmentFact(11, "ad-cdn.other.com", "/seg/11.ts", 6.4, false));
        List<SegmentFact> outside = List.of(
                new SegmentFact(0, "v.example.com", "/seg/0.ts", 8.0, false),
                new SegmentFact(12, "v.example.com", "/seg/12.ts", 8.0, false));
        AdIntervalEvidence evidence = new AdIntervalEvidence(
                80_000, 92_800, StartOrigin.USER_MARKED,
                "site", "站点", "剧名", "线路", "第 1 集",
                "v.example.com", "/play/index.m3u8", true,
                inside, outside, true, true, List.of(), false, List.of(),
                AudioIntervalFact.unavailable(), SpeechIntervalFact.unavailable());

        AdAttribution plan = HlsSegmentClassifier.classify(evidence);
        RulePayload payload = plan.payload();

        // 作用域收窄到本站，避免规则污染其他站点
        assertEquals(List.of("v.example.com"), payload.playlistHostSuffixes());
        assertEquals(List.of("ad-cdn.other.com"), payload.hosts());
        assertTrue(payload.requireDiscontinuity());
        assertTrue(payload.requireCrossDomain());
        // 永不生成 minimumSignals=1 的宽泛规则
        assertTrue(payload.minimumSignals() >= 2);
        assertTrue(plan.actionable());
    }

    @Test
    public void durationRangeOnlyAppearsWhenOutlierSignalHolds() {
        // 区间内外时长一致：不给时长条件，避免只靠固定时长删片
        List<SegmentFact> inside = List.of(
                new SegmentFact(10, "ad-cdn.other.com", "/ads/10.ts", 8.0, true),
                new SegmentFact(11, "ad-cdn.other.com", "/ads/11.ts", 8.0, false));
        List<SegmentFact> outside = List.of(
                new SegmentFact(0, "v.example.com", "/seg/0.ts", 8.0, false),
                new SegmentFact(12, "v.example.com", "/seg/12.ts", 8.0, false));
        AdIntervalEvidence evidence = new AdIntervalEvidence(
                80_000, 96_000, StartOrigin.USER_MARKED,
                "site", "站点", "剧名", "线路", "第 1 集",
                "v.example.com", "/play/index.m3u8", true,
                inside, outside, true, true, List.of(), false, List.of(),
                AudioIntervalFact.unavailable(), SpeechIntervalFact.unavailable());

        RulePayload payload = HlsSegmentClassifier.classify(evidence).payload();

        assertFalse(payload.hasDurationRange());
    }

    @Test
    public void existingRuleAttributionCarriesRuleKey() {
        ExistingRuleClassifier.RuleState disabled = new ExistingRuleClassifier.RuleState(
                "builtin|pkg|baofeng", "baofeng", "暴风片头", false, true,
                List.of("ad-cdn.other.com"));
        AdIntervalEvidence evidence = new AdIntervalEvidence(
                10_000, 40_000, StartOrigin.USER_MARKED,
                "site", "站点", "剧名", "线路", "第 1 集",
                "v.example.com", "/play/index.m3u8", true,
                List.of(new SegmentFact(3, "ad-cdn.other.com", "/seg/3.ts", 6.4, true)),
                List.of(new SegmentFact(0, "v.example.com", "/seg/0.ts", 8.0, false)),
                false, false, List.of(), false, List.of(),
                AudioIntervalFact.unavailable(), SpeechIntervalFact.unavailable());

        AdAttribution plan = ExistingRuleClassifier.classify(evidence,
                new ExistingRuleClassifier.Input(List.of(disabled), false, List.of()));

        assertEquals("builtin|pkg|baofeng", plan.payload().ruleKey());
        assertTrue(plan.actionable());
    }

    @Test
    public void mergedAttributionKeepsPayloadOfChosenMechanism() {
        AdAttribution hls = new AdAttribution("hls", AdCategory.THIRD_PARTY_CDN_SEGMENT, 0.6f,
                RiskLevel.MEDIUM, List.of("hls 证据"), RemediationKind.HLS_STRUCTURED_RULE,
                RulePayload.ofHls(List.of("v.example.com"), List.of("hls-host.com"), 2));
        AdAttribution domain = new AdAttribution("domain", AdCategory.THIRD_PARTY_CDN_SEGMENT, 0.5f,
                RiskLevel.LOW, List.of("domain 证据"), RemediationKind.HOST_BLACKLIST,
                RulePayload.ofHosts(List.of("domain-host.com")));

        AdAttributionArbiter.Verdict verdict = AdAttributionArbiter.arbitrate(List.of(hls, domain));
        AdAttribution preferred = verdict.preferred();

        // 合并后取成本更低的 HOST_BLACKLIST，载荷必须同步取它的，不能错配
        assertEquals(RemediationKind.HOST_BLACKLIST, preferred.remediation());
        assertEquals(List.of("domain-host.com"), preferred.payload().hosts());
    }
}
