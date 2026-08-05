package com.fongmi.android.tv.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public class WebThemeManifestLoaderTest {

    private static final String CACHE_URL = "https://cache.example/theme.json";

    @Before
    @After
    public void clearManifestCache() {
        WebThemeManifestLoader.clearCache();
    }

    @Test
    public void cacheMatrixUsesHitRefreshAndLastKnownGood() throws Exception {
        AtomicInteger reads = new AtomicInteger();

        WebThemeManifestLoader.LoadResult first = WebThemeManifestLoader.load(
                CACHE_URL, "mobile", false, () -> {
                    reads.incrementAndGet();
                    return manifest("1", "home-v1.html");
                });
        WebThemeManifestLoader.LoadResult hit = WebThemeManifestLoader.load(
                CACHE_URL, "mobile", false, () -> {
                    throw new AssertionError("cache hit must not read source");
                });
        WebThemeManifestLoader.LoadResult refreshed = WebThemeManifestLoader.load(
                CACHE_URL, "mobile", true, () -> {
                    reads.incrementAndGet();
                    return manifest("2", "home-v2.html");
                });
        IOException refreshFailure = new IOException("private upstream details");
        WebThemeManifestLoader.LoadResult fallback = WebThemeManifestLoader.load(
                CACHE_URL, "mobile", true, () -> {
                    reads.incrementAndGet();
                    throw refreshFailure;
                });

        assertEquals(WebThemeManifestLoader.CacheState.REFRESHED, first.state());
        assertEquals(WebThemeManifestLoader.CacheState.CACHE_HIT, hit.state());
        assertSame(first.manifest(), hit.manifest());
        assertEquals(WebThemeManifestLoader.CacheState.REFRESHED, refreshed.state());
        assertEquals("https://cache.example/home-v2.html",
                refreshed.manifest().getPage(WebThemePage.HOME).getEntryUrl());
        assertEquals(WebThemeManifestLoader.CacheState.LAST_KNOWN_GOOD, fallback.state());
        assertSame(refreshed.manifest(), fallback.manifest());
        assertSame(refreshFailure, fallback.refreshFailure());
        assertEquals(3, reads.get());
    }


    @Test
    public void cacheEntriesAreIsolatedByPlatformTarget() throws Exception {
        WebThemeManifestLoader.LoadResult mobile = WebThemeManifestLoader.load(
                CACHE_URL, "mobile", false, () -> manifest("1", "mobile.html"));
        WebThemeManifestLoader.LoadResult leanback = WebThemeManifestLoader.load(
                CACHE_URL, "leanback", false, () -> manifest("1", "leanback.html"));
        WebThemeManifestLoader.LoadResult mobileHit = WebThemeManifestLoader.load(
                CACHE_URL, "mobile", false, () -> {
                    throw new AssertionError("mobile cache hit must not read source");
                });

        assertEquals("https://cache.example/mobile.html",
                mobile.manifest().getPage(WebThemePage.HOME).getEntryUrl());
        assertEquals("https://cache.example/leanback.html",
                leanback.manifest().getPage(WebThemePage.HOME).getEntryUrl());
        assertEquals(WebThemeManifestLoader.CacheState.CACHE_HIT, mobileHit.state());
        assertSame(mobile.manifest(), mobileHit.manifest());
    }

    @Test
    public void invalidRefreshKeepsThePreviousValidatedManifest() throws Exception {
        WebThemeManifestLoader.LoadResult first = WebThemeManifestLoader.load(
                CACHE_URL, "mobile", false, () -> manifest("1", "home-v1.html"));

        WebThemeManifestLoader.LoadResult fallback = WebThemeManifestLoader.load(
                CACHE_URL, "mobile", true, () -> "{\"schemaVersion\":2}");

        assertEquals(WebThemeManifestLoader.CacheState.LAST_KNOWN_GOOD, fallback.state());
        assertSame(first.manifest(), fallback.manifest());
        assertNotNull(fallback.refreshFailure());
        assertTrue(fallback.refreshFailure().getCause() instanceof IllegalArgumentException);
    }

    @Test
    public void coldFailureIsNotSilentlyRecovered() {
        assertThrows(IOException.class, () -> WebThemeManifestLoader.load(
                CACHE_URL, "mobile", true, () -> {
                    throw new IOException("offline");
                }));
    }

    @Test
    public void boundedReaderAcceptsLimitAndRejectsOneExtraByte() throws Exception {
        assertEquals("1234", WebThemeManifestLoader.read(stream("1234"), 4));
        assertThrows(IOException.class, () -> WebThemeManifestLoader.read(stream("12345"), 4));
    }

    @Test
    public void boundedReaderRejectsMalformedUtf8() {
        byte[] malformed = {(byte) 0xC3, (byte) 0x28};

        assertThrows(IOException.class,
                () -> WebThemeManifestLoader.read(new ByteArrayInputStream(malformed), malformed.length));
    }

    @Test
    public void boundedReaderUsesAndroidCompatibleStrictUtf8Decoding() throws Exception {
        String source = source();

        assertTrue(source.contains("StandardCharsets.UTF_8.newDecoder()"));
        assertTrue(source.contains("CodingErrorAction.REPORT"));
        assertFalse(source.contains("output.toString(StandardCharsets.UTF_8)"));
    }

    @Test
    public void remoteManifestUsesIsolatedPlatformTlsClient() throws Exception {
        String source = source();

        assertTrue(source.contains("new OkHttpClient.Builder()"));
        assertTrue(source.contains("Dns.SYSTEM.lookup(hostname)"));
        assertFalse(source.contains("OkHttp.client().newBuilder()"));
        assertFalse(source.contains("com.github.catvod.net.OkHttp"));
    }

    private static String manifest(String version, String entry) {
        return "{\"schemaVersion\":2,\"id\":\"cache.theme\",\"version\":\"" + version
                + "\",\"minHostApi\":2,\"pages\":{\"home\":{\"entry\":\"" + entry
                + "\",\"contract\":\"vod.home@1\"}},\"permissions\":{\"home\":[\"vod.home\"]}}";
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String source() throws Exception {
        Path root = Files.exists(Path.of("src")) ? Path.of("") : Path.of("app");
        return Files.readString(root.resolve(
                "src/main/java/com/fongmi/android/tv/web/WebThemeManifestLoader.java"), StandardCharsets.UTF_8);
    }
}
