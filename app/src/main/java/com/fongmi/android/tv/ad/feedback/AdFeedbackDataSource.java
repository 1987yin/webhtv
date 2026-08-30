package com.fongmi.android.tv.ad.feedback;

import com.fongmi.android.tv.api.config.HlsRuleConfig;
import com.fongmi.android.tv.api.config.ImportedAdRuleCandidateStore;
import com.fongmi.android.tv.api.config.RuleConfig;
import com.fongmi.android.tv.api.config.UserAdRuleStore;
import com.fongmi.android.tv.bean.HlsAdRule;
import com.fongmi.android.tv.bean.ImportedAdRuleCandidate;
import com.fongmi.android.tv.bean.UserAdRule;
import com.fongmi.android.tv.utils.HlsManifestCleaner;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把项目里的静态单例数据读成分类器需要的入参。
 *
 * <p>三个分类器刻意做成纯函数以便单测，因此「读 SharedPreferences」这件事
 * 集中在这里。见设计文档第 5.1 节。
 */
public final class AdFeedbackDataSource {

    /**
     * 与 {@code HlsSegmentClassifier.pathOnlyPattern} 生成的前缀一致。
     * 只有以它开头的切片正则，其匹配范围才与自检的合成 manifest 语义相同。
     */
    static final String ANCHORED_PATH_PREFIX = "^[^?#]*";

    /** 本地 Gson，避免依赖 Application 生命周期，使 compiledOf 可被单测。 */
    private static final Gson GSON = new Gson();

    private AdFeedbackDataSource() {
    }

    /** 现有广告域名黑名单：VOD ads + Live ads + 用户规则 hosts。 */
    public static List<String> blacklistedHosts() {
        try {
            return List.copyOf(RuleConfig.get().getAds());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 待审接口候选里被判为广告域名的条目。 */
    public static List<String> interfaceCandidateHosts() {
        try {
            Set<String> hosts = new LinkedHashSet<>();
            for (ImportedAdRuleCandidate candidate : ImportedAdRuleCandidateStore.pending()) {
                if (candidate == null) continue;
                hosts.addAll(candidate.getHosts());
            }
            return List.copyOf(hosts);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 第一条待审候选的来源接口名，用于证据展示。 */
    public static String interfaceSourceName() {
        try {
            for (ImportedAdRuleCandidate candidate : ImportedAdRuleCandidateStore.pending()) {
                if (candidate == null) continue;
                String name = candidate.getSourceConfigName();
                if (!name.isEmpty()) return name;
            }
        } catch (RuntimeException ignored) {
            // 读取失败不影响归因
        }
        return "";
    }

    /**
     * 用户规则中的正片保护正则。这些规则可能误保护了广告切片，
     * 是 {@link ExistingRuleClassifier} 的诊断输入之一。
     */
    public static List<String> protectingExcludes() {
        try {
            List<String> excludes = new ArrayList<>();
            for (UserAdRule rule : UserAdRuleStore.load()) {
                if (rule == null || !rule.isEnabled()) continue;
                excludes.addAll(rule.getExclude());
            }
            return List.copyOf(excludes);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * 已知 HLS 规则状态。{@code HlsAdRule} 未暴露 hostSuffixes 的 getter，
     * 因此从 {@link HlsRuleConfig.Entry#detail()} 的 JSON 里取。
     */
    public static List<ExistingRuleClassifier.RuleState> hlsRuleStates() {
        try {
            List<ExistingRuleClassifier.RuleState> states = new ArrayList<>();
            for (HlsRuleConfig.Entry entry : HlsRuleConfig.getEntries()) {
                if (entry == null) continue;
                states.add(new ExistingRuleClassifier.RuleState(
                        entry.key(), entry.id(), entry.name(),
                        entry.enabled(), entry.valid(),
                        hostSuffixesOf(entry.detail()),
                        compiledOf(entry.detail())));
            }
            return List.copyOf(states);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * 从规则详情 JSON 还原已编译的规则，供 {@code RuleSelfCheck} 实跑验证。
     *
     * <p>{@code HlsRuleConfig.Entry} 只暴露 detail JSON，不给编译产物；这里重新
     * 反序列化并 compile。失败返回 null，调用方会因此不建议启用该规则。
     *
     * <p>用本地 Gson 而非 {@code App.gson()}：后者在纯 JVM 单测里
     * {@code App.get()} 为 null 会抛 NPE，被 catch 吞掉后静默让整个通道弃权，
     * 这条路径因此既没测也测不了。
     *
     * <p>带未锚定 {@code segmentUrlRegex} 的规则一律拒绝，见
     * {@link #hasUnanchoredSegmentRegex}。
     */
    static HlsManifestCleaner.Rule compiledOf(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) return null;
        if (hasUnanchoredSegmentRegex(detailJson)) return null;
        try {
            HlsAdRule rule = GSON.fromJson(detailJson, HlsAdRule.class);
            return rule == null ? null : rule.compile();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 规则的切片正则是否未锚定到 path。
     *
     * <p>{@code RuleSelfCheck} 的合成 manifest 用 {@code SegmentFact.path()} 拼 URL，
     * 而那是 {@code URI.getPath()} 的产物 —— 按去敏要求 query 与 fragment 都被丢弃。
     * 于是合成 URL 永不带 {@code ?}/{@code #}，而真实 {@code matchesPattern} 匹配的是
     * 含 query 与 fragment 的完整 URL。
     *
     * <p>本项目自己生成的正则用 {@code ^[^?#]*} 前缀对齐了这个差异（见
     * {@code HlsSegmentClassifier.pathOnlyPattern}），但接口下发或内置的第三方规则
     * 不会有这个锚定。实测一条 {@code segmentUrlRegex=["/ads/"]} 的既有规则在
     * 「正片 URL 带 ?ref=/ads/」时自检放行、真实运行多删正片且 {@code fallback=false}
     * —— 错误不被回退兜住，用户直接看到跳帧。
     *
     * <p>因此凡是带 {@code segmentUrlRegex} 且未显式排除 query/fragment 的规则，
     * 都不能用自检结论为它背书。
     */
    static boolean hasUnanchoredSegmentRegex(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) return false;
        try {
            JsonElement parsed = JsonParser.parseString(detailJson);
            if (!parsed.isJsonObject()) return false;
            JsonElement regex = parsed.getAsJsonObject().get("segmentUrlRegex");
            if (regex == null || !regex.isJsonArray()) return false;
            for (JsonElement element : regex.getAsJsonArray()) {
                if (element == null || element.isJsonNull()) continue;
                String value = element.getAsString();
                if (value == null || value.isBlank()) continue;
                if (!value.startsWith(ANCHORED_PATH_PREFIX)) return true;
            }
            return false;
        } catch (RuntimeException e) {
            // 解析不出来时保守判为未锚定
            return true;
        }
    }

    /** 从规则详情 JSON 里解析 hostSuffixes。解析失败返回空列表，不抛异常。 */
    static List<String> hostSuffixesOf(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) return List.of();
        try {
            JsonElement parsed = JsonParser.parseString(detailJson);
            if (!parsed.isJsonObject()) return List.of();
            JsonObject object = parsed.getAsJsonObject();
            JsonElement hosts = object.get("hostSuffixes");
            if (hosts == null || !hosts.isJsonArray()) return List.of();
            JsonArray array = hosts.getAsJsonArray();
            List<String> result = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                if (element == null || element.isJsonNull()) continue;
                String value = element.getAsString();
                if (value != null && !value.isBlank()) result.add(value.trim());
            }
            return List.copyOf(result);
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
