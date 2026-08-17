package com.fongmi.android.tv.player;

import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.bean.Result;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TransientPlaybackSnapshotTest {

    @Test
    public void validSnapshotPreservesResumeFields() {
        Result result = Result.playbackSnapshot(Result.empty(), "https://example.com/video.m3u8", Map.of(), "", null, List.of());
        MediaMetadata metadata = new MediaMetadata.Builder().setTitle("Episode 3").build();
        TransientPlaybackSnapshot snapshot = TransientPlaybackSnapshot.create("site|vod|episode", result, metadata, 42_000L, true);

        assertTrue(snapshot.isRestorable());
        assertEquals("site|vod|episode", snapshot.key());
        assertSame(result, snapshot.result());
        assertEquals(42_000L, snapshot.positionMs());
        assertTrue(snapshot.shouldResume());
        assertEquals("Episode 3", snapshot.metadata().title);
    }

    @Test
    public void emptyPlaybackResultCannotBeRestored() {
        TransientPlaybackSnapshot snapshot = TransientPlaybackSnapshot.create("", Result.empty(), MediaMetadata.EMPTY, 0L, false);

        assertFalse(snapshot.isRestorable());
    }

    @Test
    public void createNormalizesNullAndNegativeValues() {
        TransientPlaybackSnapshot snapshot = TransientPlaybackSnapshot.create(null, null, null, -1L, false);

        assertEquals("", snapshot.key());
        assertTrue(snapshot.result() != null);
        assertTrue(snapshot.result().getRealUrl().isEmpty());
        assertSame(MediaMetadata.EMPTY, snapshot.metadata());
        assertEquals(0L, snapshot.positionMs());
        assertFalse(snapshot.shouldResume());
    }

    @Test
    public void directConstructorAlsoNormalizesNullAndNegativeValues() {
        TransientPlaybackSnapshot snapshot = new TransientPlaybackSnapshot(null, null, null, -1L, false);

        assertEquals("", snapshot.key());
        assertTrue(snapshot.result() != null);
        assertSame(MediaMetadata.EMPTY, snapshot.metadata());
        assertEquals(0L, snapshot.positionMs());
        assertFalse(snapshot.isRestorable());
    }

    @Test
    public void emptyKeyCannotBeRestoredWhenUrlIsValid() {
        Result result = Result.playbackSnapshot(Result.empty(), "https://example.com/video.m3u8", Map.of(), "", null, List.of());

        assertFalse(TransientPlaybackSnapshot.create("", result, MediaMetadata.EMPTY, 0L, false).isRestorable());
    }

    @Test
    public void validKeyCannotBeRestoredWhenUrlIsEmpty() {
        assertFalse(TransientPlaybackSnapshot.create("site|vod|episode", Result.empty(), MediaMetadata.EMPTY, 0L, false).isRestorable());
    }
}
