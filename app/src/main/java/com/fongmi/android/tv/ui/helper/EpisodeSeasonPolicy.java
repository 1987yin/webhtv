package com.fongmi.android.tv.ui.helper;

import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.title.MediaTitleParser;
import com.fongmi.android.tv.title.MediaTitleRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class EpisodeSeasonPolicy {

    private static final Pattern SPECIAL_SEASON = Pattern.compile("(?i)(?:第\\s*0\\s*[季部]|season\\s*0\\b|s0{1,2}(?:[-._\\s]*e[0-9]{1,3})?\\b|特别篇|特別篇|\\bspecials\\b)");

    private EpisodeSeasonPolicy() {
    }

    public static boolean canSliceBySeasonCounts(int episodeCount, List<Integer> seasons, Map<Integer, Integer> seasonCounts) {
        if (episodeCount <= 0 || seasons == null || seasons.size() <= 1 || seasonCounts == null || seasonCounts.isEmpty()) return false;
        int total = 0;
        for (Integer season : seasons) {
            int count = Math.max(0, seasonCounts.getOrDefault(season, 0));
            if (count <= 0) return false;
            total += count;
            if (total > episodeCount) return false;
        }
        return total == episodeCount;
    }

    public static <T> List<T> sliceBySeasonCounts(List<T> episodes, List<Integer> seasons, Map<Integer, Integer> seasonCounts, int selectedSeason) {
        if (episodes == null || episodes.isEmpty()) return List.of();
        if (!canSliceBySeasonCounts(episodes.size(), seasons, seasonCounts)) return episodes;
        int start = 0;
        for (Integer season : seasons) {
            int count = Math.max(0, seasonCounts.getOrDefault(season, 0));
            int end = Math.min(episodes.size(), start + count);
            if (season == selectedSeason) return start < end ? episodes.subList(start, end) : List.of();
            start = end;
        }
        return episodes;
    }

    public static boolean shouldUseSingleSeasonEpisodeData(int sourceEpisodeCount, int firstSeason, List<Integer> seasons, Map<Integer, Integer> seasonCounts) {
        if (sourceEpisodeCount <= 0 || firstSeason < 0 || seasons == null || seasons.size() <= 1 || seasonCounts == null) return false;
        int firstSeasonCount = Math.max(0, seasonCounts.getOrDefault(firstSeason, 0));
        return firstSeasonCount >= sourceEpisodeCount && !canSliceBySeasonCounts(sourceEpisodeCount, seasons, seasonCounts);
    }

    /**
     * Resolves only seasons that can be safely mapped to the current source line.
     * An empty result means the UI must keep the source episodes as one flat list.
     */
    public static List<Integer> resolveAvailableSeasons(
            List<Integer> sourceSeasonNumbers,
            int titleSeason,
            int firstSeason,
            List<Integer> tmdbSeasons,
            Map<Integer, Integer> seasonCounts) {
        if (sourceSeasonNumbers == null || sourceSeasonNumbers.isEmpty() || tmdbSeasons == null || tmdbSeasons.isEmpty()) return List.of();
        boolean hasAnyExplicitSeason = hasAnyExplicitSeason(sourceSeasonNumbers);
        if (hasAnyExplicitSeason) {
            if (!hasCompleteExplicitSeasonMapping(sourceSeasonNumbers, tmdbSeasons)) {
                // Unclassified extras are safe only when every known episode agrees on one TMDB season.
                int onlyMappedSeason = -1;
                for (Integer season : sourceSeasonNumbers) {
                    if (season == null || season < 0) continue;
                    if (!tmdbSeasons.contains(season)) return List.of();
                    if (onlyMappedSeason >= 0 && onlyMappedSeason != season) return List.of();
                    onlyMappedSeason = season;
                }
                return onlyMappedSeason >= 0 ? List.of(onlyMappedSeason) : List.of();
            }
            List<Integer> available = new ArrayList<>();
            for (Integer season : tmdbSeasons) {
                if (sourceSeasonNumbers.contains(season)) available.add(season);
            }
            return List.copyOf(available);
        }
        if (titleSeason >= 0) return tmdbSeasons.contains(titleSeason) ? List.of(titleSeason) : List.of();
        if (tmdbSeasons.size() == 1) return List.of(tmdbSeasons.get(0));
        int sourceEpisodeCount = sourceSeasonNumbers.size();
        if (canSliceBySeasonCounts(sourceEpisodeCount, tmdbSeasons, seasonCounts)) return List.copyOf(tmdbSeasons);
        if (tmdbSeasons.contains(firstSeason) && shouldUseSingleSeasonEpisodeData(sourceEpisodeCount, firstSeason, tmdbSeasons, seasonCounts)) return List.of(firstSeason);
        return List.of();
    }

    public static int resolveSourceSeason(String... candidates) {
        return resolveSourceSeason(true, candidates);
    }

    /** Use for source-line labels and episode names, where trailing digits are ordinals rather than seasons. */
    public static int resolveExplicitSourceSeason(String... candidates) {
        return resolveSourceSeason(false, candidates);
    }

    private static int resolveSourceSeason(boolean allowTrailingSeason, String... candidates) {
        if (candidates == null || candidates.length == 0) return -1;
        MediaTitleParser parser = new MediaTitleParser();
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) continue;
            if (SPECIAL_SEASON.matcher(candidate).find()) return 0;
            int season = allowTrailingSeason
                    ? parser.parse(MediaTitleRequest.builder().rawTitle(candidate).build()).getSeasonNumber()
                    : parser.seasonNumber(candidate);
            if (season > 0) return season;
        }
        return -1;
    }

    /**
     * 部分剧集因 TMDB 内部更名导致"源站季编号"与 TMDB 的 season_number 存在固定偏移。
     * 例如《奔跑吧》前 4 季在 TMDB 中名为"奔跑吧兄弟"，更名后季号连续累计，
     * 源站"奔跑吧 第10季"实际对应 TMDB 第 14 季（+4 偏移）。
     * 内置偏移规则按 TMDB 剧名（含别名）匹配；无匹配时返回 0，表示无需校正。
     */
    private static final Map<String, Integer> TMDB_TITLE_SEASON_OFFSET = Map.of(
            "奔跑吧", 4,
            "奔跑吧兄弟", 4
    );

    /** 返回源季 -> TMDB 季的固定偏移量（0 表示无需校正）。优先用户自定义，再回退内置规则。 */
    public static int tmdbSeasonOffset(String tmdbTitle) {
        if (tmdbTitle == null || tmdbTitle.trim().isEmpty()) return 0;
        String title = tmdbTitle.trim();
        // 1) 用户自定义优先
        int userOffset = Setting.getTmdbSeasonOffset(title);
        if (userOffset != 0) return userOffset;
        // 2) 内置规则兜底
        Integer offset = TMDB_TITLE_SEASON_OFFSET.get(title);
        if (offset != null) return offset;
        // 别名匹配：去掉常见前缀/后缀后比较
        for (String key : TMDB_TITLE_SEASON_OFFSET.keySet()) {
            if (title.contains(key) || key.contains(title)) return TMDB_TITLE_SEASON_OFFSET.get(key);
        }
        return 0;
    }

    /** 将源站季编号校正为 TMDB season_number（无偏移时原样返回）。 */
    public static int correctTmdbSeason(int sourceSeason, String tmdbTitle) {
        if (sourceSeason < 0) return sourceSeason;
        return sourceSeason + tmdbSeasonOffset(tmdbTitle);
    }

    public static List<Integer> episodeMetadataSeasonCandidates(int sourceSeason) {
        return sourceSeason >= 0 ? List.of(sourceSeason) : List.of(1, 0);
    }

    /**
     * 生成源站季编号对应的 TMDB 季候选（已按偏移校正）。
     * 优先返回校正后的季号，失败后回退原始季号，保证无偏移时行为不变。
     */
    public static List<Integer> episodeMetadataSeasonCandidates(int sourceSeason, String tmdbTitle) {
        if (sourceSeason < 0) return List.of(1, 0);
        int corrected = correctTmdbSeason(sourceSeason, tmdbTitle);
        if (corrected == sourceSeason) return List.of(sourceSeason);
        List<Integer> candidates = new ArrayList<>();
        candidates.add(corrected);
        if (sourceSeason > 0) candidates.add(sourceSeason); // 回退原始季号
        if (!candidates.contains(0)) candidates.add(0);
        return candidates;
    }

    public static String episodePositionCacheKey(int season, String episodeName) {
        String name = episodeName == null ? "" : episodeName;
        if (season < 0 || name.isEmpty()) return name;
        String prefix = "S" + season + "|";
        return name.startsWith(prefix) ? name : prefix + name;
    }

    private static boolean hasAnyExplicitSeason(List<Integer> sourceSeasonNumbers) {
        for (Integer season : sourceSeasonNumbers) {
            if (season != null && season >= 0) return true;
        }
        return false;
    }

    /** Returns true only when every source episode declares a season present in TMDB metadata. */
    public static boolean hasCompleteExplicitSeasonMapping(List<Integer> sourceSeasonNumbers, List<Integer> tmdbSeasons) {
        if (sourceSeasonNumbers == null || sourceSeasonNumbers.isEmpty() || tmdbSeasons == null || tmdbSeasons.isEmpty()) return false;
        for (Integer season : sourceSeasonNumbers) {
            if (season == null || season < 0 || !tmdbSeasons.contains(season)) return false;
        }
        return true;
    }

    public static int linearEpisodeNumber(int sourceEpisodeNumber, int zeroBasedIndex) {
        // 文件名有明确集号时，直接使用它，不要被列表位置覆盖
        if (sourceEpisodeNumber > 0) return sourceEpisodeNumber;
        // 文件名无集号时，用列表位置推断（index + 1）
        return zeroBasedIndex >= 0 ? zeroBasedIndex + 1 : -1;
    }
}
