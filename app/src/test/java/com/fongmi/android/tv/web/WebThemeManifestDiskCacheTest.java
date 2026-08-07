package com.fongmi.android.tv.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WebThemeManifestDiskCacheTest {

    private Path directory;

    @Before
    public void setUp() throws Exception {
        directory = Files.createTempDirectory("webtheme-manifest-cache");
    }

    @After
    public void tearDown() throws Exception {
        if (directory == null || !Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    public void cachedPayloadAndMetadataSurviveAStoreRecreationWithoutLeakingTheUrl() throws Exception {
        String cacheKey = "https://themes.example/theme.json?token=secret\nmobile";
        WebThemeManifestLoader.StoredManifest expected = stored("{\"schemaVersion\":2}", "\"v1\"", 1234);
        WebThemeManifestDiskCache first = new WebThemeManifestDiskCache(directory.toFile());

        first.write(cacheKey, expected);
        WebThemeManifestDiskCache recreated = new WebThemeManifestDiskCache(directory.toFile());

        assertEquals(expected, recreated.read(cacheKey));
        List<Path> files = dataFiles();
        assertEquals(1, files.size());
        String fileName = files.get(0).getFileName().toString();
        assertTrue(fileName.matches("[0-9a-f]{64}\\.json"));
        assertFalse(fileName.contains("themes.example"));
        assertFalse(fileName.contains("secret"));
    }

    @Test
    public void legacyRawManifestRemainsAvailableAsExpiredLastKnownGood() throws Exception {
        String cacheKey = "https://themes.example/theme.json\nmobile";
        String json = "{\"schemaVersion\":2}";
        WebThemeManifestDiskCache cache = new WebThemeManifestDiskCache(directory.toFile());
        cache.write(cacheKey, stored(json, "\"v1\"", 1234));
        Files.writeString(dataFiles().get(0), json, StandardCharsets.UTF_8);

        WebThemeManifestLoader.StoredManifest migrated = cache.read(cacheKey);

        assertEquals(json, migrated.json());
        assertEquals("", migrated.etag());
        assertEquals(0, migrated.validatedAt());
    }

    @Test
    public void maximumSizedManifestStillFitsInsideTheMetadataEnvelope() throws Exception {
        String json = "x".repeat(WebThemeManifest.MAX_MANIFEST_BYTES);
        WebThemeManifestDiskCache cache = new WebThemeManifestDiskCache(directory.toFile());

        cache.write("key", stored(json, "\"v1\"", 1234));

        assertEquals(json, cache.read("key").json());
    }

    @Test
    public void diskCachePrunesEntriesToTheSameBoundAsTheMemoryCache() throws Exception {
        WebThemeManifestDiskCache cache = new WebThemeManifestDiskCache(directory.toFile());

        for (int index = 0; index < WebThemeManifestLoader.MAX_CACHE_ENTRIES + 1; index++) {
            cache.write("https://themes.example/" + index + ".json\nmobile",
                    stored("{\"id\":" + index + "}", "\"v" + index + "\"", index + 1));
        }

        assertEquals(WebThemeManifestLoader.MAX_CACHE_ENTRIES, dataFiles().size());
    }

    @Test
    public void oversizedEntryIsRejectedBeforeItTouchesDisk() {
        WebThemeManifestDiskCache cache = new WebThemeManifestDiskCache(directory.toFile());
        String oversized = "x".repeat(WebThemeManifest.MAX_MANIFEST_BYTES + 1);

        assertThrows(IOException.class, () -> cache.write("key", stored(oversized, "", 1)));
        assertTrue(dataFilesUnchecked().isEmpty());
    }

    private static WebThemeManifestLoader.StoredManifest stored(String json, String etag, long validatedAt) {
        return new WebThemeManifestLoader.StoredManifest(json, etag, validatedAt);
    }

    private List<Path> dataFiles() throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .collect(Collectors.toList());
        }
    }

    private List<Path> dataFilesUnchecked() {
        try {
            return dataFiles();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
