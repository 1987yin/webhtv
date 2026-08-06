package com.fongmi.android.tv.web;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class WebThemeManifestLoader {

    static final int MAX_CACHE_ENTRIES = 8;
    private static final Map<String, WebThemeManifest> CACHE = new LinkedHashMap<>(8, 0.75f, true);
    private static final PersistentCache NO_PERSISTENT_CACHE = new PersistentCache() {
        @Override
        public String read(String cacheKey) {
            return null;
        }

        @Override
        public void write(String cacheKey, String json) {
        }

        @Override
        public void remove(String cacheKey) {
        }
    };
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .proxy(Proxy.NO_PROXY)
            .authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .dns(WebThemeManifestLoader::lookupPublic)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build();

    enum CacheState {
        REFRESHED,
        CACHE_HIT,
        LAST_KNOWN_GOOD
    }

    record LoadResult(WebThemeManifest manifest, CacheState state, IOException refreshFailure) {
        boolean usedLastKnownGood() {
            return state == CacheState.LAST_KNOWN_GOOD;
        }
    }

    @FunctionalInterface
    interface ManifestSource {
        String read() throws IOException;
    }

    interface PersistentCache {
        String read(String cacheKey) throws IOException;

        void write(String cacheKey, String json) throws IOException;

        void remove(String cacheKey);
    }

    private WebThemeManifestLoader() {
    }

    static WebThemeManifest load(Context context, String url, String target, boolean force) throws IOException {
        return loadResult(context, url, target, force).manifest();
    }

    static LoadResult loadResult(Context context, String url, String target, boolean force) throws IOException {
        String canonicalAsset = WebHomeTarget.canonicalThemeAsset(url);
        ManifestSource source = WebHomeTarget.ECLIPSE_URL.equals(canonicalAsset)
                ? () -> read(context.getAssets().open("webhome/theme.json"), WebThemeManifest.MAX_MANIFEST_BYTES)
                : () -> fetch(url);
        PersistentCache persistent = context != null && canonicalAsset.isEmpty()
                ? WebThemeManifestDiskCache.create(context)
                : NO_PERSISTENT_CACHE;
        return load(url, target, force, source, persistent);
    }

    static LoadResult load(String url, String target, boolean force, ManifestSource source) throws IOException {
        return load(url, target, force, source, NO_PERSISTENT_CACHE);
    }

    static LoadResult load(String url, String target, boolean force, ManifestSource source,
            PersistentCache persistentCache) throws IOException {
        PersistentCache persistent = persistentCache == null ? NO_PERSISTENT_CACHE : persistentCache;
        String cacheKey = cacheKey(url, target);
        WebThemeManifest cached = getCached(cacheKey);
        if (!force && cached != null) return new LoadResult(cached, CacheState.CACHE_HIT, null);
        try {
            String json = source.read();
            WebThemeManifest manifest = parse(url, target, json);
            putCached(cacheKey, manifest);
            persistBestEffort(persistent, cacheKey, json);
            return new LoadResult(manifest, CacheState.REFRESHED, null);
        } catch (IOException failure) {
            WebThemeManifest fallback = getCached(cacheKey);
            if (fallback == null) fallback = readPersistent(persistent, cacheKey, url, target);
            if (fallback != null) {
                putCached(cacheKey, fallback);
                return new LoadResult(fallback, CacheState.LAST_KNOWN_GOOD, failure);
            }
            throw failure;
        }
    }

    static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static String cacheKey(String url, String target) {
        return url + "\n" + target;
    }

    private static WebThemeManifest getCached(String cacheKey) {
        synchronized (CACHE) {
            return CACHE.get(cacheKey);
        }
    }

    private static void putCached(String cacheKey, WebThemeManifest manifest) {
        synchronized (CACHE) {
            CACHE.put(cacheKey, manifest);
            while (CACHE.size() > MAX_CACHE_ENTRIES) {
                String eldest = CACHE.keySet().iterator().next();
                CACHE.remove(eldest);
            }
        }
    }

    private static void persistBestEffort(PersistentCache persistent, String cacheKey, String json) {
        try {
            persistent.write(cacheKey, json);
        } catch (IOException ignored) {
        }
    }

    private static WebThemeManifest readPersistent(PersistentCache persistent, String cacheKey,
            String url, String target) {
        try {
            String json = persistent.read(cacheKey);
            if (json == null) return null;
            if (json.isEmpty()) {
                persistent.remove(cacheKey);
                return null;
            }
            return parse(url, target, json);
        } catch (IOException ignored) {
            persistent.remove(cacheKey);
            return null;
        }
    }

    private static WebThemeManifest parse(String url, String target, String json) throws IOException {
        try {
            return WebThemeManifest.parse(url, json, target);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid theme manifest", e);
        }
    }

    private static String fetch(String url) throws IOException {
        if (!WebHomeTarget.isSafeThemeUrl(url) || !WebHomeTarget.isManifestUrl(url)) {
            throw new IOException("Unsafe theme manifest URL");
        }
        Request request = new Request.Builder().url(url).get().header("Accept", "application/json").build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || !response.request().url().equals(request.url())) {
                throw new IOException("Theme manifest request failed: " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > WebThemeManifest.MAX_MANIFEST_BYTES) {
                throw new IOException("Theme manifest is too large");
            }
            return read(body.byteStream(), WebThemeManifest.MAX_MANIFEST_BYTES);
        }
    }

    static String read(InputStream input, int maxBytes) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) throw new IOException("Theme manifest is too large");
                output.write(buffer, 0, count);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(output.toByteArray()))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new IOException("Theme manifest is not valid UTF-8", e);
            }
        }
    }

    private static List<InetAddress> lookupPublic(String hostname) throws UnknownHostException {
        List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
        if (addresses.isEmpty()) throw new UnknownHostException(hostname);
        for (InetAddress address : addresses) {
            if (WebHomeTarget.isBlockedAddress(address)) throw new UnknownHostException("Blocked theme host");
        }
        return addresses;
    }
}
