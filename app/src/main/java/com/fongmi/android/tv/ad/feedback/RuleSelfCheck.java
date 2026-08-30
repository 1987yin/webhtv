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

    /** 合成 manifest 每片额外占用的行数（EXTINF + URI），用于预判真实规模。 */
    private static final int LINES_PER_SEGMENT_SYNTHETIC = 2;
    /**
     * 真实 manifest 每片的行数上界。除 EXTINF + URI 外，常见还带
     * {@code #EXT-X-KEY} 或 {@code #EXT-X-PROGRAM-DATE-TIME}。
     * 合成时按这个密度预留余量，否则真实 manifest 会因超过
     * {@code MAX_MANIFEST_LINES} 而回退，而自检看不到。
     */
    private static final int LINES_PER_SEGMENT_REAL = 3;
    /** 与 {@code HlsManifestCleaner.MAX_MANIFEST_LINES} 一致。 */
    private static final int MAX_MANIFEST_LINES = 20_000;

    /**
     * @return 载荷是否安全可落地
     */
    static boolean isSafe(AdIntervalEvidence evidence, RulePayload payload) {
        if (payload.isEmpty()) return false;
        if (evidence.playlistHost().isEmpty()) return false;
        if (evidence.inside().isEmpty() || evidence.outside().isEmpty()) return false;

        // 真实 manifest 的行密度高于合成，按上界预判：若真实会因规模回退，
        // 这条规则在播放时不会生效，不该保存。
        int total = evidence.inside().size() + evidence.outside().size();
        if ((long) total * LINES_PER_SEGMENT_REAL > MAX_MANIFEST_LINES) return false;

        HlsManifestCleaner.Rule rule = compile(payload);
        if (rule == null) return false;

        List<SegmentFact> ordered = ordered(evidence);
        String base = SYNTHETIC_BASE_SCHEME + evidence.playlistHost() + "/index.m3u8";
        HlsManifestCleaner.Result result;
        try {
            result = HlsManifestCleaner.clean(base, synthesize(evidence, ordered), List.of(rule));
        } catch (RuntimeException e) {
            return false;
        }
        // 回退意味着这条规则在真实播放里同样会被拒（全删 / 比例超限 / 时长超限）
        if (result == null || result.fallback() || !result.changed()) return false;

        // 按 URI 出现次数比对，而不是 contains 子串查找 —— 后者会被别名遮蔽：
        // 删掉 /s/1 后保留的 /s/12 让判断恒为真。代理式内嵌 URL 的 path、
        // 无扩展名序号切片、同一 URI 在 playlist 中复用都会触发这种遮蔽。
        java.util.Map<String, Integer> keptCounts = segmentUriCounts(result.manifest());
        java.util.Map<String, Integer> expected = new java.util.HashMap<>();
        for (SegmentFact fact : evidence.outside()) {
            expected.merge(uriOf(fact, evidence.playlistHost()), 1, Integer::sum);
        }
        // 区间外每片都必须保留，且次数完全一致：少一次就是误删。
        if (!expected.equals(keptCounts)) return false;
        // 区间内每片都必须被删：与区间外同 URI 的切片会被一并删除，
        // 此时上面的次数比对已经失败，这里再兜一次总数。
        return result.removedSegments() == evidence.inside().size();
    }

    /** 统计净化后 manifest 里每个切片 URI 的出现次数。 */
    private static java.util.Map<String, Integer> segmentUriCounts(String cleaned) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        if (cleaned == null) return counts;
        for (String line : cleaned.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            counts.merge(trimmed, 1, Integer::sum);
        }
        return counts;
    }

    /** 切片按原下标排序，作为合成与回读的共同顺序基准。 */
    private static List<SegmentFact> ordered(AdIntervalEvidence evidence) {
        List<SegmentFact> all = new java.util.ArrayList<>(evidence.inside());
        all.addAll(evidence.outside());
        all.sort(java.util.Comparator.comparingInt(SegmentFact::index));
        return all;
    }

    /**
     * 用证据合成一份与真实 playlist 等价的 media playlist。
     *
     * <p>切片按原下标排序，保留 host、path、时长与断点标记 —— 这些正是规则可能
     * 用到的全部条件维度。
     *
     * <p>刻意不注入自定义标签做标记：{@code HlsManifestCleaner} 对未知 tag
     * 会整份回退（实测 {@code fallback=true}），注入哨兵反而让自检永远拒绝。
     */
    private static String synthesize(AdIntervalEvidence evidence, List<SegmentFact> ordered) {
        StringBuilder text = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:10\n");
        for (SegmentFact fact : ordered) {
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
