package com.fongmi.android.tv.bean;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TmdbMatchCacheTest {

    @Test
    public void titleScopedCacheSeparatesSameSiteAndVodId() {
        TmdbMatchCache cache = new TmdbMatchCache();

        cache.put("玩偶|虎斑2", "shared", "云秀行（真彩）", item(100, "云秀行"));
        cache.put("玩偶|虎斑2", "shared", "千香（真彩）", item(200, "千香"));

        assertEquals(100, cache.find("玩偶|虎斑2", "shared", "云秀行（真彩）").getTmdbId());
        assertEquals(200, cache.find("玩偶|虎斑2", "shared", "千香（真彩）").getTmdbId());
    }

    @Test
    public void titleScopedFindSkipsConflictingLegacyCache() {
        TmdbMatchCache cache = new TmdbMatchCache();

        cache.put("玩偶|虎斑2", "shared", item(200, "千香"));

        assertNull(cache.find("玩偶|虎斑2", "shared", "云秀行（真彩）"));
        assertEquals(200, cache.find("玩偶|虎斑2", "shared", "千香（真彩）").getTmdbId());
    }

    @Test
    public void titleScopedFindFallsBackAcrossSites() {
        TmdbMatchCache cache = new TmdbMatchCache();

        cache.put("site-a", "vod-a", "庆余年 第二季", item(100, "庆余年"));

        assertEquals(100, cache.find("site-b", "vod-b", "庆余年 第二季").getTmdbId());
    }

    @Test
    public void titleScopedFallbackIsRemovedWhenSameTitleConflicts() {
        TmdbMatchCache cache = new TmdbMatchCache();

        cache.put("site-a", "vod-a", "重名剧", item(100, "重名剧"));
        cache.put("site-b", "vod-b", "重名剧", item(200, "重名剧"));

        assertNull(cache.find("site-c", "vod-c", "重名剧"));
        assertEquals(100, cache.find("site-a", "vod-a", "重名剧").getTmdbId());
        assertEquals(200, cache.find("site-b", "vod-b", "重名剧").getTmdbId());
    }

    @Test
    public void manualMatchSurvivesTitleRewriteAndBlocksAutomaticOverwrite() {
        TmdbMatchCache cache = new TmdbMatchCache();

        cache.put("site", "vod", "凡人修仙传 更新至120集", item(100, "自动猜错的剧"));
        cache.putManual("site", "vod", List.of("凡人修仙传 更新至120集"), item(200, "凡人修仙传"));

        // 手动选择在原站源标题下读回。
        assertEquals(200, cache.find("site", "vod", "凡人修仙传 更新至120集").getTmdbId());
        assertTrue(cache.isManual("site", "vod", "凡人修仙传 更新至120集"));
        // 富集把 vod.getName() 改写成 TMDB 标题后依然读回。
        assertEquals(200, cache.find("site", "vod", "凡人修仙传").getTmdbId());
        assertEquals(200, cache.findManual("site", "vod", "凡人修仙传").getTmdbId());

        // 后续自动匹配不得覆盖。
        cache.put("site", "vod", "凡人修仙传 更新至120集", item(300, "又一个自动猜测"));
        cache.put("site", "vod", item(300, "又一个自动猜测"));
        assertEquals(200, cache.find("site", "vod", "凡人修仙传 更新至120集").getTmdbId());
    }

    @Test
    public void manualMatchAnchorDoesNotLeakToOtherTitlesSharingOneVodId() {
        TmdbMatchCache cache = new TmdbMatchCache();

        cache.putManual("玩偶|虎斑2", "shared", List.of("云秀行（真彩）"), item(100, "云秀行"));
        cache.put("玩偶|虎斑2", "shared", "千香（真彩）", item(200, "千香"));

        assertEquals(100, cache.find("玩偶|虎斑2", "shared", "云秀行（真彩）").getTmdbId());
        assertEquals(200, cache.find("玩偶|虎斑2", "shared", "千香（真彩）").getTmdbId());
        assertFalse(cache.isManual("玩偶|虎斑2", "shared", "千香（真彩）"));
    }

    @Test
    public void manualAnchorWithoutUsableAliasDoesNotBecomeWildcard() {
        TmdbMatchCache cache = new TmdbMatchCache();

        // 站源标题与 TMDB 标题都被清洗成空串时，锚点不能退化成通配符去匹配别的作品。
        cache.putManual("site", "shared", List.of("【】"), item(100, "【】"));

        assertNull(cache.findManual("site", "shared", "另一部剧"));
        assertFalse(cache.isManual("site", "shared", "另一部剧"));
        // 精确的站源标题键仍然可用。
        assertEquals(100, cache.find("site", "shared", "【】").getTmdbId());
    }

    private static TmdbItem item(int id, String title) {
        return new TmdbItem(id, "tv", title, "", "", "", "");
    }
}
