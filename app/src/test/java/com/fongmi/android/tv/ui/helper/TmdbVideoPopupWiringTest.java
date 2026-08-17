package com.fongmi.android.tv.ui.helper;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TmdbVideoPopupWiringTest {

    @Test
    public void playbackActivityCoordinatesTransientPlaybackAndRestore() throws Exception {
        String playback = read("src", "main", "java", "com", "fongmi", "android", "tv", "ui", "activity", "PlaybackActivity.java");

        assertTrue(playback.contains("public static final String EXTRA_TRANSIENT_PLAYBACK"));
        assertTrue(playback.contains("public final boolean launchTransientPlayback(Intent intent)"));
        assertTrue(playback.contains("player().getCurrentResult()"));
        assertTrue(playback.contains("startActivityForResult(intent, REQUEST_TRANSIENT_PLAYBACK)"));
        assertTrue(playback.contains("restoreTransientPlayback()"));
        assertTrue(playback.contains("private static final int REQUEST_TRANSIENT_PLAYBACK = 1098"));
        assertTrue(playback.contains("TransientPlaybackSnapshot.create("));
        assertTrue(playback.contains("snapshot.shouldResume()"));
        assertTrue(playback.contains("private final TransientPlaybackCoordinator transientPlayback = new TransientPlaybackCoordinator();"));
        assertFalse(playback.contains("private TransientPlaybackSnapshot transientSnapshot"));
        assertFalse(playback.contains("private TransientPlaybackSnapshot pendingTransientRestore"));
        assertFalse(playback.contains("private long pendingTransientSeekMs"));
        assertOrderAfter(playback, "public void onPrepare()", "PlaybackActivity.this.onPrepare();", "transientPlayback.consumePreparedPosition(", "manager.seekTo(positionMs)");
        assertFalse(playback.contains("!manager.isEmpty()"));
        assertTrue(playback.contains("boolean hasPlaybackSession = manager != null && !manager.isReleased() && manager.hasSession();"));
        assertOrderAfter(playback, "public final boolean launchTransientPlayback(Intent intent)", "transientPlayback.canBeginLaunch()", "transientPlayback.beginLaunch(transientSnapshot, hasPlaybackSession)", "startActivityForResult(intent, REQUEST_TRANSIENT_PLAYBACK)");
        assertTrue(playback.contains("TransientPlaybackSnapshot snapshot = transientPlayback.cancelLaunch();"));
        assertTrue(playback.contains("transientPlayback.queueRestoreAfterResult();"));
        assertOrderAfter(playback, "private void restoreTransientPlayback()", "transientPlayback.beginRestore()", "manager.stop();", "startPlayerInternal(", "catch (RuntimeException e)", "transientPlayback.failRestore();", "PlaybackActivity.this.onError(ResUtil.getString(R.string.error_play_url))");
        assertTrue(playback.contains("private boolean startPlayerInternal("));
        assertOrderAfter(playback, "public void onError(String msg)", "transientPlayback.failRestore();", "PlaybackActivity.this.onError(msg);");
        assertOrderAfter(playback, "public void onServiceDisconnected(ComponentName name)", "transientPlayback.requeueInFlightRestore();", "mService = null;");
        assertTrue(playback.contains("if (transientPlayback.hasQueuedRestore()) restoreTransientPlayback();"));
        assertOrderAfter(playback, "protected void onDestroy()", "transientPlayback.clear();", "super.onDestroy();");
        assertTrue(playback.contains("resumeAfterTransientPlayback(snapshot != null && snapshot.shouldResume())"));
    }

    @Test
    public void standardVideoActivitiesSupportIsolatedSingleItemTransientPlayback() throws Exception {
        String siteApi = read("src", "main", "java", "com", "fongmi", "android", "tv", "api", "SiteApi.java");
        String viewModel = read("src", "main", "java", "com", "fongmi", "android", "tv", "model", "SiteViewModel.java");
        String mobile = read("src", "mobile", "java", "com", "fongmi", "android", "tv", "ui", "activity", "VideoActivity.java");
        String leanback = read("src", "leanback", "java", "com", "fongmi", "android", "tv", "ui", "activity", "VideoActivity.java");

        assertTrue(siteApi.contains("playerContentIsolated(@NonNull String key, @NonNull String flag, @NonNull String id, int playerType)"));
        assertTrue(siteApi.contains("return playerContent(key, flag, id, playerType, new Source(), false);"));
        assertTrue(viewModel.contains("playerContent(String key, String flag, String id, boolean isolated)"));
        assertTrue(viewModel.contains("isolated ? SiteApi.playerContentIsolated(key, flag, id) : SiteApi.playerContent(key, flag, id)"));

        assertTransientVideoActivityWiring(mobile, true);
        assertTransientVideoActivityWiring(leanback, false);
    }

    @Test
    public void relatedVideoUsesIsolatedPopupAndRestoresHostPlayback() throws Exception {
        String helper = read("src", "main", "java", "com", "fongmi", "android", "tv", "ui", "helper", "TmdbVideoPlayback.java");
        String dialog = read("src", "main", "java", "com", "fongmi", "android", "tv", "ui", "dialog", "TmdbVideoPlayerDialog.java");
        String playback = read("src", "main", "java", "com", "fongmi", "android", "tv", "ui", "activity", "PlaybackActivity.java");
        String siteApi = read("src", "main", "java", "com", "fongmi", "android", "tv", "api", "SiteApi.java");

        assertTrue(helper.contains("TmdbVideoPlayerDialog.show((FragmentActivity) activity, launch)"));
        assertFalse(helper.contains("VideoActivity.startDirect"));
        assertTrue(dialog.contains("pauseForTransientPlayback()"));
        assertTrue(dialog.contains("resumeAfterTransientPlayback"));
        assertTrue(dialog.contains("SiteApi.playerContentIsolated(SiteApi.PUSH"));
        assertFalse(dialog.contains("SiteApi.playerContent(SiteApi.PUSH"));
        assertTrue(siteApi.contains("public static Result playerContentIsolated"));
        assertTrue(siteApi.contains("return playerContent(key, flag, id, playerType, new Source(), false);"));
        assertTrue(dialog.contains("new ExoPlayer.Builder"));
        assertTrue(playback.contains("public final boolean pauseForTransientPlayback()"));
        assertTrue(playback.contains("public final void resumeAfterTransientPlayback(boolean shouldResume)"));
    }

    @Test
    public void relatedVideoRowsAppearImmediatelyAfterPhotosAndBeforeRecommendations() throws Exception {
        String header = read("src", "main", "res", "layout", "view_tmdb_header.xml");
        assertOrder(header, "android:id=\"@+id/tmdbPhotos\"", "android:id=\"@+id/tmdbRelatedVideosLabel\"", "android:id=\"@+id/tmdbRelatedVideos\"", "android:id=\"@+id/tmdbRecommendationsLabel\"");

        String detail = read("src", "main", "res", "layout", "activity_tmdb_detail.xml");
        assertOrder(detail, "android:id=\"@+id/episodePhotoList\"", "android:id=\"@+id/relatedVideoTitle\"", "android:id=\"@+id/relatedVideoList\"", "android:id=\"@+id/castTitle\"", "android:id=\"@+id/relatedTitle\"");

        String leanback = read("src", "leanback", "res", "layout", "activity_video.xml");
        assertOrder(leanback, "android:id=\"@+id/tmdbPhotos\"", "android:id=\"@+id/tmdbRelatedVideosLabel\"", "android:id=\"@+id/tmdbRelatedVideos\"", "android:id=\"@+id/tmdbCrewLabel\"", "android:id=\"@+id/tmdbRecommendationsLabel\"");

        String leanbackActivity = read("src", "leanback", "java", "com", "fongmi", "android", "tv", "ui", "activity", "VideoActivity.java");
        assertOrderAfter(leanbackActivity, "private void bindTmdbData()", "java.util.List<String> photos", "java.util.List<TmdbVideo> relatedVideos", "java.util.List<com.fongmi.android.tv.bean.TmdbPerson> creators", "java.util.List<com.fongmi.android.tv.bean.TmdbItem> recommendations");
    }

    @Test
    public void chineseResourcesNameTheRelatedVideoSection() throws Exception {
        String simplified = read("src", "main", "res", "values-zh-rCN", "strings.xml");
        String traditional = read("src", "main", "res", "values-zh-rTW", "strings.xml");
        assertTrue(simplified.contains("<string name=\"tmdb_related_videos_label\">相关视频</string>"));
        assertTrue(traditional.contains("<string name=\"tmdb_related_videos_label\">相關視頻</string>"));
    }

    private static void assertOrder(String source, String... values) {
        int previous = -1;
        for (String value : values) {
            int current = source.indexOf(value);
            assertTrue("Missing or out-of-order value: " + value, current > previous);
            previous = current;
        }
    }

    private static void assertOrderAfter(String source, String anchor, String... values) {
        int previous = source.indexOf(anchor);
        assertTrue("Missing anchor: " + anchor, previous >= 0);
        for (String value : values) {
            int current = source.indexOf(value, previous + 1);
            assertTrue("Missing or out-of-order value after " + anchor + ": " + value, current > previous);
            previous = current;
        }
    }

    private static void assertTransientVideoActivityWiring(String source, boolean mobile) {
        assertTrue(source.contains("public static Intent createTransientIntent(Activity activity, TmdbVideoPlayback.Launch launch)"));
        assertTrue(source.contains("intent.putExtra(PlaybackActivity.EXTRA_TRANSIENT_PLAYBACK, true)"));
        assertTrue(source.contains("putIntentPlaybackSelection(intent, launch.getPlayFlag(), launch.getPlayEpisodeName(), launch.getPlayEpisodeUrl())"));
        assertTrue(source.contains("private boolean isTransientPlayback()"));
        assertTrue(source.contains("getIntent().getBooleanExtra(PlaybackActivity.EXTRA_TRANSIENT_PLAYBACK, false)"));
        assertTrue(source.contains("if (isTransientPlayback()) mViewModel.playerContent(getKey(), playFlag, episode.getUrl(), true)"));
        assertTrue(source.contains("else mViewModel.playerContent(getKey(), playFlag, episode.getUrl())"));
        assertTrue(source.contains("if (isTransientPlayback() && !isFullscreen()) enterFullscreen();"));
        assertTrue(source.contains("private void applyTransientPlaybackControls()"));
        assertTrue(source.contains("if (isTransientPlayback()) return;"));
        assertTrue(source.contains("private void finishTransientPlayback()"));
        assertTrue(source.contains("setResult(RESULT_OK)"));
        assertTrue(source.contains("super.onActivityResult(requestCode, resultCode, data)"));
        assertTrue(source.contains("case Player.STATE_ENDED:"));
        assertTrue(source.contains("checkEnded(true)"));
        assertTrue(source.contains("mBinding.control.action.next.setVisibility(View.GONE)"));
        assertTrue(source.contains("mBinding.control.action.prev.setVisibility(View.GONE)"));
        assertTrue(source.contains("mBinding.control.action.episodes.setVisibility(View.GONE)"));
        if (mobile) {
            assertTrue(source.contains("SiteApi.playerContentIsolated(key, flag, episode)"));
            assertTrue(source.contains("SiteApi.playerContentIsolated(key, flag, episode, nextType)"));
            assertTrue(source.contains("mBinding.control.next.setVisibility(View.GONE)"));
            assertTrue(source.contains("mBinding.control.prev.setVisibility(View.GONE)"));
        }
    }

    private static String read(String... parts) throws Exception {
        Path root = Files.exists(Path.of("src", "main")) ? Path.of("") : Path.of("app");
        return Files.readString(root.resolve(Path.of("", parts)), StandardCharsets.UTF_8);
    }
}
