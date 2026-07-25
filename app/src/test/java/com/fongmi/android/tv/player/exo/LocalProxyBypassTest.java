package com.fongmi.android.tv.player.exo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class LocalProxyBypassTest {

    @Test
    public void unwrapsLoopbackMultithreadProxyToRemoteRawUrl() {
        String upstream = "https://dl-pc-zb.pds.quark.cn/video.mkv?token=abc%2Fdef%3D";
        String proxy = "http://127.0.0.1:5575/proxy?thread=10&chunkSize=1024&url=" + encode(upstream);

        LocalProxyBypass.Target target = LocalProxyBypass.maybeUnwrap(proxy, Map.of(
                "Cookie", "session=private",
                "Referer", "https://pan.quark.cn"));

        assertEquals(upstream, target.url());
        assertEquals("session=private", target.headers().get("Cookie"));
        assertEquals("https://pan.quark.cn", target.headers().get("Referer"));
    }

    @Test
    public void encodedProxyHeadersOverridePlaybackHeaders() {
        String upstream = "https://d.pcs.baidu.com/file?sign=private";
        String encodedHeaders = encode("{\"Cookie\":\"proxy-cookie\",\"User-Agent\":\"pan-agent\"}");
        String proxy = "http://localhost:5575/proxy?url=" + encode(upstream) + "&header=" + encodedHeaders + "&thread=8";

        LocalProxyBypass.Target target = LocalProxyBypass.maybeUnwrap(proxy, Map.of("Cookie", "outer-cookie"));

        assertEquals(upstream, target.url());
        assertEquals("proxy-cookie", target.headers().get("Cookie"));
        assertEquals("pan-agent", target.headers().get("User-Agent"));
    }

    @Test
    public void keepsOpaqueOrNonMultithreadLocalProxy() {
        assertNull(LocalProxyBypass.maybeUnwrap(
                "http://127.0.0.1:9978/proxy?do=js&url=" + encode("https://example.com/video.mp4"), Map.of()));
        assertNull(LocalProxyBypass.maybeUnwrap(
                "http://127.0.0.1:5575/proxy?thread=10&id=opaque", Map.of()));
    }

    @Test
    public void rejectsRemoteProxyAndPrivateUpstream() {
        assertNull(LocalProxyBypass.maybeUnwrap(
                "https://proxy.example.com/proxy?thread=10&url=" + encode("https://example.com/video.mp4"), Map.of()));
        assertNull(LocalProxyBypass.maybeUnwrap(
                "http://127.0.0.1:5575/proxy?thread=10&url=" + encode("http://192.168.1.2/private.mkv"), Map.of()));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
