package com.fongmi.android.tv.ad.feedback;

import com.fongmi.android.tv.api.config.HlsRuleConfig;
import com.fongmi.android.tv.api.config.ImportedAdRuleCandidateStore;
import com.fongmi.android.tv.api.config.RuleConfig;
import com.fongmi.android.tv.api.config.UserAdRuleStore;
import com.fongmi.android.tv.bean.ImportedAdRuleCandidate;
import com.fongmi.android.tv.bean.UserAdRule;
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
                        hostSuffixesOf(entry.detail())));
            }
            return List.copyOf(states);
        } catch (RuntimeException e) {
            return List.of();
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
