package com.fongmi.android.tv.ui.helper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
