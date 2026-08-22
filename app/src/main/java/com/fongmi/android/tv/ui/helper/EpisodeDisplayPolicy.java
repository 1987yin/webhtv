package com.fongmi.android.tv.ui.helper;

import com.fongmi.android.tv.bean.Episode;

import java.util.List;

public final class EpisodeDisplayPolicy {

    private EpisodeDisplayPolicy() {
    }

    /** 是否存在通过严格集号校验的有效 TMDB 分集，决定要不要用增强卡片。 */
    public static boolean hasTmdbEpisodeData(List<Episode> items) {
        if (items == null || items.isEmpty()) return false;
        for (Episode item : items) {
            if (item != null && TmdbEpisodeMatcher.shouldApply(item, item.getTmdbEpisode())) return true;
        }
        return false;
    }

    /**
     * 是否已经收到任何 TMDB 分集数据，不校验集号是否对得上。
     * 判断"刮削是否还没回来"要用这个，否则整季误匹配会让 loading 一直不退。
     */
    private static boolean hasTmdbEpisodePayload(List<Episode> items) {
        if (items == null || items.isEmpty()) return false;
        for (Episode item : items) {
            if (item != null && item.getTmdbEpisode() != null) return true;
        }
        return false;
    }

    public static boolean shouldUseTmdbEpisodeCards(boolean tmdbSourceEnabled, List<Episode> items) {
        return tmdbSourceEnabled && hasTmdbEpisodeData(items);
    }

    public static boolean shouldWaitForTmdbEpisodes(boolean tmdbSourceEnabled, boolean tmdbEpisodeEnrichmentPending, boolean tmdbAdapterReady, boolean tmdbEpisodeMetadataLoaded, List<Episode> items) {
        return tmdbSourceEnabled && tmdbEpisodeEnrichmentPending && tmdbAdapterReady && !tmdbEpisodeMetadataLoaded && items != null && !items.isEmpty() && !hasTmdbEpisodePayload(items);
    }

    public static boolean shouldShowTmdbEpisodeChrome(boolean tmdbSourceEnabled, boolean waitingForTmdbEpisodes, List<Episode> items) {
        return tmdbSourceEnabled && (hasTmdbEpisodeData(items) || waitingForTmdbEpisodes);
    }

    public static boolean shouldShowEpisodeGroup(int groupCount, boolean tmdbDetailLayout) {
        return groupCount > 1;
    }
}
