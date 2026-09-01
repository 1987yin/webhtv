package com.fongmi.android.tv.service;

import com.fongmi.android.tv.service.IntroSkipService.IntroSkipPlan;
import com.fongmi.android.tv.service.IntroSkipService.Segment;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class IntroSkipServiceTest {

    @Test
    public void parseIntroDb_readsIntroRecapAndOutroSegments() {
        String body = "{"
                + "\"imdb_id\":\"tt0944947\","
                + "\"season\":1,"
                + "\"episode\":1,"
                + "\"intro\":{\"start_sec\":437,\"end_sec\":531,\"confidence\":1,\"submission_count\":2},"
                + "\"recap\":{\"start_ms\":12000,\"end_ms\":82000,\"confidence\":0.8,\"submission_count\":1},"
                + "\"outro\":{\"start_sec\":3631.5,\"end_sec\":3699.5,\"confidence\":1,\"submission_count\":2}"
                + "}";

        IntroSkipPlan plan = IntroSkipService.parseIntroDb(body, 3_700_000);

        assertFalse(plan.isEmpty());
        assertEquals(2, plan.getOpenings().size());
        assertEquals(Segment.Kind.RECAP, plan.getOpenings().get(0).getKind());
        assertEquals(12_000, plan.getOpenings().get(0).getStartMs());
        assertEquals(82_000, plan.getOpenings().get(0).getEndMs());
        assertEquals(Segment.Kind.INTRO, plan.getOpenings().get(1).getKind());
        assertEquals(437_000, plan.getOpenings().get(1).getStartMs());
        assertEquals(531_000, plan.getOpenings().get(1).getEndMs());
        assertEquals(1, plan.getEndings().size());
        assertEquals(3_631_500, plan.getEndings().get(0).getStartMs());
        // 结束点距本集结尾只有 500ms，等于放到文件结束：贴到 duration 并标记 openEnded
        assertEquals(3_700_000, plan.getEndings().get(0).getEndMs());
        assertTrue(plan.getEndings().get(0).isOpenEnded());
    }

    @Test
    public void parseTheIntroDb_readsIntroRecapAndCredits() {
        String body = "{"
                + "\"tmdb_id\":12345,"
                + "\"type\":\"movie\","
                + "\"intro\":[{\"start_ms\":null,\"end_ms\":23000}],"
                + "\"recap\":[{\"start_ms\":25000,\"end_ms\":134000}],"
                + "\"credits\":[{\"start_ms\":5801777,\"end_ms\":6371111}]"
                + "}";

        IntroSkipPlan plan = IntroSkipService.parseTheIntroDb(body, 7_200_000);

        assertEquals(2, plan.getOpenings().size());
        assertEquals(Segment.Kind.INTRO, plan.getOpenings().get(0).getKind());
        assertEquals(0, plan.getOpenings().get(0).getStartMs());
        assertEquals(23_000, plan.getOpenings().get(0).getEndMs());
        assertEquals(Segment.Kind.RECAP, plan.getOpenings().get(1).getKind());
        assertEquals(25_000, plan.getOpenings().get(1).getStartMs());
        assertEquals(134_000, plan.getOpenings().get(1).getEndMs());
        assertEquals(1, plan.getEndings().size());
        assertEquals(5_801_777, plan.getEndings().get(0).getStartMs());
        assertEquals(6_371_111, plan.getEndings().get(0).getEndMs());
        // 片尾后还剩 80 分钟内容，是个真实可跳的落点
        assertFalse(plan.getEndings().get(0).isOpenEnded());
    }

    @Test
    public void parseTheIntroDb_shiftsCreditsWhenLocalSourceIsShorter() {
        // 参考版本 45:00，本地片源 44:00（掐头/删减）：片尾应按「距结尾 1:00」折算，而不是被整段丢弃
        String body = "{\"duration_ms\":2700000,\"credits\":[{\"start_ms\":2640000,\"end_ms\":2695000}]}";

        IntroSkipPlan plan = IntroSkipService.parseTheIntroDb(body, 2_640_000);

        assertEquals(1, plan.getEndings().size());
        assertEquals(2_580_000, plan.getEndings().get(0).getStartMs());
        assertEquals(2_640_000, plan.getEndings().get(0).getEndMs());
        assertTrue(plan.getEndings().get(0).isOpenEnded());
    }

    @Test
    public void parseTheIntroDb_keepsPostCreditsSceneSkippable() {
        // 片尾后还有 2 分钟彩蛋：折算后仍是可 seek 的落点，不能被当成 openEnded
        String body = "{\"duration_ms\":7200000,\"credits\":[{\"start_ms\":6900000,\"end_ms\":7080000}]}";

        IntroSkipPlan plan = IntroSkipService.parseTheIntroDb(body, 7_140_000);

        assertEquals(1, plan.getEndings().size());
        assertEquals(6_840_000, plan.getEndings().get(0).getStartMs());
        assertEquals(7_020_000, plan.getEndings().get(0).getEndMs());
        assertFalse(plan.getEndings().get(0).isOpenEnded());
    }

    @Test
    public void parseTheIntroDb_dropsCreditsWhenLocalSourceIsFarTooShort() {
        // 参考 2 小时、片尾距结尾 20 分钟，本地只有 16 分钟（错配到了预告/片段）：
        // 折算后片尾起点落到 0 之前，无法对上时间轴，必须丢弃而不是乱跳
        String body = "{\"duration_ms\":7200000,\"credits\":[{\"start_ms\":6000000,\"end_ms\":null}]}";

        IntroSkipPlan plan = IntroSkipService.parseTheIntroDb(body, 1_000_000);

        assertEquals(0, plan.getEndings().size());
    }

    @Test
    public void parseTheIntroDb_ignoresTinyDurationDrift() {
        // 差值在容差内（1s）：不折算，保持原始时间戳
        String body = "{\"duration_ms\":2700000,\"credits\":[{\"start_ms\":2640000,\"end_ms\":2680000}]}";

        IntroSkipPlan plan = IntroSkipService.parseTheIntroDb(body, 2_699_000);

        assertEquals(1, plan.getEndings().size());
        assertEquals(2_640_000, plan.getEndings().get(0).getStartMs());
        assertEquals(2_680_000, plan.getEndings().get(0).getEndMs());
    }

    @Test
    public void parseTheIntroDb_usesDurationForOpenEndedCredits() {
        String body = "{\"credits\":[{\"start_ms\":6408000,\"end_ms\":null}]}";

        IntroSkipPlan plan = IntroSkipService.parseTheIntroDb(body, 7_200_000);

        assertEquals(1, plan.getEndings().size());
        assertEquals(6_408_000, plan.getEndings().get(0).getStartMs());
        assertEquals(7_200_000, plan.getEndings().get(0).getEndMs());
    }

    @Test
    public void resolve_deduplicatesOverlappingProviderSegments() {
        List<IntroSkipService.RawSegment> raw = new ArrayList<>();
        raw.addAll(IntroSkipService.parseIntroDbRaw("{\"intro\":{\"start_ms\":437000,\"end_ms\":531000,\"confidence\":1,\"submission_count\":2}}"));
        raw.addAll(IntroSkipService.parseTheIntroDbRaw("{\"intro\":[{\"start_ms\":438000,\"end_ms\":530000}]}"));

        IntroSkipPlan merged = IntroSkipPlan.from(raw, 3_700_000);

        // 两家报的是同一段片头，保留分数更高的 IntroDB
        assertEquals(1, merged.getOpenings().size());
        assertEquals("IntroDB", merged.getOpenings().get(0).getProvider());
        assertEquals(437_000, merged.getOpenings().get(0).getStartMs());
    }

    @Test
    public void resolve_keepsAdjacentCreditsAndPreviewAsSeparateSegments() {
        // 片尾紧接下集预告：起点只差 1 秒，但类型不同，不能被去重合成一段
        String body = "{\"duration_ms\":1500000,"
                + "\"credits\":[{\"start_ms\":1380000,\"end_ms\":1439000}],"
                + "\"preview\":[{\"start_ms\":1440000,\"end_ms\":1500000}]}";

        IntroSkipPlan plan = IntroSkipService.parseTheIntroDb(body, 1_500_000);

        assertEquals(2, plan.getEndings().size());
        assertEquals(Segment.Kind.OUTRO, plan.getEndings().get(0).getKind());
        assertEquals(Segment.Kind.PREVIEW, plan.getEndings().get(1).getKind());
    }

    @Test
    public void parseTheIntroDb_readsPreviewSegment() {
        String body = "{\"duration_ms\":1500000,\"preview\":[{\"start_ms\":1450000,\"end_ms\":null}]}";

        IntroSkipPlan plan = IntroSkipService.parseTheIntroDb(body, 1_500_000);

        assertEquals(1, plan.getEndings().size());
        assertEquals(Segment.Kind.PREVIEW, plan.getEndings().get(0).getKind());
        assertTrue(plan.getEndings().get(0).isOpenEnded());
    }

    @Test
    public void cacheKey_ignoresSubMinuteDurationJitter() {
        // HLS 时长秒级抖动不该穿透缓存
        IntroSkipService.Query a = new IntroSkipService.Query(123, "tt1", "tv", 1, 2, 2_700_000);
        IntroSkipService.Query b = new IntroSkipService.Query(123, "tt1", "tv", 1, 2, 2_701_500);

        assertEquals(a.cacheKey(), b.cacheKey());
    }

    @Test
    public void cacheKey_preloadShareesBucketWithUnknownDuration() {
        // 预载时时长未知（0），onPrepare 时同样未知，两者必须命中同一条缓存
        IntroSkipService.Query preload = new IntroSkipService.Query(123, "tt1", "tv", 1, 2, 0);
        IntroSkipService.Query prepare = new IntroSkipService.Query(123, "tt1", "tv", 1, 2, 0);

        assertEquals(preload.cacheKey(), prepare.cacheKey());
    }

    @Test
    public void cacheKey_separatesMateriallyDifferentDurations() {
        // 真换了片源（差几分钟）时 duration_ms 会带出不同响应，不能复用
        IntroSkipService.Query full = new IntroSkipService.Query(123, "tt1", "tv", 1, 2, 2_700_000);
        IntroSkipService.Query cut = new IntroSkipService.Query(123, "tt1", "tv", 1, 2, 2_400_000);

        assertNotEquals(full.cacheKey(), cut.cacheKey());
    }

    @Test
    public void parseTheIntroDb_keepsZeroStartIntroAsSkippable() {
        // 实测响应形状（逐玉 S1E1）：第一段片头 start_ms 为 null，落成 0→45s。
        // 这类「从 0 开始」的片头必须照常保留，续播护栏才有东西可判。
        String body = "{\"tmdb_id\":279388,\"type\":\"tv\",\"season\":1,\"episode\":1,"
                + "\"intro\":[{\"start_ms\":null,\"end_ms\":45000},{\"start_ms\":160000,\"end_ms\":253000}]}";

        IntroSkipPlan plan = IntroSkipService.parseTheIntroDb(body, 2_841_680);

        assertEquals(2, plan.getOpenings().size());
        assertEquals(0, plan.getOpenings().get(0).getStartMs());
        assertEquals(45_000, plan.getOpenings().get(0).getEndMs());
        assertEquals(160_000, plan.getOpenings().get(1).getStartMs());
        assertEquals(253_000, plan.getOpenings().get(1).getEndMs());
        assertEquals(0, plan.getEndings().size());
    }

    @Test
    public void rawSegments_resolveAgainstDifferentDurations() {
        // 同一份缓存（原始段）换不同片源时长，各自折算出对得上本地时间轴的结果
        List<IntroSkipService.RawSegment> raw = IntroSkipService.parseTheIntroDbRaw(
                "{\"duration_ms\":2700000,\"credits\":[{\"start_ms\":2640000,\"end_ms\":2695000}]}");

        IntroSkipPlan reference = IntroSkipPlan.from(raw, 2_700_000);
        IntroSkipPlan shorter = IntroSkipPlan.from(raw, 2_640_000);

        assertEquals(2_640_000, reference.getEndings().get(0).getStartMs());
        assertEquals(2_580_000, shorter.getEndings().get(0).getStartMs());
    }
}
