package com.fongmi.android.tv.player;

import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.bean.Result;

/** Immutable playback state retained temporarily while navigating away from a player. */
public record TransientPlaybackSnapshot(
        String key,
        Result result,
        MediaMetadata metadata,
        long positionMs,
        boolean shouldResume) {

    public static TransientPlaybackSnapshot create(String key, Result result, MediaMetadata metadata,
                                                    long positionMs, boolean shouldResume) {
        return new TransientPlaybackSnapshot(
                key == null ? "" : key,
                result == null ? Result.empty() : result,
                metadata == null ? MediaMetadata.EMPTY : metadata,
                Math.max(0L, positionMs),
                shouldResume);
    }

    public boolean isRestorable() {
        return !key.isEmpty() && !result.getRealUrl().isEmpty();
    }
}
