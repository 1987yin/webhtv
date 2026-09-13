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
        assertTrue("a later down press must invalidate a queued focus request",
                focus.contains("int generation = ++mSearchFocusGeneration;")
                        && focus.contains("if (generation != mSearchFocusGeneration"));
    }

    private static String read(String path) throws Exception {
        Path direct = Path.of(path);
        if (Files.exists(direct)) return Files.readString(direct, StandardCharsets.UTF_8);
        return Files.readString(Path.of("..").resolve(path), StandardCharsets.UTF_8);
    }
}
