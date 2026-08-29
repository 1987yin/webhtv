package com.fongmi.android.tv.ad.feedback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 基于 HLS 切片结构的归因通道。零新增运行时成本：全部输入来自
 * {@code M3u8Parser.parse()} 已产出的证据，不发起新的网络请求。
 *
 * <p>五种信号的权重见设计文档第 7.1 节。生成的条件必须落在
 * {@code HlsManifestCleaner.Rule} 已支持的范围内，否则规则无法被现有引擎执行。
 */
public final class HlsSegmentClassifier {

    public static final String CHANNEL_ID = "hls";

    /** 置信度低于此值时弃权。 */
    static final float MIN_CONFIDENCE = 0.30f;

    static final float WEIGHT_CROSS_DOMAIN = 0.35f;
    static final float WEIGHT_DISCONTINUITY = 0.25f;
    static final float WEIGHT_DURATION_OUTLIER = 0.20f;
    static final float WEIGHT_PATH_HINT = 0.15f;
    static final float WEIGHT_HEAD_POSITION = 0.05f;

    /** 区间内切片时长视为「整齐」的标准差上限，秒。 */
    private static final double UNIFORM_STDDEV_MAX = 0.05d;
    /** 与区间外众数时长的差异下限，秒。 */
    private static final double DURATION_GAP_MIN = 0.5d;
    /** 落在 playlist 头部多少比例内算「片头」。 */
    private static final double HEAD_RATIO = 0.15d;

    /** 保守的广告路径特征，只匹配明确的目录段。 */
    private static final List<String> PATH_HINTS =
            List.of("/ad/", "/ads/", "/adv/", "/preroll/", "/midroll/", "/creative/");

    private HlsSegmentClassifier() {
    }

    /** 命中的信号，用于生成规则时决定 minimumSignals。 */
    public record Signals(boolean crossDomain, boolean discontinuity, boolean durationOutlier,
                          boolean pathHint, boolean headPosition) {

        /** 可编码进 {@code HlsAdRule} 的信号数。位置信号不被 HlsAdRule 支持，不计入。 */
        public int encodableCount() {
            int count = 0;
            if (crossDomain) count++;
            if (discontinuity) count++;
            if (durationOutlier) count++;
            if (pathHint) count++;
            return count;
        }

        public float weightSum() {
            float sum = 0f;
            if (crossDomain) sum += WEIGHT_CROSS_DOMAIN;
            if (discontinuity) sum += WEIGHT_DISCONTINUITY;
            if (durationOutlier) sum += WEIGHT_DURATION_OUTLIER;
            if (pathHint) sum += WEIGHT_PATH_HINT;
            if (headPosition) sum += WEIGHT_HEAD_POSITION;
            return sum;
        }
    }

    /**
     * 对区间做结构归因。无法判断时返回 null 表示弃权。
     */
    public static AdAttribution classify(AdIntervalEvidence evidence) {
        if (evidence == null || !evidence.hasSegmentEvidence()) return null;
        // 区间已被结构化规则整体处理，用户看到的广告来自别处，不应再加 HLS 规则
        if (evidence.handledByStructuredRule()) return null;

        Signals signals = detect(evidence);
        float confidence = signals.weightSum();
        if (confidence < MIN_CONFIDENCE) return null;
        // 无法编码进规则的结论没有落地价值
        if (signals.encodableCount() == 0) return null;

        return new AdAttribution(CHANNEL_ID, categoryOf(signals), confidence,
                RiskLevel.MEDIUM, describe(evidence, signals), RemediationKind.HLS_STRUCTURED_RULE,
                payloadOf(evidence, signals));
    }

    /**
     * 生成可被 {@code HlsManifestCleaner.Rule} 执行的条件。
     *
     * <p>关键约束：{@code HlsManifestCleaner} 对每个切片独立累加信号，
     * 达到 minimumSignals 即删除。因此只能编码**有区分度**的条件 ——
     * 与 playlist 同域的 hostSuffixes 对每个切片都成立，requireDiscontinuity
     * 对每个断点后的切片都成立，两者凑够 2 个信号会连正片一起删。
     *
     * <p>有区分度的条件只有两类：非本站域名、广告路径特征。至少要有一类，
     * 否则返回空载荷让通道弃权。
     */
    private static RulePayload payloadOf(AdIntervalEvidence evidence, Signals signals) {
        String playlistHost = evidence.playlistHost();
        // 只保留非本站域名 —— 同域 host 不是区分条件
        List<String> hosts = evidence.inside().stream()
                .map(SegmentFact::host)
                .filter(host -> !host.isEmpty())
                .filter(host -> playlistHost.isEmpty() || !hostMatches(host, playlistHost))
                .distinct()
                .toList();
        List<String> pathPatterns = signals.pathHint()
                ? pathPatternsOf(evidence.inside()) : List.of();
        // 没有任何区分条件时不得生成规则
        if (hosts.isEmpty() && pathPatterns.isEmpty()) return RulePayload.empty();

        double min = Double.NaN;
        double max = Double.NaN;
        if (signals.durationOutlier()) {
            double mean = mean(evidence.inside());
            min = Math.max(0d, mean - 0.1d);
            max = mean + 0.1d;
        }
        // 作用域收窄到当前站点的 playlist 域名，否则规则会污染其他站点
        List<String> scope = playlistHost.isEmpty() ? List.of() : List.of(playlistHost);

        // requireDiscontinuity 只对广告块首片成立，块内后续切片拿不到这个信号。
        // 把它计入门限会导致只删掉首片、留下其余广告，因此不编码进规则 ——
        // 断点已经在归因阶段用于定位区间，规则层不再需要它。
        // requireCrossDomain 对块内每一片都成立，可以安全编码。
        boolean requireCrossDomain = signals.crossDomain();

        // 逐项统计真正对块内每一片都成立的信号，minimumSignals 不能超过它，
        // 否则 HlsAdRule.compile() 会拒绝整条规则。
        int encoded = 0;
        if (!hosts.isEmpty()) encoded++;
        if (!pathPatterns.isEmpty()) encoded++;
        if (!Double.isNaN(min)) encoded++;
        if (requireCrossDomain) encoded++;

        // 门限优先取 2 以避免宽泛删片，但不得超过实际可编码的信号数
        // （超过会让 compile() 拒绝整条规则）。也不取满全部信号 —— 要求
        // 三个条件同时成立时，广告块里稍有出入的一片就会漏删。
        int minimumSignals = Math.min(2, encoded);

        return RulePayload.ofHlsRule(scope, hosts, pathPatterns, min, max,
                false, requireCrossDomain, minimumSignals);
    }

    /** 命中的广告路径目录段转成正则，用 quote 避免元字符注入。 */
    private static List<String> pathPatternsOf(List<SegmentFact> inside) {
        List<String> patterns = new ArrayList<>();
        for (String hint : PATH_HINTS) {
            boolean allMatch = inside.stream()
                    .allMatch(fact -> fact.path().toLowerCase(Locale.US).contains(hint));
            if (allMatch) patterns.add(Pattern.quote(hint));
        }
        return patterns;
    }

    /** host 是否等于给定域名或为其子域。 */
    private static boolean hostMatches(String host, String domain) {
        String lower = host.toLowerCase(Locale.US);
        String target = domain.toLowerCase(Locale.US);
        return lower.equals(target) || lower.endsWith("." + target);
    }

    /** 逐项检测五种信号。 */
    public static Signals detect(AdIntervalEvidence evidence) {
        List<SegmentFact> inside = evidence.inside();
        List<SegmentFact> outside = evidence.outside();
        return new Signals(
                detectCrossDomain(inside, outside, evidence.playlistHost()),
                evidence.boundedByDiscontinuity() || inside.get(0).discontinuityBefore(),
                detectDurationOutlier(inside, outside),
                detectPathHint(inside),
                detectHeadPosition(inside, outside));
    }

    /**
     * 跨域：区间内切片整体不属于 playlist 域名，且区间外切片属于。
     * 只有形成对照才算证据 —— 整条 playlist 都跨域说明这就是该站的正常结构。
     */
    private static boolean detectCrossDomain(List<SegmentFact> inside, List<SegmentFact> outside,
                                            String playlistHost) {
        if (playlistHost == null || playlistHost.isEmpty()) return false;
        boolean insideForeign = inside.stream()
                .allMatch(fact -> !fact.host().isEmpty() && !fact.hostEndsWith(playlistHost));
        if (!insideForeign) return false;
        if (outside.isEmpty()) return false;
        return outside.stream()
                .anyMatch(fact -> fact.hostEndsWith(playlistHost));
    }

    /**
     * 时长离群：区间内时长高度一致，且与区间外众数明显不同。
     * 单独出现时权重不足以触发规则，必须与其他信号叠加。
     */
    private static boolean detectDurationOutlier(List<SegmentFact> inside, List<SegmentFact> outside) {
        if (inside.isEmpty() || outside.isEmpty()) return false;
        if (stdDev(inside) > UNIFORM_STDDEV_MAX) return false;
        double insideMean = mean(inside);
        Double outsideMode = mode(outside);
        if (outsideMode == null) return false;
        return Math.abs(insideMean - outsideMode) > DURATION_GAP_MIN;
    }

    private static boolean detectPathHint(List<SegmentFact> inside) {
        return inside.stream().anyMatch(fact -> {
            String path = fact.path().toLowerCase(Locale.US);
            return PATH_HINTS.stream().anyMatch(path::contains);
        });
    }

    /** 位置：区间落在 playlist 头部 15% 以内。 */
    private static boolean detectHeadPosition(List<SegmentFact> inside, List<SegmentFact> outside) {
        int total = inside.size() + outside.size();
        if (total == 0) return false;
        int firstIndex = inside.stream().mapToInt(SegmentFact::index).min().orElse(Integer.MAX_VALUE);
        return firstIndex <= Math.max(0, (int) Math.floor(total * HEAD_RATIO));
    }

    private static AdCategory categoryOf(Signals signals) {
        if (signals.crossDomain()) return AdCategory.THIRD_PARTY_CDN_SEGMENT;
        if (signals.discontinuity()) return AdCategory.DISCONTINUITY_BLOCK;
        if (signals.durationOutlier()) return AdCategory.FIXED_DURATION_BLOCK;
        return AdCategory.UNKNOWN;
    }

    private static List<String> describe(AdIntervalEvidence evidence, Signals signals) {
        List<SegmentFact> inside = evidence.inside();
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.US, "区间内 %d 个切片，时长合计 %.1fs",
                inside.size(), inside.stream().mapToDouble(SegmentFact::durationSec).sum()));
        if (signals.crossDomain()) {
            lines.add(String.format(Locale.US, "切片域名 %s 与 playlist 域名 %s 不一致",
                    inside.get(0).host(), evidence.playlistHost()));
        }
        if (signals.discontinuity()) lines.add("区间边界存在 #EXT-X-DISCONTINUITY");
        if (signals.durationOutlier()) {
            lines.add(String.format(Locale.US, "区间内切片时长一致（%.2fs），与正片切片时长差异明显",
                    mean(inside)));
        }
        if (signals.pathHint()) lines.add("切片路径包含广告目录特征");
        if (signals.headPosition()) lines.add("区间位于播放列表头部");
        lines.add("起点来源：" + evidence.startOrigin());
        return lines;
    }

    private static double mean(List<SegmentFact> facts) {
        return facts.stream().mapToDouble(SegmentFact::durationSec).average().orElse(0d);
    }

    private static double stdDev(List<SegmentFact> facts) {
        if (facts.size() < 2) return 0d;
        double mean = mean(facts);
        double variance = facts.stream()
                .mapToDouble(fact -> Math.pow(fact.durationSec() - mean, 2))
                .sum() / facts.size();
        return Math.sqrt(variance);
    }

    /** 区间外切片的众数时长，按 0.1s 粒度归桶。 */
    private static Double mode(List<SegmentFact> facts) {
        Map<Long, Integer> buckets = new HashMap<>();
        for (SegmentFact fact : facts) {
            long bucket = Math.round(fact.durationSec() * 10);
            buckets.merge(bucket, 1, Integer::sum);
        }
        return buckets.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> entry.getKey() / 10d)
                .orElse(null);
    }
}
