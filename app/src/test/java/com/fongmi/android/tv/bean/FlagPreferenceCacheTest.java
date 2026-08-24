package com.fongmi.android.tv.bean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class FlagPreferenceCacheTest {

    private File directory;
    private File cacheFile;

    @Before
    public void setUp() throws Exception {
        directory = Files.createTempDirectory("flag-preference-cache").toFile();
        cacheFile = new File(directory, "flag_preferences.json");
    }

    @After
    public void tearDown() {
        if (cacheFile.exists()) cacheFile.delete();
        directory.delete();
    }

    @Test
    public void preferenceSurvivesProcessRestart() {
        FlagPreferenceCache cache = new FlagPreferenceCache(cacheFile);
        cache.put("site", "vod", "夸克原画#0101#1", "夸克原画#0101");
        cache.save();

        FlagPreferenceCache restored = new FlagPreferenceCache(cacheFile);
        FlagPreferenceCache.FlagPreference preference = restored.get("site", "vod");

        assertNotNull(preference);
        assertEquals("夸克原画#0101#1", preference.getStableKey());
        assertEquals("夸克原画#0101", preference.getFlagName());
    }

    @Test
    public void latestSelectionReplacesEarlierOne() {
        FlagPreferenceCache cache = new FlagPreferenceCache(cacheFile);
        cache.put("site", "vod", "BD5播放#0", "BD5播放");
        cache.put("site", "vod", "夸克无限#0101#2", "夸克无限#0101");
        cache.save();

        FlagPreferenceCache restored = new FlagPreferenceCache(cacheFile);

        assertEquals("夸克无限#0101#2", restored.get("site", "vod").getStableKey());
        assertEquals(1, restored.size());
    }

    @Test
    public void preferencesAreScopedPerSiteAndVod() {
        FlagPreferenceCache cache = new FlagPreferenceCache(cacheFile);
        cache.put("site-a", "vod-1", "线路一#0", "线路一");
        cache.put("site-b", "vod-1", "线路二#1", "线路二");
        cache.put("site-a", "vod-2", "线路三#2", "线路三");

        assertEquals("线路一#0", cache.get("site-a", "vod-1").getStableKey());
        assertEquals("线路二#1", cache.get("site-b", "vod-1").getStableKey());
        assertEquals("线路三#2", cache.get("site-a", "vod-2").getStableKey());
        assertNull(cache.get("site-b", "vod-2"));
    }

    @Test
    public void blankIdentityIsRejected() {
        FlagPreferenceCache cache = new FlagPreferenceCache(cacheFile);
        cache.put("", "vod", "线路一#0", "线路一");
        cache.put("site", "", "线路一#0", "线路一");
        cache.put("site", "vod", "", "");

        assertEquals(0, cache.size());
        assertNull(cache.get("site", "vod"));
    }

    @Test
    public void removeClearsStoredPreference() {
        FlagPreferenceCache cache = new FlagPreferenceCache(cacheFile);
        cache.put("site", "vod", "线路一#0", "线路一");

        cache.remove("site", "vod");

        assertNull(cache.get("site", "vod"));
    }

    @Test
    public void missingCacheFileYieldsNoPreference() {
        FlagPreferenceCache cache = new FlagPreferenceCache(cacheFile);

        assertNull(cache.get("site", "vod"));
        assertEquals(0, cache.size());
    }
}
