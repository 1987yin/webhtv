package com.fongmi.android.tv.player;

import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.bean.Result;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TransientPlaybackCoordinatorTest {

    @Test
    public void launchPreservesPlayingAndPausedSnapshots() {
        TransientPlaybackCoordinator coordinator = new TransientPlaybackCoordinator();
        TransientPlaybackSnapshot playing = snapshot("playing", "https://video/playing.m3u8", 1_000L, true);

        assertTrue(coordinator.beginLaunch(playing));
        coordinator.queueRestoreAfterResult();
        TransientPlaybackSnapshot restoredPlaying = coordinator.beginRestore();
        assertSame(playing, restoredPlaying);
        assertTrue(restoredPlaying.shouldResume());
        coordinator.failRestore();

        TransientPlaybackSnapshot paused = snapshot("paused", "https://video/paused.m3u8", 2_000L, false);
        assertTrue(coordinator.beginLaunch(paused));
        coordinator.queueRestoreAfterResult();
        TransientPlaybackSnapshot restoredPaused = coordinator.beginRestore();
        assertSame(paused, restoredPaused);
        assertFalse(restoredPaused.shouldResume());
    }

    @Test
    public void duplicateLaunchIsRejectedAndCancelReturnsOriginalSnapshot() {
        TransientPlaybackCoordinator coordinator = new TransientPlaybackCoordinator();
        TransientPlaybackSnapshot snapshot = snapshot("key", "https://video/item.m3u8", 3_000L, true);

        assertTrue(coordinator.beginLaunch(snapshot));
        assertFalse(coordinator.beginLaunch(snapshot("other", "https://video/other.m3u8", 0L, false)));
        assertSame(snapshot, coordinator.cancelLaunch());
        assertFalse(coordinator.isLaunchActive());
        assertTrue(coordinator.beginLaunch(null));
    }

    @Test
    public void existingSessionRequiresRestorableSnapshotButEmptyPlayerDoesNot() {
        TransientPlaybackCoordinator coordinator = new TransientPlaybackCoordinator();

        assertFalse(coordinator.beginLaunch(null, true));
        assertFalse(coordinator.isLaunchActive());
        assertTrue(coordinator.beginLaunch(null, false));
        assertTrue(coordinator.isLaunchActive());
    }

    @Test
    public void disconnectRequeuesInFlightRestoreForReconnect() {
        TransientPlaybackCoordinator coordinator = new TransientPlaybackCoordinator();
        TransientPlaybackSnapshot snapshot = snapshot("key", "https://video/item.m3u8", 4_000L, true);

        coordinator.beginLaunch(snapshot);
        coordinator.queueRestoreAfterResult();
        assertTrue(coordinator.hasQueuedRestore());
        assertSame(snapshot, coordinator.beginRestore());
        assertFalse(coordinator.hasQueuedRestore());

        coordinator.requeueInFlightRestore();
        assertTrue(coordinator.hasQueuedRestore());
        assertFalse(coordinator.hasInFlightRestore());
        assertSame(snapshot, coordinator.beginRestore());
    }

    @Test
    public void prepareRequiresMatchingKeyAndUrlAndCompletesOnlyOnce() {
        TransientPlaybackCoordinator coordinator = new TransientPlaybackCoordinator();
        TransientPlaybackSnapshot snapshot = snapshot("key", "https://video/item.m3u8", 5_000L, false);
        coordinator.beginLaunch(snapshot);
        coordinator.queueRestoreAfterResult();
        coordinator.beginRestore();

        assertFalse(coordinator.consumePreparedPosition("wrong", "https://video/item.m3u8").isPresent());
        assertTrue(coordinator.hasInFlightRestore());
        assertFalse(coordinator.consumePreparedPosition("key", "https://video/wrong.m3u8").isPresent());
        assertTrue(coordinator.hasInFlightRestore());

        OptionalLong position = coordinator.consumePreparedPosition("key", "https://video/item.m3u8");
        assertTrue(position.isPresent());
        assertEquals(5_000L, position.getAsLong());
        assertFalse(coordinator.hasInFlightRestore());
        assertFalse(coordinator.consumePreparedPosition("key", "https://video/item.m3u8").isPresent());
    }

    @Test
    public void restoreFailureAndClearDiscardAllPendingState() {
        TransientPlaybackCoordinator coordinator = new TransientPlaybackCoordinator();
        coordinator.beginLaunch(snapshot("key", "https://video/item.m3u8", 6_000L, true));
        coordinator.queueRestoreAfterResult();
        coordinator.beginRestore();
        coordinator.failRestore();

        assertFalse(coordinator.hasQueuedRestore());
        assertFalse(coordinator.hasInFlightRestore());

        TransientPlaybackSnapshot next = snapshot("next", "https://video/next.m3u8", 7_000L, false);
        coordinator.beginLaunch(next);
        coordinator.clear();
        assertFalse(coordinator.isLaunchActive());

        coordinator.beginLaunch(next);
        coordinator.queueRestoreAfterResult();
        coordinator.clear();
        assertFalse(coordinator.hasQueuedRestore());

        coordinator.beginLaunch(next);
        coordinator.queueRestoreAfterResult();
        coordinator.beginRestore();
        coordinator.clear();
        assertFalse(coordinator.hasInFlightRestore());
    }

    private static TransientPlaybackSnapshot snapshot(String key, String url, long positionMs, boolean shouldResume) {
        Result result = Result.playbackSnapshot(Result.empty(), url, Map.of(), "", null, List.of());
        return TransientPlaybackSnapshot.create(key, result, MediaMetadata.EMPTY, positionMs, shouldResume);
    }
}
