package com.fongmi.android.tv.ui.helper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EpisodeCardImagePolicyTest {

    @Test
    public void wideDevicePrefersBackdropAndFallsBackToPoster() {
        assertEquals("backdrop", EpisodeCardImagePolicy.fallbackFor("backdrop", "poster", true));
        assertEquals("poster", EpisodeCardImagePolicy.fallbackFor("", "poster", true));
    }

    @Test
    public void narrowDevicePrefersPosterAndFallsBackToBackdrop() {
        assertEquals("poster", EpisodeCardImagePolicy.fallbackFor("backdrop", "poster", false));
        assertEquals("backdrop", EpisodeCardImagePolicy.fallbackFor("backdrop", "", false));
    }

    @Test
    public void missingBothRatiosYieldsEmpty() {
        assertEquals("", EpisodeCardImagePolicy.fallbackFor("", "", true));
        assertEquals("", EpisodeCardImagePolicy.fallbackFor("", "", false));
    }
}
