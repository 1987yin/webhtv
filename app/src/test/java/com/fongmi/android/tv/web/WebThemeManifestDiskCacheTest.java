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
    public void cachedPayloadSurvivesAStoreRecreationWithoutLeakingTheUrlInItsFileName() throws Exception {
        String cacheKey = "https://themes.example/theme.json?token=secret\nmobile";
        String json = "{\"schemaVersion\":2}";
        WebThemeManifestDiskCache first = new WebThemeManifestDiskCache(directory.toFile());

        first.write(cacheKey, json);
        WebThemeManifestDiskCache recreated = new WebThemeManifestDiskCache(directory.toFile());

        assertEquals(json, recreated.read(cacheKey));
        List<Path> files = dataFiles();
        assertEquals(1, files.size());
        String fileName = files.get(0).getFileName().toString();
        assertTrue(fileName.matches("[0-9a-f]{64}\\.json"));
        assertFalse(fileName.contains("themes.example"));
        assertFalse(fileName.contains("secret"));
    }

    @Test
    public void diskCachePrunesEntriesToTheSameBoundAsTheMemoryCache() throws Exception {
        WebThemeManifestDiskCache cache = new WebThemeManifestDiskCache(directory.toFile());

        for (int index = 0; index < WebThemeManifestLoader.MAX_CACHE_ENTRIES + 1; index++) {
            cache.write("https://themes.example/" + index + ".json\nmobile", "{\"id\":" + index + "}");
        }

        assertEquals(WebThemeManifestLoader.MAX_CACHE_ENTRIES, dataFiles().size());
    }

    @Test
    public void oversizedEntryIsRejectedBeforeItTouchesDisk() {
        WebThemeManifestDiskCache cache = new WebThemeManifestDiskCache(directory.toFile());
        String oversized = "x".repeat(WebThemeManifest.MAX_MANIFEST_BYTES + 1);

        assertThrows(IOException.class, () -> cache.write("key", oversized));
        assertTrue(dataFilesUnchecked().isEmpty());
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
