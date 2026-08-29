package com.fongmi.android.tv.ad.feedback;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 基于域名信誉的归因通道，实现「非当前适配的主流域名」这条判据。
 *
 * <p>三层比对见设计文档第 7.2 节。所有外部数据由调用方以 {@link Input} 传入，
 * 类本身不触碰 SharedPreferences，因此可在纯 JVM 下单测。
 */
public final class DomainReputationClassifier {

    public static final String CHANNEL_ID = "domain";

    /** 命中已知黑名单时的置信度。 */
    static final float CONFIDENCE_KNOWN_HOST = 0.95f;
    /** 仅「不在本站基线内」时的置信度。 */
    static final float CONFIDENCE_UNKNOWN_HOST = 0.55f;
    /** 接口候选也认为是广告域名时的加成。 */
    static final float BONUS_INTERFACE_CANDIDATE = 0.15f;

    private DomainReputationClassifier() {
    }

    /**
     * 分类输入。
     *
     * @param blacklistedHosts    现有黑名单，来自 {@code RuleConfig.get().getAds()}
     * @param siteBaselineHosts   本站历史正常域名，来自 SitePlaylistHostBaseline
     * @param interfaceCandidateHosts 接口学习的待审候选域名
     * @param interfaceSourceName 命中候选时展示的来源接口名
     */
    public record Input(List<String> blacklistedHosts, List<String> siteBaselineHosts,
                        List<String> interfaceCandidateHosts, String interfaceSourceName) {

        public Input {
            blacklistedHosts = blacklistedHosts == null ? List.of() : List.copyOf(blacklistedHosts);
            siteBaselineHosts = siteBaselineHosts == null ? List.of() : List.copyOf(siteBaselineHosts);
            interfaceCandidateHosts = interfaceCandidateHosts == null ? List.of() : List.copyOf(interfaceCandidateHosts);
            interfaceSourceName = interfaceSourceName == null ? "" : interfaceSourceName;
        }

        public static Input empty() {
            return new Input(List.of(), List.of(), List.of(), "");
        }
    }

    /** 无结论时返回 null 表示弃权。 */
    public static AdAttribution classify(AdIntervalEvidence evidence, Input input) {
        if (evidence == null || evidence.inside().isEmpty()) return null;
        Input safe = input == null ? Input.empty() : input;

        Set<String> foreignHosts = foreignHosts(evidence);
        if (foreignHosts.isEmpty()) return null;

        List<String> matchedBlacklist = matching(foreignHosts, safe.blacklistedHosts());
        List<String> matchedCandidates = matching(foreignHosts, safe.interfaceCandidateHosts());

        if (!matchedBlacklist.isEmpty()) {
            // 已在黑名单里却仍被用户看到：拦截路径没覆盖播放器直连的切片请求。
            // 结论是诊断，落地方式应改用 HLS 结构化规则。
            List<String> evidenceLines = new ArrayList<>();
            evidenceLines.add("切片域名已在广告黑名单中：" + String.join("、", matchedBlacklist));
            evidenceLines.add("黑名单主要在 WebView 请求拦截生效，不拦播放器直连的切片请求");
            evidenceLines.add("建议改用 HLS 结构化规则删除这些切片");
            return new AdAttribution(CHANNEL_ID, AdCategory.ALREADY_HANDLED,
                    CONFIDENCE_KNOWN_HOST, RiskLevel.LOW, evidenceLines, RemediationKind.NONE);
        }

        // 无基线数据时不能断言「非本站域名」，避免首次播放即误判
        if (safe.siteBaselineHosts().isEmpty() && matchedCandidates.isEmpty()) return null;

        boolean outsideBaseline = !safe.siteBaselineHosts().isEmpty()
                && foreignHosts.stream().noneMatch(host -> endsWithAny(host, safe.siteBaselineHosts()));
        if (!outsideBaseline && matchedCandidates.isEmpty()) return null;

        float confidence = outsideBaseline ? CONFIDENCE_UNKNOWN_HOST : 0f;
        List<String> evidenceLines = new ArrayList<>();
        if (outsideBaseline) {
            evidenceLines.add("切片域名 " + String.join("、", foreignHosts) + " 不在本站常用域名中");
        }
        if (!matchedCandidates.isEmpty()) {
            confidence += BONUS_INTERFACE_CANDIDATE;
            String source = safe.interfaceSourceName().isEmpty() ? "接口规则" : safe.interfaceSourceName();
            evidenceLines.add(source + "也将其列为广告域名候选");
        }
        evidenceLines.add("起点来源：" + evidence.startOrigin());

        return new AdAttribution(CHANNEL_ID, AdCategory.THIRD_PARTY_CDN_SEGMENT,
                confidence, RiskLevel.LOW, evidenceLines, RemediationKind.HOST_BLACKLIST,
                RulePayload.ofHosts(List.copyOf(foreignHosts)));
    }

    /** 区间内不属于 playlist 域名的切片 host，去重保序。 */
    private static Set<String> foreignHosts(AdIntervalEvidence evidence) {
        Set<String> hosts = new LinkedHashSet<>();
        String playlistHost = evidence.playlistHost();
        for (SegmentFact fact : evidence.inside()) {
            if (fact.host().isEmpty()) continue;
            if (!playlistHost.isEmpty() && fact.hostEndsWith(playlistHost)) continue;
            hosts.add(fact.host().toLowerCase(Locale.US));
        }
        return hosts;
    }

    private static List<String> matching(Set<String> hosts, List<String> patterns) {
        List<String> matched = new ArrayList<>();
        for (String host : hosts) {
            if (endsWithAny(host, patterns)) matched.add(host);
        }
        return matched;
    }

    /**
     * host 是否命中任一片段。黑名单条目多为域名片段（如 {@code doubleclick.net}），
     * 沿用现有拦截语义：后缀匹配或包含匹配。
     */
    private static boolean endsWithAny(String host, List<String> patterns) {
        String lower = host.toLowerCase(Locale.US);
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) continue;
            String target = pattern.toLowerCase(Locale.US).trim();
            if (lower.equals(target) || lower.endsWith("." + target) || lower.contains(target)) return true;
        }
        return false;
    }
}
