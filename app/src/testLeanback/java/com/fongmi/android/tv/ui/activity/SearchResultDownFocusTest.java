package com.fongmi.android.tv.ui.activity;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchResultDownFocusTest {

    @Test
    public void validDownPressFocusesTheNextResultImmediately() throws Exception {
        String source = read("app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java");
        int start = source.indexOf("private boolean onSearchDown(int position, int count)");
        int end = source.indexOf("private void focusSearchTarget(int position)");

        assertTrue("result-row navigation must define its down policy", start >= 0 && end > start);
        String down = source.substring(start, end);
        assertFalse("a valid down press must not defer movement to the system focus search",
                down.contains("if (bottom) mScroller.checkMore();\n            return bottom;"));
        assertTrue("a valid down press must return true before a new row is focused",
                down.contains("focusSearchTarget(next);") && down.contains("return true;"));
        assertTrue("navigation must still load the next row before focusing it",
                down.contains("mSearchAdapter.ensureLoaded(next + 1, count * 3);"));
    }

    @Test
    public void deferredResultFocusScrollsFirstAndRejectsStaleRequests() throws Exception {
        String source = read("app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java");
        int start = source.indexOf("private void focusSearchTarget(int position)");
        int end = source.indexOf("private boolean canFocusSearchResult(int position)");

        assertTrue("search results must define a deferred focus policy", start >= 0 && end > start);
        String focus = source.substring(start, end);
        assertTrue("a missing target must scroll to the requested row before focusing it",
                focus.contains("scrollToSearchResult(position);"));
        assertTrue("focus must be restored after the target is laid out",
                focus.contains("mBinding.recycler.post(") && focus.contains("laidOutTarget.requestFocus();"));
        assertTrue("a focused target must be aligned fully into view",
                focus.contains("alignSearchResultCard(target);")
                        && focus.contains("alignSearchResultCard(laidOutTarget);"));
        assertTrue("a later down press must invalidate a queued focus request",
                focus.contains("int generation = ++mSearchFocusGeneration;")
                        && focus.contains("if (generation != mSearchFocusGeneration"));
    }

    @Test
    public void focusedResultCardScrollsMinimallyIntoView() throws Exception {
        String source = read("app/src/leanback/java/com/fongmi/android/tv/ui/activity/CollectActivity.java");
        int start = source.indexOf("private void alignSearchResultCard(View focusedView)");
        int end = source.indexOf("private void preloadNextRows(int count)");

        assertTrue("search results must define focused-card alignment", start >= 0 && end > start);
        String align = source.substring(start, end);
        assertTrue("alignment must convert the focused card into the RecyclerView coordinate space",
                align.contains("focusedView.getDrawingRect(rect);")
                        && align.contains("mBinding.recycler.offsetDescendantRectToMyCoords(focusedView, rect);"));
        assertTrue("alignment must scroll a bottom-clipped card fully into view",
                align.contains("if (rect.bottom > bottom) targetScrollY = rect.bottom - bottom;"));
        assertTrue("alignment must scroll a top-clipped card fully into view",
                align.contains("else if (rect.top < top) targetScrollY = rect.top - top;"));
        assertTrue("alignment must use smooth scrolling to avoid focus/scroll jank",
                align.contains("mBinding.recycler.smoothScrollBy(0, targetScrollY);"));
    }

    private static String read(String path) throws Exception {
        Path direct = Path.of(path);
        if (Files.exists(direct)) return Files.readString(direct, StandardCharsets.UTF_8);
        return Files.readString(Path.of("..").resolve(path), StandardCharsets.UTF_8);
    }
}
