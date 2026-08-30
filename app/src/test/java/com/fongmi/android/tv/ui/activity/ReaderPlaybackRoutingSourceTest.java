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
        int degraded = source.indexOf("return lastAnchorIndex = idx;", loop);

        assertTrue("currentAnchorIndex must exist", fn >= 0);
        assertTrue("stopping at the top must short-circuit to anchor 0 before scanning",
                guard > fn && guard < loop);
        assertTrue("a degraded measurement must not advance the anchor unless the document end is reached",
                degraded > loop);
        // #reader 的 min-height:105vh 让「图全未解码」时轻扫 32px 就到文末，
        // 只判 atDocumentEnd 会把退化值当可信 —— 必须同时要求锚点已全部进 DOM
        assertTrue("reaching the document end alone must not validate a degraded measurement",
                source.contains("anchorsSettled() && atDocumentEnd()"));
        assertFalse("rect.height is always 0 for content-visibility paragraphs; it must not gate the scan",
                source.contains("if(rect.height <= 0) break;"));
        assertTrue("progress bar must read the current anchor, not the loaded count",
                source.contains("Math.min(total, effectiveAnchorIndex() + 1)"));
        // 落库与进度条必须同一个真值，否则会出现「显示 33/33、重进回到第 30 段」的分叉
        assertTrue("the saved anchor must come from the same source as the progress bar",
                source.contains("effectiveAnchorIndex(), anchorTotal());"));
        // 小说正文下方还有 140px 内边距和章节导航，光靠 currentAnchorIndex 够不到最后一段，
        // 历史列表就永远到不了 100%
        assertTrue("a chapter read to its end must be able to record the final anchor",
                source.contains("if(anchorsSettled() && atDocumentEnd()) return total - 1;"));
        // 退化态统一由 anchorsSettled() 把关：漫画末页图加载失败时高度恒为 0、不占空间；
        // PDF 的 canvas 先插入后绘制，未绘制时是固有 300x150（有高度但不是真实页高），
        // 只判「到底」会把用户没看到的内容记成读完，下次直接跳到章末
        assertTrue("a degraded layout must be recognised before trusting the document end",
                source.contains("function anchorsSettled()"));
        // 漫画看末尾两页：图各自异步解码，末页不保证最后完成，只看末页仍会虚报
        assertTrue("a comic must require the last two pages to be laid out",
                source.contains("for(var i = Math.max(0, total - 2); i < total; i++){"));
        assertTrue("a PDF must require every page to be painted, not merely appended",
                source.contains("return pdfDoc != null && pdfAppendedCount >= pdfDoc.numPages;"));
        // 渲染失败也要计数，否则那一章永远达不到 numPages，永远记不成读完
        assertTrue("a failed page render must still count towards the settled total",
                source.contains(".promise.then(pageSettled, pageSettled);"));
        assertTrue("a failed getPage must also count",
                source.contains("}, pageSettled);"));
        // 换章会清零计数，上一章在途的回调若继续 ++ 会把新章撑到 numPages
        assertTrue("stale render callbacks must not inflate the next chapter's count",
                source.contains("var gen = pdfGen;")
                        && source.contains("if(gen !== pdfGen) return;"));
        // #reader 是所有章节共用的容器：getDocument 的成功/失败回调也必须带代号，
        // 否则上一章的慢加载会把新章内容整段替换成 pdf-error，或把旧 doc 塞进新章
        assertEquals("both getDocument handlers must be generation-guarded",
                3, countOccurrences(source, "if(gen !== pdfGen) return;"));
        // renderPdf 只在新章也是 PDF 时才调，从 PDF 切到漫画要靠 renderContent 作废代号
        assertTrue("switching away from a PDF chapter must invalidate its callbacks",
                source.contains("pdfGen++;") && source.contains("pdfDoc = null; pdfAppendedCount = 0;"));
        // fulfillment 里同步抛错不会被同一个 then 的 rejection 处理器捕获
        assertTrue("a throw inside the fulfillment handler must still count",
                source.contains("} catch(e){") && source.contains("pageSettled();"));
        assertTrue("currentAnchorIndex must use the same settled check",
                source.contains("if(found || (anchorsSettled() && atDocumentEnd()))"));
        // memo 有副作用：文末分支提前 return 前必须先让 currentAnchorIndex() 跑过一次
        int effFn = source.indexOf("function effectiveAnchorIndex()");
        int memoUpdate = source.indexOf("Math.min(total - 1, currentAnchorIndex());", effFn);
        int endBranch = source.indexOf("if(anchorsSettled() && atDocumentEnd())", effFn);
        assertTrue("the memo must be updated before the document-end shortcut returns",
                memoUpdate > effFn && memoUpdate < endBranch);
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
                router.contains("pendingChapters.put(token, new long[]{readerCloseGen,"));
        assertTrue("a stale generation must be detected",
                router.contains("if (v[0] != gen) { pendingChapters.remove(e.getKey()); stale = true; }"));
        // 三个拉起阅读器的入口都必须过这道闸，只改一个仍会漏。
        // 只数 `if (...)` 调用点，避免把方法定义和注释里的提及也算进来。
        assertEquals("every relaunch site must consult the suppression guard",
                3, countOccurrences(router, "if (shouldSuppressRelaunch())"));
        assertTrue("the guard must combine the silence window and the stale check",
                router.contains("return justClosed() || stale;"));
        assertTrue("the reader must tag its chapter switch before handing it to the host",
                reader.contains("long token = NovelRouter.noteChapterRequest();"));
        // parse=1 兜底才是实际会走到的宿主解析路径：loadChapter 里那条在 siteKey 非空时
        // 不可达，而所有真实启动入口都会写 siteKey。漏了它标记永远不会被设上，整套判定失效。
        assertEquals("both host paths must tag their request",
                2, countOccurrences(reader, "long token = NovelRouter.noteChapterRequest();"));
        // 两个判定都要执行：|| 短路会让一次性的 isStaleChapterResult 不执行、标记留存，
        // 静默期后用户主动打开另一本书就会被误吞
        assertTrue("the stale check must not be short-circuited by the silence window",
                router.contains("boolean stale = isStaleChapterResult();"));
        // 宿主解析有多条静默失败路径不回到 NovelRouter（章节属于另一条线路、
        // playerContent 报错、解析出来是普通视频地址），标记必须能被撤销并自行过期，
        // 否则它会一直留着把用户之后主动打开的书误判成过期结果吞掉
        assertTrue("a failed chapter switch must abandon its pending tag",
                router.contains("public static void abandonChapterRequest(long token)"));
        // 与宿主请求无关的失败（空 URL、注入异常）必须传 0，否则会抹掉在途请求的标记
        assertTrue("an unrelated failure must not revoke any tag",
                reader.contains("private void chapterFailed(long token)")
                        && reader.contains("if (token != 0) {"));
        assertTrue("the empty-url path must pass a zero token",
                reader.contains("chapterFailed(0L); return;"));
        // 宿主找不到章节时会静默返回，必须立刻收尾而不是等 45s 过期
        assertTrue("a dispatch that never happened must close out immediately",
                reader.contains("if (!h.labPlayEpisode(chapterUrl)) chapterFailedWithToast(token);"));
        assertTrue("the host contract must report whether it dispatched",
                read("app/src/main/java/com/fongmi/android/tv/ui/novel/NovelReaderHost.java")
                        .contains("boolean labPlayEpisode(String chapterUrl);"));
        assertTrue("an unclaimed tag must expire on its own",
                router.contains("PENDING_CHAPTER_TTL"));
    }

    /**
     * 阅读进度落库不需要数据迁移，且读完即 100%。
     *
     * 历史列表按 position/duration 画进度条，若把「读到最后一个锚点」也存成序号，
     * 2 页的漫画短章读完只显示 50%；但若整体改成 1 基，升级前写入的存量记录会被
     * 统一平移一个锚点。折中：只把「读完」编码为 duration，其余沿用序号原值。
     */
    @Test
    public void readingProgressKeepsLegacyRowsAndStillReachesFullBar() throws Exception {
        String history = read("app/src/main/java/com/fongmi/android/tv/ui/novel/ReaderHistory.java");
        String reader = read("app/src/main/java/com/fongmi/android/tv/ui/web/WebReaderActivity.java");

        // 非读完的锚点原样存序号 —— 存量 0 基记录读回来不偏移
        assertTrue("a mid-chapter anchor must be stored as-is so legacy rows still resolve",
                history.contains("long value = Math.max(0, Math.min(duration - 1, anchor));"));
        assertTrue("only the finished state is encoded as duration",
                history.contains("return duration == SCALE ? duration - 1 : duration;"));
        assertTrue("the finished encoding must convert back to the last anchor",
                history.contains("if (position >= duration) return (int) (duration - 1);"));
        assertTrue("restore must go through the converter",
                reader.contains("ReaderHistory.toAnchor(h.getPosition(), restoreTotal)"));
        // 旧版小说记录存的是百分比×SCALE，不能走锚点换算
        assertTrue("legacy percent records must bypass the anchor conversion",
                reader.contains("restoreTotal == ReaderHistory.SCALE"));
        // 百分比分支必须解锁 restoringPage，否则整个会话都不再上报，旧记录也永远迁不到新语义
        String html = read("app/src/main/assets/reader.html");
        int percentFn = html.indexOf("function restoreScrollPercent(p)");
        assertTrue("restoreScrollPercent must exist", percentFn > 0);
        assertTrue("the legacy percent path must release the reporting lock",
                html.indexOf("restoringPage = false;", percentFn) > percentFn
                        && html.indexOf("restoringPage = false;", percentFn) < html.indexOf("function restoreAnchor(index)"));
        // max>0 挡不住：min-height:105vh 让它首帧就成立，上报会把 90% 的进度覆盖成章首
        // anchorsSettled() 对小说恒为真（段落一次性建完），首帧就放行等于没有护栏；
        // content-visibility 让屏外段落先按 30px 估算，此刻 scrollHeight 还会长大
        assertTrue("the percent restore must wait for the document height to settle",
                html.contains("if(!settled || lastMax === null || Math.abs(max - lastMax) >= 2) stable = 0;"));
        assertTrue("it must require three stable measurements like restoreAnchor",
                html.contains("var ready = stable >= 3;"));
        assertTrue("the percent restore must be invalidated when content is replaced",
                html.contains("var gen = ++restoreGen;") && html.contains("restoringPage = true;"));
        assertTrue("it must not report before the layout is ready",
                html.contains("if(ready) reportProgress();"));
        // 漫画一批批懒加载，光等不会让剩下的页进 DOM，anchorsSettled 永远为假 ——
        // 旧百分比记录既恢复不了也迁移不了，必须主动催加载
        assertTrue("the percent restore must drive lazy loading for comics and PDFs",
                html.contains("if(DATA.kind === 3) renderPdfMore();")
                        && html.contains("else if(DATA.kind !== 1) loadMoreComic();"));
        assertTrue("the percent restore budget must use the monotonic clock",
                html.contains("var deadline = nowMs() + 60000;"));
    }

    /**
     * 换章必须清掉锚点记忆。
     *
     * PDF 章在 pdfDoc 解析完成前 anchorTotal() 为 0，effectiveAnchorIndex() 会在
     * total<=0 处早退，不经过 currentAnchorIndex() 的归零短路 —— 上一章的高位值
     * 会残留，把新章第 1 页记成上一章的位置。
     */
    @Test
    public void switchingChapterResetsTheAnchorMemo() throws Exception {
        String html = read("app/src/main/assets/reader.html");

        int render = html.indexOf("function renderContent()");
        int reset = html.indexOf("lastAnchorIndex = 0;", render);
        int clear = html.indexOf("r.innerHTML = '';", render);

        assertTrue("renderContent must exist", render > 0);
        assertTrue("the memo must be cleared when the content is replaced",
                reset > render && reset < clear);
    }

    /**
     * 关闭静默期与在途标记的时限都必须用单调时钟。
     *
     * wall clock 会被 NTP 校正或用户改时间往回跳，一旦往回跳，「现在 - 关闭时刻」
     * 变成大负数而恒小于窗口，静默期就永不结束，之后所有阅读打开都被当成残留回调拦掉。
     */
    @Test
    public void relaunchGuardsUseMonotonicClock() throws Exception {
        String router = read("app/src/main/java/com/fongmi/android/tv/ui/novel/NovelRouter.java");

        assertFalse("the silence window must not depend on wall-clock time",
                router.contains("readerClosedAt = System.currentTimeMillis();"));
        assertFalse("the pending-tag TTL must not depend on wall-clock time",
                router.contains("pendingChapterAt = System.currentTimeMillis();"));
        assertEquals("both timestamps and both comparisons must use elapsedRealtime",
                4, countOccurrences(router, "android.os.SystemClock.elapsedRealtime()"));
    }

    /**
     * 只撤销自己发出的那次在途标记。
     *
     * chapterFailed() 还会被空 URL、注入异常等与宿主请求无关的路径调用，
     * 无条件撤销会把另一次仍在途的请求的标记抹掉，重新打开「返回键失效」的缺口。
     */
    @Test
    public void onlyTheOwnHostRequestAbandonsItsPendingTag() throws Exception {
        String reader = read("app/src/main/java/com/fongmi/android/tv/ui/web/WebReaderActivity.java");
        String router = read("app/src/main/java/com/fongmi/android/tv/ui/novel/NovelRouter.java");

        assertTrue("the reader must remember which host request is in flight",
                reader.contains("private volatile long hostChapterToken = 0L;"));
        assertEquals("both host-request sites must capture the token",
                2, countOccurrences(reader, "hostChapterToken = token;"));
        // 标记是全局单槽：撤销必须凭令牌确认归属，否则切章 C 失败会抹掉切章 B 的标记
        assertTrue("revocation must be gated on the token",
                reader.contains("NovelRouter.abandonChapterRequest(token);"));
        // 单槽会被新请求覆盖：切章 C 立刻失败撤销后，切章 B 的记录随之消失，
        // B 的迟到结果就不再被拦。改为每次请求各占一条。
        assertTrue("in-flight requests must be tracked per token, not in a single slot",
                router.contains("pendingChapters = new java.util.concurrent.ConcurrentHashMap<>()"));
        assertTrue("revocation must remove only its own entry",
                router.contains("pendingChapters.remove(token);"));
        assertTrue("a delivered result must close out the in-flight request",
                reader.contains("hostChapterToken = 0L;"));
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
