package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.diagnostic.PanEndpoint;
import com.fongmi.android.tv.player.diagnostic.PanEndpointParser;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EXO-only fallback for loopback multi-thread proxies that expose a signed RAW
 * URL. Direct playback avoids proxy implementations that buffer large ranges in
 * memory while preserving the provider headers needed by ExoPlayer.
 */
public final class LocalProxyBypass {

    public record Target(String url, Map<String, String> headers) {
    }

    private LocalProxyBypass() {
    }

    public static Target maybeUnwrap(String playbackUrl, Map<String, String> playbackHeaders) {
        if (!isLoopbackProxy(playbackUrl)) return null;
        try {
            PanEndpoint endpoint = PanEndpointParser.parse(playbackUrl, playbackHeaders);
            if (!endpoint.hasDirectUpstream() || endpoint.configuredThreads() <= 0) return null;
            Map<String, String> headers = new LinkedHashMap<>(endpoint.upstreamHeaders());
            return new Target(endpoint.upstreamUrl(), Collections.unmodifiableMap(headers));
        } catch (IllegalArgumentException e) {
            com.github.catvod.crawler.SpiderDebug.log("player", "bypass rejected: %s", e.getMessage());
            return null;
        } catch (RuntimeException e) {
            com.github.catvod.crawler.SpiderDebug.log("player", "bypass parse failed: %s", e.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean isLoopbackProxy(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || !"/proxy".equals(uri.getPath())) return false;
            String host = uri.getHost();
            return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
