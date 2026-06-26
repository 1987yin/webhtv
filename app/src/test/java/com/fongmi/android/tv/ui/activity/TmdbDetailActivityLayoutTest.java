package com.fongmi.android.tv.ui.activity;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class TmdbDetailActivityLayoutTest {

    @Test
    public void fusionDetailBackdropDrawsBehindSystemBars() throws Exception {
        Path sourcePath = findMainJavaPath().resolve(Path.of("com", "fongmi", "android", "tv", "ui", "activity", "TmdbDetailActivity.java"));
        String source = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);
        int method = source.indexOf("private void applyDetailEdgeToEdge()");
        int init = source.indexOf("protected void initView(Bundle savedInstanceState)");
        int theme = source.indexOf("private void applyDetailTheme()");

        assertTrue(sourcePath + " is missing applyDetailEdgeToEdge", method >= 0);
        assertTrue("TMDB detail must draw the backdrop behind the system bars",
                source.indexOf("WindowCompat.setDecorFitsSystemWindows(window, false)", method) > method);
        assertTrue("TMDB detail status bar must stay transparent over the backdrop",
                source.indexOf("window.setStatusBarColor(Color.TRANSPARENT)", method) > method);
        assertTrue("TMDB detail navigation bar must stay transparent over the backdrop",
                source.indexOf("window.setNavigationBarColor(Color.TRANSPARENT)", method) > method);
        assertTrue("TMDB detail must keep system bar icon contrast in sync with the detail theme",
                source.indexOf("setAppearanceLightStatusBars", method) > method);
        assertTrue("TMDB detail must configure edge-to-edge during initialization",
                source.indexOf("applyDetailEdgeToEdge();", init) > init);
        assertTrue("TMDB detail must re-apply edge-to-edge after theme changes",
                source.indexOf("applyDetailEdgeToEdge();", theme) > theme);
    }

    @Test
    public void fusionDetailBackdropCropsToFillScreen() throws Exception {
        Path sourcePath = findMainJavaPath().resolve(Path.of("com", "fongmi", "android", "tv", "ui", "activity", "TmdbDetailActivity.java"));
        String source = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);
        int method = source.indexOf("private boolean shouldCropBackdrop()");
        assertTrue(sourcePath + " is missing shouldCropBackdrop", method >= 0);

        int methodEnd = source.indexOf("\n    }", method);
        String body = source.substring(method, methodEnd);
        assertTrue("Fusion detail must center-crop artwork so portrait screens do not show top/bottom background bars",
                body.contains("return true;"));
    }

    @Test
    public void detailLoadsPersonalAiCacheBeforeSlowMediaBlocksFinish() throws Exception {
        Path sourcePath = findMainJavaPath().resolve(Path.of("com", "fongmi", "android", "tv", "ui", "activity", "TmdbDetailActivity.java"));
        String source = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);
        int method = source.indexOf("private void loadTmdbMediaBlocks(TmdbBundle bundle)");
        int bind = source.indexOf("bindTmdbSection();", method);
        int earlyCache = source.indexOf("loadTmdbPersonalAiCache(bundle, currentVod, generation);", method);
        int task = source.indexOf("Task.execute(() ->", method);
        int merge = source.indexOf("relatedItems.clear();", method);
        int fullAi = source.indexOf("loadTmdbPersonalAi(bundle, currentVod", method);

        assertTrue(sourcePath + " is missing loadTmdbMediaBlocks", method >= 0);
        assertTrue("TMDB detail must bind the loading section before early AI cache lookup", bind > method);
        assertTrue("TMDB detail must read AI cache before slow media block loading starts", earlyCache > bind && earlyCache < task);
        assertTrue("TMDB detail must keep the early AI row while merging slow media blocks",
                merge > method && fullAi > merge && !source.substring(merge, fullAi).contains("personalAiItems.clear();"));
    }

    @Test
    public void fusionDetailShowsFocusedPersonalAiReason() throws Exception {
        Path activityPath = findMainJavaPath().resolve(Path.of("com", "fongmi", "android", "tv", "ui", "activity", "TmdbDetailActivity.java"));
        String activity = new String(Files.readAllBytes(activityPath), StandardCharsets.UTF_8);
        Path adapterPath = findMainJavaPath().resolve(Path.of("com", "fongmi", "android", "tv", "ui", "adapter", "TmdbRailAdapter.java"));
        String adapter = new String(Files.readAllBytes(adapterPath), StandardCharsets.UTF_8);
        Path layoutPath = findMainResPath().resolve(Path.of("layout", "activity_tmdb_detail.xml"));
        String layout = new String(Files.readAllBytes(layoutPath), StandardCharsets.UTF_8);

        int aiList = layout.indexOf("android:id=\"@+id/personalAiList\"");
        int aiReason = layout.indexOf("android:id=\"@+id/personalAiReason\"");
        int tmdbStatus = layout.indexOf("android:id=\"@+id/tmdbStatus\"");
        assertTrue("TMDB detail must keep the AI reason directly below the smart recommendation row",
                aiList >= 0 && aiReason > aiList && tmdbStatus > aiReason);
        assertTrue("TMDB detail must listen for smart recommendation card focus",
                activity.contains("personalAiAdapter.setOnItemFocusListener(this::showAiRecommendationReason);"));
        assertTrue("TMDB detail must render the focused card overview as the recommendation reason",
                activity.contains("binding.personalAiReason.setText(getString(R.string.ai_recommendation_reason_preview, reason));"));
        assertTrue("TMDB detail must hide stale recommendation reasons when the smart row is absent",
                activity.contains("showAiRecommendationReason(null, false);"));
        assertTrue("TMDB detail must scroll the reason into view when the focused card sits near the bottom of the wide layout",
                activity.contains("scrollAiRecommendationReasonIntoView();")
                        && activity.contains("offsetDescendantRectToMyCoords(binding.personalAiReason, rect)")
                        && activity.contains("binding.scroll.smoothScrollBy(0, bottomGap);"));
        assertTrue("TMDB rail cards must report focus changes to the detail screen",
                adapter.contains("public interface FocusListener")
                        && adapter.contains("public void setOnItemFocusListener(FocusListener listener)")
                        && adapter.contains("focusListener.onItemFocus(item, focused)")
                        && adapter.contains("holder.root.hasFocus() && focusListener != null"));
    }

    @Test
    public void keepStateShowsAddedLabelWhenAlreadyKept() throws Exception {
        Path sourcePath = findMainJavaPath().resolve(Path.of("com", "fongmi", "android", "tv", "ui", "activity", "TmdbDetailActivity.java"));
        String source = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);
        int method = source.indexOf("private void updateKeepState()");
        assertTrue(sourcePath + " is missing updateKeepState", method >= 0);

        int methodEnd = source.indexOf("\n    }", method);
        String body = source.substring(method, methodEnd);
        assertTrue("TMDB detail must show the current favorite state, not the removal result label",
                body.contains("TmdbDetailLabels.keepLabel(kept)") && !body.contains("R.string.keep_del"));
        assertTrue("TMDB detail must keep all favorite buttons visually selected together",
                body.contains("binding.keep.setSelected(kept)")
                        && body.contains("binding.keepTop.setSelected(kept)")
                        && body.contains("binding.keepFusion.setSelected(kept)"));
    }

    private static Path findMainJavaPath() {
        Path moduleRelative = Path.of("src", "main", "java");
        if (Files.exists(moduleRelative)) return moduleRelative;
        return Path.of("app", "src", "main", "java");
    }

    private static Path findMainResPath() {
        Path moduleRelative = Path.of("src", "main", "res");
        if (Files.exists(moduleRelative)) return moduleRelative;
        return Path.of("app", "src", "main", "res");
    }
}
