package com.fongmi.android.tv.ui.novel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 阅读进度的落库编码：历史列表按 position/duration 画进度条。
 *
 * 只把「读完」编码为 duration，其余锚点原样存序号 —— 这样读完是 100%，
 * 而升级前写入的存量 0 基记录读回来也不会平移。
 */
public class ReaderHistoryProgressTest {

    @Test
    public void finishedChapterFillsTheBar() {
        assertEquals(33, ReaderHistory.toPosition(32, 33));
        assertEquals(2, ReaderHistory.toPosition(1, 2));
        assertEquals(1, ReaderHistory.toPosition(0, 1));
    }

    @Test
    public void midChapterAnchorIsStoredAsIs() {
        assertEquals(0, ReaderHistory.toPosition(0, 33));
        assertEquals(11, ReaderHistory.toPosition(11, 33));
        assertEquals(31, ReaderHistory.toPosition(31, 33));
    }

    @Test
    public void anchorSurvivesTheRoundTrip() {
        for (int total : new int[] {1, 2, 3, 33, 95}) {
            for (int anchor = 0; anchor < total; anchor++) {
                long position = ReaderHistory.toPosition(anchor, total);
                assertEquals("total=" + total + " anchor=" + anchor,
                        anchor, ReaderHistory.toAnchor(position, total));
            }
        }
    }

    /** 打开后不滚动就退出会把恢复值原样回写，必须不漂移。 */
    @Test
    public void repeatedOpenAndExitDoesNotDrift() {
        int total = 33;
        for (long start : new long[] {0, 12, 31, 33}) {
            long position = start;
            for (int cycle = 0; cycle < 5; cycle++) {
                int anchor = ReaderHistory.toAnchor(position, total);
                position = ReaderHistory.toPosition(anchor, total);
            }
            assertEquals("start=" + start, start, position);
        }
    }

    /**
     * 升级前的存量记录不会被误判成「读完」。
     * 旧代码上限是 min(duration, anchor)，anchor 最大 total-1，故旧 position 恒小于 duration。
     */
    @Test
    public void legacyRowsAreNotMistakenForFinished() {
        int total = 33;
        for (int legacy = 0; legacy <= total - 1; legacy++) {
            assertTrue("legacy=" + legacy, legacy < total);
            assertEquals("legacy=" + legacy, legacy, ReaderHistory.toAnchor(legacy, total));
        }
    }

    @Test
    public void degenerateTotalsDoNotCrash() {
        assertEquals(0, ReaderHistory.toAnchor(5, 0));
        assertEquals(0, ReaderHistory.toAnchor(-1, 10));
        assertEquals(0, ReaderHistory.toPosition(-5, 10));
    }
}
