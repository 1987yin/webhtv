package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ReaderPlaybackRoutingSourceTest {

    @Test
    public void readerResultNeverFallsThroughToVideoPipeline() throws Exception {
        String source = read("app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java");

        int route = source.indexOf("NovelRouter.isReaderUrl(result)");
        int pipeline = source.indexOf("player().parse(", route);
        int unconditionalReturn = source.indexOf("return;", route);

        assertTrue("reader routing must exist in startPlayer", route >= 0);
        assertTrue("reader result must return before the video pipeline", unconditionalReturn > route
                && (pipeline < 0 || unconditionalReturn < pipeline));
    }

    @Test
    public void readerDispatchKeepsHostPageInBackstack() throws Exception {
        for (String path : new String[] {
                "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
                "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java"
        }) {
            String source = read(path);
            int dispatch = source.indexOf("ContentDispatcher.dispatchResult(this");
            int stop = source.indexOf("stopPlayback();", dispatch);
            int finish = source.indexOf("finish();", stop);
            int blockEnd = source.indexOf('}', stop);

            assertTrue(path + " must dispatch results through ContentDispatcher", dispatch >= 0);
            assertTrue(path + " must stop playback after reader dispatch", stop > dispatch);
            assertTrue(path + " must keep its page in the back stack after reader dispatch",
                    finish < 0 || finish > blockEnd);
        }
    }

    @Test
    public void reclaimedOrRepeatedPlayerResultsAreNotStartedTwice() throws Exception {
        for (String path : new String[] {
                "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
                "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java"
        }) {
            String source = read(path);

            assertTrue(path + " must track the result already applied to the player",
                    source.contains("mAppliedPlayerResult"));
            assertTrue(path + " must ignore a duplicate player result while playback remains active",
                    source.contains("if (result == mAppliedPlayerResult && !player().isEmpty()) return;"));
        }
    }

    /**
     * 返回键要能真正退出阅读器。
     *
     * 生命周期顺序是 阅读器 onPause -> 宿主 onResume -> 阅读器 onStop -> 阅读器 onDestroy。
     * 宿主 onResume 会因 shouldReclaim() 重新派发上一次的 playerContent 结果，
     * 关闭时间戳若等到 onDestroy 才写，那一刻 NovelRouter 的两道防线同时失效，
     * 阅读器会被立刻重新拉起，返回键表现为完全无效。
     */
    @Test
    public void readerMarksClosedBeforeHostResumes() throws Exception {
        String source = read("app/src/main/java/com/fongmi/android/tv/ui/web/WebReaderActivity.java");

        int pause = source.indexOf("protected void onPause()");
        int markInPause = source.indexOf("markClosed();", pause);
        int superPause = source.indexOf("super.onPause();", pause);
        int destroy = source.indexOf("protected void onDestroy()");

        assertTrue("onPause must exist", pause >= 0);
        assertTrue("onPause must mark the reader closed before super.onPause()",
                markInPause > pause && markInPause < superPause);
        assertTrue("markClosed must run before onDestroy is reached", markInPause < destroy);
        assertTrue("markClosed must clear the reader registration",
                source.contains("NovelRouter.currentReader = null;"));
        assertTrue("markClosed must stamp the close time",
                source.contains("NovelRouter.readerClosedAt = System.currentTimeMillis();"));
        assertTrue("onDestroy must still mark closed for non-finish teardown",
                source.indexOf("markClosed();", destroy) > destroy);
    }

    /**
     * 首帧不能把进度记成批次末尾锚点。
     *
     * 漫画首批只挂 5 张图（未解码高度为 0），小说屏外段落的 content-visibility 还没展开，
     * 此时没有锚点能跨过阈值，遍历会退化成「最后一个锚点」——95 页漫画记成第 5 页、
     * 33 段小说记成第 33 段，下次进来就直接跳到那里。
     */
    @Test
    public void readerAnchorIndexShortCircuitsAtTopOfChapter() throws Exception {
        String source = read("app/src/main/assets/reader.html");

        int fn = source.indexOf("function currentAnchorIndex()");
        int guard = source.indexOf("if(scrolled <= 0) return 0;", fn);
        int loop = source.indexOf("for(", fn);
        int heightBreak = source.indexOf("if(rect.height <= 0) break;", fn);

        assertTrue("currentAnchorIndex must exist", fn >= 0);
        assertTrue("stopping at the top must short-circuit to anchor 0 before scanning",
                guard > fn && guard < loop);
        assertTrue("an undecoded (zero-height) anchor must stop the scan instead of advancing",
                heightBreak > loop);
        assertTrue("progress bar must read the current anchor, not the loaded count",
                source.contains(": Math.min(total, currentAnchorIndex() + 1);"));
        // 整章一屏放得下时确实全部可见，读数要给总数，不能因为没滚动就停在 1
        assertTrue("a fully visible chapter must report every anchor as read",
                source.contains("els.length >= total && !scrollable"));
    }

    private static String read(String path) throws Exception {
        Path direct = Path.of(path);
        if (Files.exists(direct)) return Files.readString(direct, StandardCharsets.UTF_8);
        return Files.readString(Path.of(path.substring("app/".length())), StandardCharsets.UTF_8);
    }
}
