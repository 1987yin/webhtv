package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
                source.contains("NovelRouter.markReaderClosed();"));
        assertTrue("onDestroy must still mark closed for non-finish teardown",
                source.indexOf("markClosed();", destroy) > destroy);
    }

    /**
     * 首帧不能把进度虚报到批次末尾。
     *
     * 漫画首批只挂 5 张图，未解码时 bottom 全堆在同一 y 上，没有锚点跨过阈值，
     * 循环末尾的 `idx = i` 就把序号推到最后一个锚点 —— 95 页漫画记成第 5 页、
     * 33 段小说记成第 33 段，下次进来直接跳到那里。
     *
     * 注意不能用 rect.height 当「未就绪」信号：小说段落带 content-visibility:auto，
     * 真机实测 height 恒为 0 而 bottom 有效，用 height 判断会让小说进度永远停在第 1 段。
     */
    @Test
    public void readerAnchorIndexShortCircuitsAtTopOfChapter() throws Exception {
        String source = read("app/src/main/assets/reader.html");

        int fn = source.indexOf("function currentAnchorIndex()");
        int guard = source.indexOf("if(scrolled <= 0) return lastAnchorIndex = 0;", fn);
        int loop = source.indexOf("for(", fn);
        int degraded = source.indexOf("if(found || atDocumentEnd()) return lastAnchorIndex = idx;", fn);

        assertTrue("currentAnchorIndex must exist", fn >= 0);
        assertTrue("stopping at the top must short-circuit to anchor 0 before scanning",
                guard > fn && guard < loop);
        assertTrue("a degraded measurement must not advance the anchor unless the document end is reached",
                degraded > loop);
        assertFalse("rect.height is always 0 for content-visibility paragraphs; it must not gate the scan",
                source.contains("if(rect.height <= 0) break;"));
        assertTrue("progress bar must read the current anchor, not the loaded count",
                source.contains(": Math.min(total, currentAnchorIndex() + 1);"));
        // 整章第一屏就全部露出时确实读完了，读数给总数；判据必须带上界，
        // 否则往下滚后末锚点 bottom 变负会被误判成读完
        assertTrue("a fully visible chapter must report every anchor as read",
                source.contains("last.bottom > 0 && last.bottom <= window.innerHeight"));
    }

    /**
     * 迟到的切章结果不能重新拉起已关闭的阅读器。
     *
     * 1500ms 静默期只挡得住紧随返回的那一拨回调；用户点了下一章又马上返回时，
     * 爬虫可能几秒后才回，这条结果落在窗口外就会另起一个阅读器压在宿主上面，
     * 表现仍然是返回键无效。用关闭代号比对才能识别它属于已经关掉的那个阅读器。
     */
    @Test
    public void staleChapterResultDoesNotRelaunchClosedReader() throws Exception {
        String router = read("app/src/main/java/com/fongmi/android/tv/ui/novel/NovelRouter.java");
        String reader = read("app/src/main/java/com/fongmi/android/tv/ui/web/WebReaderActivity.java");

        assertTrue("closing the reader must bump a generation counter",
                router.contains("readerCloseGen++;"));
        assertTrue("a chapter request must record the generation it belongs to",
                router.contains("pendingChapterGen = readerCloseGen;"));
        assertTrue("a stale generation must be detected",
                router.contains("return gen != readerCloseGen;"));
        // 三个拉起阅读器的入口都必须过这道闸，只改一个仍会漏。
        // 只数 `if (...)` 调用点，避免把方法定义和注释里的提及也算进来。
        assertEquals("every relaunch site must consult the suppression guard",
                3, countOccurrences(router, "if (shouldSuppressRelaunch())"));
        assertTrue("the guard must combine the silence window and the stale check",
                router.contains("return justClosed() || isStaleChapterResult();"));
        assertTrue("the reader must tag its chapter switch before handing it to the host",
                reader.contains("NovelRouter.noteChapterRequest(); h.labPlayEpisode(chapterUrl);"));
    }

    /**
     * 切章成功送达前台后必须清掉在途标记。
     *
     * 「送达前台阅读器」这条路径提前 return，不经过 shouldSuppressRelaunch()，
     * 所以标记不会被 isStaleChapterResult() 顺手清掉。留着它的后果是：
     * 用户关掉阅读器、过一会儿主动打开另一本书时，那次合法打开会被误判成过期结果吞掉。
     */
    @Test
    public void deliveredChapterResultClearsThePendingTag() throws Exception {
        String router = read("app/src/main/java/com/fongmi/android/tv/ui/novel/NovelRouter.java");

        assertTrue("a clear helper must exist", router.contains("private static void clearChapterRequest()"));
        // 三个入口在把结果交给前台阅读器之前都要清，漏一个就会吞掉后续的合法打开
        assertEquals("every foreground-delivery path must clear the pending tag",
                3, countOccurrences(router, "            clearChapterRequest();"));
        // 换行符按仓库检出方式可能是 CRLF，统一后再比对顺序
        String normalized = router.replace("\r\n", "\n");
        for (String delivery : new String[] {
                "clearChapterRequest();\n            reader.onEpisodeResolved(kind, result.getRealUrl()",
                "clearChapterRequest();\n            reader.onEpisodeResolved(kind, payload, title);",
                "clearChapterRequest();\n            reader.onEpisodeResolved(kind, payload, extractTitle(payload));"
        }) {
            assertTrue("the tag must be cleared immediately before delivering: " + delivery,
                    normalized.contains(delivery));
        }
    }

    /**
     * 阅读器回到前台必须重新登记。
     *
     * onPause 交还前台时会清掉注册，只在 onCreate 注册的话，两个阅读器叠栈时
     * 下层那个再次回到前台就永久失去注册，之后它自己的切章会另起一个实例压在自己上面。
     */
    @Test
    public void readerReRegistersOnResume() throws Exception {
        String source = read("app/src/main/java/com/fongmi/android/tv/ui/web/WebReaderActivity.java");

        int resume = source.indexOf("protected void onResume()");
        int register = source.indexOf("NovelRouter.currentReader = this;", resume);
        int pause = source.indexOf("protected void onPause()");

        assertTrue("onResume must exist", resume > 0);
        assertTrue("onResume must re-register the reader", register > resume && register < pause);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String read(String path) throws Exception {
        Path direct = Path.of(path);
        if (Files.exists(direct)) return Files.readString(direct, StandardCharsets.UTF_8);
        return Files.readString(Path.of(path.substring("app/".length())), StandardCharsets.UTF_8);
    }
}
