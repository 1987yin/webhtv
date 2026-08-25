package com.fongmi.android.tv.player.exo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExoBufferingStallWatchdogTest {

    private static final long TIMEOUT = ExoBufferingStallWatchdog.STALL_TIMEOUT_MS;
    private static final long LOADING_TIMEOUT = ExoBufferingStallWatchdog.LOADING_STALL_TIMEOUT_MS;

    @Test
    public void staysQuietUntilArmed() {
        ExoBufferingStallWatchdog watchdog = new ExoBufferingStallWatchdog();
        assertFalse(watchdog.isArmed());
        assertFalse(watchdog.shouldTimeout(LOADING_TIMEOUT * 10, 0, 0, false));
    }

    @Test
    public void timesOutOnlyWhenNeitherPositionNorBufferAdvances() {
        ExoBufferingStallWatchdog watchdog = new ExoBufferingStallWatchdog();
        watchdog.arm(0, 5_000, 9_000);
        assertFalse(watchdog.shouldTimeout(TIMEOUT - 1, 5_000, 9_000, false));
        assertTrue(watchdog.shouldTimeout(TIMEOUT, 5_000, 9_000, false));
    }

    @Test
    public void growingBufferIsProgressEvenWhilePositionStandsStill() {
        ExoBufferingStallWatchdog watchdog = new ExoBufferingStallWatchdog();
        watchdog.arm(0, 5_000, 9_000);
        // A normal rebuffer: position frozen, buffered end still climbing.
        watchdog.observe(TIMEOUT - 1, 5_000, 12_000);
        assertFalse(watchdog.shouldTimeout(TIMEOUT, 5_000, 12_000, false));
        assertTrue(watchdog.shouldTimeout(TIMEOUT * 2 - 1, 5_000, 12_000, false));
    }

    @Test
    public void advancingPositionIsProgressEvenWhileBufferStandsStill() {
        ExoBufferingStallWatchdog watchdog = new ExoBufferingStallWatchdog();
        watchdog.arm(0, 5_000, 9_000);
        watchdog.observe(TIMEOUT - 1, 7_000, 9_000);
        assertFalse(watchdog.shouldTimeout(TIMEOUT, 7_000, 9_000, false));
    }

    @Test
    public void loadingSourceGetsTheLongerCeiling() {
        ExoBufferingStallWatchdog watchdog = new ExoBufferingStallWatchdog();
        watchdog.arm(0, 5_000, 9_000);
        // A remote Matroska Cues fetch produces no samples yet is not stalled.
        assertFalse(watchdog.shouldTimeout(TIMEOUT, 5_000, 9_000, true));
        assertFalse(watchdog.shouldTimeout(LOADING_TIMEOUT - 1, 5_000, 9_000, true));
        assertTrue(watchdog.shouldTimeout(LOADING_TIMEOUT, 5_000, 9_000, true));
    }

    @Test
    public void loadingCeilingStaysBounded() {
        // A hung socket read keeps loading true forever; it must still trip.
        assertTrue(LOADING_TIMEOUT > TIMEOUT);
        assertTrue(LOADING_TIMEOUT < Long.MAX_VALUE);
    }

    @Test
    public void resetDisarms() {
        ExoBufferingStallWatchdog watchdog = new ExoBufferingStallWatchdog();
        watchdog.arm(0, 5_000, 9_000);
        watchdog.reset();
        assertFalse(watchdog.isArmed());
        assertFalse(watchdog.shouldTimeout(LOADING_TIMEOUT * 10, 5_000, 9_000, false));
    }

    @Test
    public void observeArmsWhenNotYetArmed() {
        ExoBufferingStallWatchdog watchdog = new ExoBufferingStallWatchdog();
        watchdog.observe(1_000, 5_000, 9_000);
        assertTrue(watchdog.isArmed());
        assertFalse(watchdog.shouldTimeout(1_000 + TIMEOUT - 1, 5_000, 9_000, false));
        assertTrue(watchdog.shouldTimeout(1_000 + TIMEOUT, 5_000, 9_000, false));
    }

    @Test
    public void timeoutMustOutlastMaxRebufferThreshold() {
        // A LoadControl still filling its rebuffer threshold must never be killed.
        assertTrue(ExoBufferingStallWatchdog.STALL_TIMEOUT_MS
                > ExoPlaybackThresholdPolicy.MAX_STREAMING_REBUFFER_MS);
    }

    @Test
    public void staleProgressDoesNotRearmTheClock() {
        ExoBufferingStallWatchdog watchdog = new ExoBufferingStallWatchdog();
        watchdog.arm(0, 5_000, 9_000);
        // A regressed sample (e.g. after a flush) must not count as progress.
        watchdog.observe(TIMEOUT / 2, 4_000, 8_000);
        assertTrue(watchdog.shouldTimeout(TIMEOUT, 4_000, 8_000, false));
    }
}
