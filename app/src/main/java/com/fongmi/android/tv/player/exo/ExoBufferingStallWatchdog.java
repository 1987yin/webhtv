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

    /**
     * A regression smaller than this counts as jitter and is ignored; anything larger is
     * treated as a seek or flush and re-arms the baseline. Well below the smallest useful
     * seek step so a real jump is never mistaken for jitter.
     */
    static final long DISCONTINUITY_TOLERANCE_MS = 1_000L;

    /**
     * Absolute ceiling for one buffering episode. Re-arming on a discontinuity resets the
     * progress clock, so a source that regresses by more than the tolerance on a repeating
     * cycle could otherwise defer the timeout forever and resurrect the stall this class
     * exists to catch. The episode start is preserved across those re-arms, which makes
     * termination provable regardless of the sample pattern.
     */
    public static final long EPISODE_CEILING_MS = 90_000L;

    private boolean armed;
    private long episodeStartedAtMs;
    private long lastProgressAtMs;
    private long lastPositionMs;
    private long lastBufferedPositionMs;

    /**
     * Starts a fresh episode. Use this at the real arming points (entering BUFFERING, a seek,
     * a first frame before READY) and for every tick while paused, so paused time never
     * accumulates toward {@link #EPISODE_CEILING_MS}.
     */
    public void arm(long nowMs, long positionMs, long bufferedPositionMs) {
        armed = true;
        episodeStartedAtMs = nowMs;
        rebaseline(nowMs, positionMs, bufferedPositionMs);
    }

    private void rebaseline(long nowMs, long positionMs, long bufferedPositionMs) {
        lastProgressAtMs = nowMs;
        lastPositionMs = positionMs;
        lastBufferedPositionMs = bufferedPositionMs;
    }

    public void observe(long nowMs, long positionMs, long bufferedPositionMs) {
        if (!armed) {
            arm(nowMs, positionMs, bufferedPositionMs);
            return;
        }
        // A large regression is a discontinuity, not a stall: a backward seek or a flush moves
        // the position and the buffered end below the recorded baseline, and keeping the old
        // baseline would make every later sample compare as "no progress" and time out a
        // session that merely jumped. Re-arm on the new, lower baseline instead.
        //
        // Small regressions must NOT re-arm. The buffered end can jitter down a little while
        // buffering, and re-arming on jitter would reset the clock on every dip, so an
        // oscillating-but-stalled session would never time out at all.
        // Rebaseline only. The episode start is deliberately preserved so a repeating
        // regression cycle cannot defer the timeout indefinitely.
        if (positionMs < lastPositionMs - DISCONTINUITY_TOLERANCE_MS
                || bufferedPositionMs < lastBufferedPositionMs - DISCONTINUITY_TOLERANCE_MS) {
            rebaseline(nowMs, positionMs, bufferedPositionMs);
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
        episodeStartedAtMs = 0;
        lastProgressAtMs = 0;
        lastPositionMs = 0;
        lastBufferedPositionMs = 0;
    }

    public boolean isArmed() {
        return armed;
    }

    public boolean shouldTimeout(
            long nowMs, long positionMs, long bufferedPositionMs, boolean loading) {
        if (!armed) return false;
        if (nowMs - episodeStartedAtMs >= EPISODE_CEILING_MS) return true;
        return positionMs <= lastPositionMs
                && bufferedPositionMs <= lastBufferedPositionMs
                && nowMs - lastProgressAtMs >= (loading ? LOADING_STALL_TIMEOUT_MS : STALL_TIMEOUT_MS);
    }
}
