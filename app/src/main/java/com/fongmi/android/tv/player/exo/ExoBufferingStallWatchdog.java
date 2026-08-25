package com.fongmi.android.tv.player.exo;

/**
 * Detects a player stuck in BUFFERING that is no longer making any progress.
 *
 * <p>Position alone is not evidence: it legitimately stands still throughout a
 * normal rebuffer. Only when neither the playback position nor the buffered end
 * advances is the session actually stalled.
 *
 * <p>The timeout must stay above {@code MAX_STREAMING_REBUFFER_MS} (15 s) so a
 * LoadControl that is still filling its rebuffer threshold is never killed.
 */
public final class ExoBufferingStallWatchdog {

    public static final long STALL_TIMEOUT_MS = 20_000L;

    /**
     * While the source still reports loading, neither the position nor the buffered
     * end has to move: a remote Matroska seek fetches the file-tail Cues before it
     * can produce a single sample (see E-SP2). Killing that would trade a working
     * fetch for a needless fallback, so a loading source gets this longer ceiling
     * instead. It still has to be bounded, or a hung socket read would never trip.
     */
    public static final long LOADING_STALL_TIMEOUT_MS = 60_000L;

    private boolean armed;
    private long lastProgressAtMs;
    private long lastPositionMs;
    private long lastBufferedPositionMs;

    public void arm(long nowMs, long positionMs, long bufferedPositionMs) {
        armed = true;
        lastProgressAtMs = nowMs;
        lastPositionMs = positionMs;
        lastBufferedPositionMs = bufferedPositionMs;
    }

    public void observe(long nowMs, long positionMs, long bufferedPositionMs) {
        if (!armed) {
            arm(nowMs, positionMs, bufferedPositionMs);
            return;
        }
        if (positionMs > lastPositionMs || bufferedPositionMs > lastBufferedPositionMs) {
            lastPositionMs = Math.max(lastPositionMs, positionMs);
            lastBufferedPositionMs = Math.max(lastBufferedPositionMs, bufferedPositionMs);
            lastProgressAtMs = nowMs;
        }
    }

    public void reset() {
        armed = false;
        lastProgressAtMs = 0;
        lastPositionMs = 0;
        lastBufferedPositionMs = 0;
    }

    public boolean isArmed() {
        return armed;
    }

    public boolean shouldTimeout(
            long nowMs, long positionMs, long bufferedPositionMs, boolean loading) {
        return armed
                && positionMs <= lastPositionMs
                && bufferedPositionMs <= lastBufferedPositionMs
                && nowMs - lastProgressAtMs >= (loading ? LOADING_STALL_TIMEOUT_MS : STALL_TIMEOUT_MS);
    }
}
