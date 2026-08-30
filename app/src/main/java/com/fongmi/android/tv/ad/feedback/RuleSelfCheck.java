package com.fongmi.android.tv.ad.feedback;

import com.fongmi.android.tv.bean.HlsAdRule;
import com.fongmi.android.tv.utils.HlsManifestCleaner;

import java.util.List;
import java.util.Locale;

/**
 * 规则自检：把候选载荷交给**真实**的 {@link HlsManifestCleaner} 跑一遍合成 manifest，
 * 只有「区间内全删、区间外一片不删、且未触发回退」才放行。
 *
 * <p>这是核心不变量的唯一守门人。前五轮评审反复出现同一类缺陷 —— 每轮为某一类条件
 * 补上对照校验，下一轮就暴露另一类没校验的条件（同域 hosts、缺 crossDomain 门槛、
 * 路径正则跨 query、采样截断、duration+crossDomain 组合、共用 CDN 靠路径区分）。
 * 根因是那些修法都在验证「条件」，而不变量说的是「规则的实际效果」。
 *
 * <p>用执行引擎自身做判据，好处是对未来新增的任何条件类型自动生效，并且顺带覆盖了
 * cleaner 的三道回退闸门（全删、删除比例 &gt; 35%、删除时长 &gt; 90s）—— 那些闸门即使
 * 条件完全有区分度也会让规则整体失效。
 */
final class RuleSelfCheck {

    /** 合成 manifest 的基准地址，只需 host 与 playlist 一致即可。 */
    private static final String SYNTHETIC_BASE_SCHEME = "https://";

    private RuleSelfCheck() {
    }

    /**
     * @return 载荷是否安全可落地
     */
    static boolean isSafe(AdIntervalEvidence evidence, RulePayload payload) {
        if (payload.isEmpty()) return false;
        if (evidence.playlistHost().isEmpty()) return false;
        if (evidence.inside().isEmpty() || evidence.outside().isEmpty()) return false;

        HlsManifestCleaner.Rule rule = compile(payload);
        if (rule == null) return false;

        String base = SYNTHETIC_BASE_SCHEME + evidence.playlistHost() + "/index.m3u8";
        String manifest = synthesize(evidence);
        HlsManifestCleaner.Result result;
        try {
            result = HlsManifestCleaner.clean(base, manifest, List.of(rule));
        } catch (RuntimeException e) {
            return false;
        }
        // 回退意味着这条规则在真实播放里同样会被拒（全删 / 比例超限 / 时长超限）
        if (result == null || result.fallback() || !result.changed()) return false;
        // 必须恰好删掉区间内的每一片，且一片区间外的都不碰
        return result.removedSegments() == evidence.inside().size()
                && !removedAnyOutside(result.manifest(), evidence);
    }

    /** 区间外任何一片仍必须留在净化后的 manifest 里。 */
    private static boolean removedAnyOutside(String cleaned, AdIntervalEvidence evidence) {
        if (cleaned == null) return true;
        for (SegmentFact fact : evidence.outside()) {
            if (!cleaned.contains(uriOf(fact, evidence.playlistHost()))) return true;
        }
        return false;
    }

    /**
     * 用证据合成一份与真实 playlist 等价的 media playlist。
     *
     * <p>切片按原下标排序，保留 host、path、时长与断点标记 —— 这些正是规则可能
     * 用到的全部条件维度。
     */
    private static String synthesize(AdIntervalEvidence evidence) {
        List<SegmentFact> all = new java.util.ArrayList<>(evidence.inside());
        all.addAll(evidence.outside());
        all.sort(java.util.Comparator.comparingInt(SegmentFact::index));

        StringBuilder text = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:10\n");
        for (SegmentFact fact : all) {
            if (fact.discontinuityBefore()) text.append("#EXT-X-DISCONTINUITY\n");
            text.append(String.format(Locale.US, "#EXTINF:%.3f,%n", fact.durationSec()));
            text.append(uriOf(fact, evidence.playlistHost())).append('\n');
        }
        return text.append("#EXT-X-ENDLIST\n").toString();
    }

    /** 还原切片的绝对地址。host 为空时按同域相对路径处理。 */
    private static String uriOf(SegmentFact fact, String playlistHost) {
        String host = fact.host().isEmpty() ? playlistHost : fact.host();
        String path = fact.path().startsWith("/") ? fact.path() : "/" + fact.path();
        return SYNTHETIC_BASE_SCHEME + host + path;
    }

    /** 载荷编译失败说明条件本身非法，直接判为不安全。 */
    private static HlsManifestCleaner.Rule compile(RulePayload payload) {
        try {
            return HlsAdRule.createUserRule(
                    "self-check", "self-check",
                    payload.playlistHostSuffixes(), payload.hosts(), payload.regex(),
                    payload.hasDurationRange() ? payload.durationMin() : null,
                    payload.hasDurationRange() ? payload.durationMax() : null,
                    payload.requireDiscontinuity(), payload.requireCrossDomain(),
                    payload.minimumSignals()).compile();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
