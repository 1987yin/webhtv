package com.fongmi.android.tv.ad.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;

public class ProbeRuleDownloaderTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private MockWebServer server;
    private OkHttpClient client;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new OkHttpClient();
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    /** 规则源不签名，所以传输层必须是 HTTPS，否则任何中间人都能改指纹。 */
    @Test
    public void plainHttpUrlIsRejectedBeforeAnyRequest() throws Exception {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProbeRuleDownloader.refresh("http://example.com/rules.json", store()));

        assertEquals("probe rule url must be https", error.getMessage());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void successfulDownloadIsInstalled() throws Exception {
        server.enqueue(new MockResponse().setBody(community()));
        ProbeRuleStore store = store();

        AdAudioRuleSnapshot snapshot = ProbeRuleDownloader.refresh(url(), store, client);

        assertEquals(4, snapshot.ruleSet().rules().size());
        assertTrue(snapshot.version().startsWith("2:"));
        assertEquals(2L, store.revision());
    }

    @Test
    public void oversizedBodyIsRejectedAndNothingIsCached() throws Exception {
        Buffer body = new Buffer();
        body.write(new byte[ProbeRuleStore.MAX_DOWNLOAD_BYTES + 1024]);
        server.enqueue(new MockResponse().setBody(body));
        ProbeRuleStore store = store();

        assertThrows(IOException.class, () -> ProbeRuleDownloader.refresh(url(), store, client));

        assertEquals(0L, store.revision());
    }

    @Test
    public void serverErrorIsRejected() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThrows(IOException.class, () -> ProbeRuleDownloader.refresh(url(), store(), client));
    }

    @Test
    public void malformedBodyLeavesTheStoreEmpty() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"format\":\"nope\"}"));
        ProbeRuleStore store = store();

        assertThrows(IllegalArgumentException.class,
                () -> ProbeRuleDownloader.refresh(url(), store, client));

        assertEquals(0L, store.revision());
    }

    private String url() {
        return server.url("/rules.json").toString();
    }

    private ProbeRuleStore store() throws IOException {
        return new ProbeRuleStore(temporaryFolder.newFolder().toPath());
    }

    private String community() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/probe-rules-v1-community.json")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
