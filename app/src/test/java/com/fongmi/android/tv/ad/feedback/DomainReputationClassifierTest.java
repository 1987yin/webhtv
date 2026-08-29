package com.fongmi.android.tv.ad.feedback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class DomainReputationClassifierTest {

    private static final String PLAYLIST_HOST = "v.example.com";

    private static AdIntervalEvidence evidenceWith(String... insideHosts) {
        List<SegmentFact> inside = new java.util.ArrayList<>();
        for (int i = 0; i < insideHosts.length; i++) {
            inside.add(new SegmentFact(i, insideHosts[i], "/seg/" + i + ".ts", 6.4, false));
        }
        return new AdIntervalEvidence(
                10_000, 40_000, StartOrigin.USER_MARKED,
                "site", "站点", "剧名", "线路", "第 1 集",
                PLAYLIST_HOST, "/play/index.m3u8", true,
                inside, List.of(new SegmentFact(9, PLAYLIST_HOST, "/seg/9.ts", 8.0, false)),
                false, false, List.of(), false, List.of(),
                AudioIntervalFact.unavailable(), SpeechIntervalFact.unavailable());
    }

    @Test
    public void blacklistedHostYieldsAlreadyHandledDiagnosis() {
        DomainReputationClassifier.Input input = new DomainReputationClassifier.Input(
                List.of("doubleclick.net"), List.of(PLAYLIST_HOST), List.of(), "");

        AdAttribution attribution = DomainReputationClassifier.classify(
                evidenceWith("ads.doubleclick.net"), input);

        assertNotNull(attribution);
        // 已在黑名单却仍被看到，说明拦截路径没覆盖播放器直连请求
        assertEquals(AdCategory.ALREADY_HANDLED, attribution.category());
        assertEquals(RemediationKind.NONE, attribution.remediation());
        // ALREADY_HANDLED 的得分强制归零
        assertEquals(0f, attribution.score(), 0.0001f);
    }

    @Test
    public void hostOutsideSiteBaselineIsThirdPartyCdn() {
        DomainReputationClassifier.Input input = new DomainReputationClassifier.Input(
                List.of(), List.of(PLAYLIST_HOST, "cdn.example.com"), List.of(), "");

        AdAttribution attribution = DomainReputationClassifier.classify(
                evidenceWith("ad-cdn.other.com"), input);

        assertNotNull(attribution);
        assertEquals(AdCategory.THIRD_PARTY_CDN_SEGMENT, attribution.category());
        // 域名黑名单只在 WebView 层生效，拦不住播放器直连的切片；
        // 要真正删掉这些切片必须落成 HLS 结构化规则
        assertEquals(RemediationKind.HLS_STRUCTURED_RULE, attribution.remediation());
        assertEquals(RiskLevel.LOW, attribution.risk());
        assertEquals(DomainReputationClassifier.CONFIDENCE_UNKNOWN_HOST,
                attribution.confidence(), 0.0001f);
        // 规则必须限定到本站 playlist 域名，否则会污染其他站点
        assertEquals(List.of(PLAYLIST_HOST), attribution.payload().playlistHostSuffixes());
        assertEquals(List.of("ad-cdn.other.com"), attribution.payload().hosts());
        // hostSuffixes + requireCrossDomain 两个信号，满足 minimumSignals >= 2
        assertTrue(attribution.payload().requireCrossDomain());
        assertEquals(2, attribution.payload().minimumSignals());
        assertTrue(attribution.actionable());
    }

    @Test
    public void interfaceCandidateAddsConfidenceBonus() {
        DomainReputationClassifier.Input input = new DomainReputationClassifier.Input(
                List.of(), List.of(PLAYLIST_HOST), List.of("ad-cdn.other.com"), "饭太硬");

        AdAttribution attribution = DomainReputationClassifier.classify(
                evidenceWith("ad-cdn.other.com"), input);

        assertNotNull(attribution);
        assertEquals(DomainReputationClassifier.CONFIDENCE_UNKNOWN_HOST
                + DomainReputationClassifier.BONUS_INTERFACE_CANDIDATE,
                attribution.confidence(), 0.0001f);
        assertTrue(attribution.evidence().stream().anyMatch(line -> line.contains("饭太硬")));
    }

    @Test
    public void abstainsWithoutBaselineToAvoidFirstPlayMisjudgement() {
        // 首次播放该站，没有任何基线数据：不能断言域名异常
        DomainReputationClassifier.Input input = DomainReputationClassifier.Input.empty();

        assertNull(DomainReputationClassifier.classify(evidenceWith("ad-cdn.other.com"), input));
    }

    @Test
    public void abstainsWhenAllSegmentsBelongToPlaylistHost() {
        DomainReputationClassifier.Input input = new DomainReputationClassifier.Input(
                List.of(), List.of(PLAYLIST_HOST), List.of(), "");

        assertNull(DomainReputationClassifier.classify(evidenceWith(PLAYLIST_HOST), input));
        // 子域名同样视为本站
        assertNull(DomainReputationClassifier.classify(evidenceWith("cdn." + PLAYLIST_HOST), input));
    }

    @Test
    public void abstainsWhenForeignHostIsInBaseline() {
        // 该站正常使用独立 CDN 域名，已在基线内
        DomainReputationClassifier.Input input = new DomainReputationClassifier.Input(
                List.of(), List.of(PLAYLIST_HOST, "fast-cdn.other.com"), List.of(), "");

        assertNull(DomainReputationClassifier.classify(evidenceWith("fast-cdn.other.com"), input));
    }

    @Test
    public void handlesNullInputAndEmptyInterval() {
        assertNull(DomainReputationClassifier.classify(null, DomainReputationClassifier.Input.empty()));
        assertNull(DomainReputationClassifier.classify(evidenceWith("ad.other.com"), null));
    }
}
