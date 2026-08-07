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
    static final long CACHE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(15);

    private static final int MAX_ETAG_LENGTH = 256;
    private static final Map<String, CachedManifest> CACHE = new LinkedHashMap<>(8, 0.75f, true);
    private static final PersistentCache NO_PERSISTENT_CACHE = new PersistentCache() {
        @Override
        public StoredManifest read(String cacheKey) {
            return null;
        }

        @Override
        public void write(String cacheKey, StoredManifest stored) {
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

    record StoredManifest(String json, String etag, long validatedAt) {
        StoredManifest {
            json = json == null ? "" : json;
            etag = normalizeEtag(etag);
        }

        boolean isFresh(long now) {
            return validatedAt > 0 && now >= validatedAt && now - validatedAt < CACHE_TTL_MILLIS;
        }

        StoredManifest revalidated(String responseEtag, long now) {
            String nextEtag = normalizeEtag(responseEtag);
            return new StoredManifest(json, nextEtag.isEmpty() ? etag : nextEtag, now);
        }
    }

    record FetchResult(String json, String etag, boolean notModified) {
        FetchResult {
            json = notModified ? null : json;
            etag = normalizeEtag(etag);
        }

        static FetchResult modified(String json, String etag) {
            return new FetchResult(json, etag, false);
        }

        static FetchResult notModified(String etag) {
            return new FetchResult(null, etag, true);
        }
    }

    private record CachedManifest(WebThemeManifest manifest, StoredManifest stored) {
    }

    @FunctionalInterface
    interface ManifestSource {
        String read() throws IOException;
    }

    @FunctionalInterface
    interface ConditionalSource {
        FetchResult read(String etag) throws IOException;
    }

    interface PersistentCache {
        StoredManifest read(String cacheKey) throws IOException;

        void write(String cacheKey, StoredManifest stored) throws IOException;

        void remove(String cacheKey);
    }

    private WebThemeManifestLoader() {
    }

    static WebThemeManifest load(Context context, String url, String target, boolean force) throws IOException {
        return loadResult(context, url, target, force).manifest();
    }

    static LoadResult loadResult(Context context, String url, String target, boolean force) throws IOException {
        String canonicalAsset = WebHomeTarget.canonicalThemeAsset(url);
        if (WebHomeTarget.ECLIPSE_URL.equals(canonicalAsset)) {
            return load(url, target, force,
                    () -> read(context.getAssets().open("webhome/theme.json"),
                            WebThemeManifest.MAX_MANIFEST_BYTES));
        }
        PersistentCache persistent = context != null && canonicalAsset.isEmpty()
                ? WebThemeManifestDiskCache.create(context)
                : NO_PERSISTENT_CACHE;
        return load(url, target, force, etag -> fetch(url, etag), persistent,
                System.currentTimeMillis());
    }

    static LoadResult load(String url, String target, boolean force, ManifestSource source) throws IOException {
        return load(url, target, force, source, NO_PERSISTENT_CACHE);
    }

    static LoadResult load(String url, String target, boolean force, ManifestSource source,
            PersistentCache persistentCache) throws IOException {
        return load(url, target, force,
                etag -> FetchResult.modified(source.read(), ""), persistentCache,
                System.currentTimeMillis());
    }

    static LoadResult load(String url, String target, boolean force, ConditionalSource source,
            PersistentCache persistentCache, long now) throws IOException {
        PersistentCache persistent = persistentCache == null ? NO_PERSISTENT_CACHE : persistentCache;
        String cacheKey = cacheKey(url, target);
        CachedManifest cached = getCached(cacheKey);
        if (cached == null) {
            cached = readPersistent(persistent, cacheKey, url, target);
            if (cached != null) putCached(cacheKey, cached);
        }
        if (!force && cached != null && cached.stored().isFresh(now)) {
            return new LoadResult(cached.manifest(), CacheState.CACHE_HIT, null);
        }
        try {
            String etag = cached == null ? "" : cached.stored().etag();
            FetchResult fetched = source.read(etag);
            if (fetched == null) throw new IOException("Theme manifest request returned no result");
            if (fetched.notModified()) {
                if (cached == null || etag.isEmpty()) {
                    throw new IOException("Theme manifest was not modified without a cached validator");
                }
                StoredManifest stored = cached.stored().revalidated(fetched.etag(), now);
                CachedManifest revalidated = new CachedManifest(cached.manifest(), stored);
                putCached(cacheKey, revalidated);
                persistBestEffort(persistent, cacheKey, stored);
                return new LoadResult(revalidated.manifest(), CacheState.CACHE_HIT, null);
            }
            StoredManifest stored = new StoredManifest(fetched.json(), fetched.etag(), now);
            WebThemeManifest manifest = parse(url, target, stored.json());
            CachedManifest refreshed = new CachedManifest(manifest, stored);
            putCached(cacheKey, refreshed);
            persistBestEffort(persistent, cacheKey, stored);
            return new LoadResult(manifest, CacheState.REFRESHED, null);
        } catch (IOException failure) {
            CachedManifest fallback = getCached(cacheKey);
            if (fallback == null) fallback = cached;
            if (fallback != null) {
                return new LoadResult(fallback.manifest(), CacheState.LAST_KNOWN_GOOD, failure);
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

    private static CachedManifest getCached(String cacheKey) {
        synchronized (CACHE) {
            return CACHE.get(cacheKey);
        }
    }

    private static void putCached(String cacheKey, CachedManifest cached) {
        synchronized (CACHE) {
            CACHE.put(cacheKey, cached);
            while (CACHE.size() > MAX_CACHE_ENTRIES) {
                String eldest = CACHE.keySet().iterator().next();
                CACHE.remove(eldest);
            }
        }
    }

    private static void persistBestEffort(PersistentCache persistent, String cacheKey,
            StoredManifest stored) {
        try {
            persistent.write(cacheKey, stored);
        } catch (IOException ignored) {
        }
    }

    private static CachedManifest readPersistent(PersistentCache persistent, String cacheKey,
            String url, String target) {
        try {
            StoredManifest stored = persistent.read(cacheKey);
            if (stored == null) return null;
            if (stored.json().isEmpty()) {
                persistent.remove(cacheKey);
                return null;
            }
            return new CachedManifest(parse(url, target, stored.json()), stored);
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

    private static FetchResult fetch(String url, String etag) throws IOException {
        if (!WebHomeTarget.isSafeThemeUrl(url) || !WebHomeTarget.isManifestUrl(url)) {
            throw new IOException("Unsafe theme manifest URL");
        }
        return execute(CLIENT, buildRequest(url, etag));
    }

    static Request buildRequest(String url, String etag) {
        Request.Builder request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json");
        String normalizedEtag = normalizeEtag(etag);
        if (!normalizedEtag.isEmpty()) request.header("If-None-Match", normalizedEtag);
        return request.build();
    }

    static FetchResult execute(OkHttpClient client, Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.request().url().equals(request.url())) {
                throw new IOException("Theme manifest request was redirected");
            }
            String responseEtag = normalizeEtag(response.header("ETag"));
            if (response.code() == 304) return FetchResult.notModified(responseEtag);
            if (!response.isSuccessful()) {
                throw new IOException("Theme manifest request failed: " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > WebThemeManifest.MAX_MANIFEST_BYTES) {
                throw new IOException("Theme manifest is too large");
            }
            return FetchResult.modified(
                    read(body.byteStream(), WebThemeManifest.MAX_MANIFEST_BYTES), responseEtag);
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

    private static String normalizeEtag(String etag) {
        if (etag == null) return "";
        String value = etag.trim();
        if (value.isEmpty() || value.length() > MAX_ETAG_LENGTH) return "";
        int opaqueStart;
        if (value.startsWith("W/\"") && value.endsWith("\"")) opaqueStart = 3;
        else if (value.startsWith("\"") && value.endsWith("\"")) opaqueStart = 1;
        else return "";
        if (opaqueStart >= value.length() - 1) return "";
        for (int index = opaqueStart; index < value.length() - 1; index++) {
            char part = value.charAt(index);
            if (part == '"' || part < 0x20 || part > 0x7e) return "";
        }
        return value;
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
