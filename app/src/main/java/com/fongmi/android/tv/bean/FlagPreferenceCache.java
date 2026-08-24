package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.github.catvod.utils.Path;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线路选择偏好缓存
 *
 * 线路选择原本只作为播放的副产物写进 History.vodFlag，而写库要过
 * saveInlineHistory 的播放态门槛和 History.canSave() 的 position>0 门槛。
 * 详情页里「切了线路但没起播」或「从播放器返回详情页后再切线路」都不满足，
 * 进程被杀后重进就退回 flags.get(0)。
 *
 * 这里把线路选择独立存成用户偏好：切一下就落盘，不依赖播放进度，
 * 也不进 History 表（避免 position=0 的空记录污染「最近观看」列表）。
 *
 * 缓存策略与 EpisodePositionCache 一致：内存优先 + JSON 落盘 + 过期清理。
 */
public class FlagPreferenceCache {

    private static final String CACHE_FILE_NAME = "flag_preferences.json";
    private static final int MAX_ENTRIES = 500;
    private static final long EXPIRE_TIME = 90L * 24 * 60 * 60 * 1000; // 90天过期

    private final Map<String, FlagPreference> cache;
    private final Gson gson;
    private final File cacheFile;
    private boolean dirty = false;

    private static class Loader {
        static volatile FlagPreferenceCache INSTANCE = new FlagPreferenceCache();
    }

    public static FlagPreferenceCache get() {
        return Loader.INSTANCE;
    }

    private FlagPreferenceCache() {
        this(Path.cache(CACHE_FILE_NAME));
    }

    FlagPreferenceCache(File cacheFile) {
        this.cache = new ConcurrentHashMap<>();
        this.gson = new Gson();
        this.cacheFile = cacheFile;
        load();
    }

    /**
     * 一次线路选择。stableKey 是 Flag.stableKey 生成的「线路名#索引」，
     * 用于区分同名线路；flagName 是退化匹配用的线路名。
     */
    public static class FlagPreference {
        public String stableKey;
        public String flagName;
        public long timestamp;

        public FlagPreference() {
        }

        public FlagPreference(String stableKey, String flagName) {
            this.stableKey = stableKey;
            this.flagName = flagName;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > EXPIRE_TIME;
        }

        public String getStableKey() {
            return stableKey == null ? "" : stableKey;
        }

        public String getFlagName() {
            return flagName == null ? "" : flagName;
        }
    }

    /**
     * 构建缓存 key，格式: siteKey|vodId
     *
     * 不含线路名——每部剧在每个站源下只记一条「当前选中线路」。
     */
    private String buildKey(String siteKey, String vodId) {
        return siteKey + "|" + vodId;
    }

    /**
     * 记录用户选中的线路。
     *
     * @param siteKey  站点 key
     * @param vodId    视频 id
     * @param stableKey Flag.stableKey 生成的稳定键（线路名#索引）
     * @param flagName  线路名，稳定键失效时的退化匹配依据
     */
    public void put(String siteKey, String vodId, String stableKey, String flagName) {
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId)) return;
        if (TextUtils.isEmpty(stableKey) && TextUtils.isEmpty(flagName)) return;
        String key = buildKey(siteKey, vodId);
        FlagPreference existing = cache.get(key);
        // 播放路径每次换集都会重申当前线路，选择没变就不必反复落盘。
        boolean changed = existing == null
                || !TextUtils.equals(existing.getStableKey(), stableKey == null ? "" : stableKey)
                || !TextUtils.equals(existing.getFlagName(), flagName == null ? "" : flagName);
        cache.put(key, new FlagPreference(stableKey, flagName));
        if (!changed) return;
        dirty = true;
        if (cache.size() > MAX_ENTRIES) removeOldest();
    }

    /**
     * 读取上次选中的线路，没有或已过期返回 null。
     */
    public FlagPreference get(String siteKey, String vodId) {
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId)) return null;
        String key = buildKey(siteKey, vodId);
        FlagPreference preference = cache.get(key);
        if (preference == null) return null;
        if (preference.isExpired()) {
            cache.remove(key);
            dirty = true;
            return null;
        }
        return preference;
    }

    public void remove(String siteKey, String vodId) {
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId)) return;
        if (cache.remove(buildKey(siteKey, vodId)) != null) dirty = true;
    }

    private void removeOldest() {
        String oldest = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, FlagPreference> entry : cache.entrySet()) {
            if (entry.getValue().timestamp < oldestTime) {
                oldestTime = entry.getValue().timestamp;
                oldest = entry.getKey();
            }
        }
        if (oldest != null) cache.remove(oldest);
    }

    public synchronized void save() {
        if (!dirty) return;
        try {
            cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
            File file = cacheFile;
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                gson.toJson(new HashMap<>(cache), writer);
                dirty = false;
            }
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
        }
    }

    private synchronized void load() {
        try {
            if (!cacheFile.exists()) return;
            try (FileReader reader = new FileReader(cacheFile)) {
                Map<String, FlagPreference> loaded = gson.fromJson(reader,
                        new TypeToken<Map<String, FlagPreference>>() {}.getType());
                if (loaded == null) return;
                cache.clear();
                loaded.forEach((key, value) -> {
                    if (key != null && value != null) cache.put(key, value);
                });
                cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized void clear() {
        cache.clear();
        dirty = false;
        if (cacheFile.exists()) cacheFile.delete();
    }

    int size() {
        return cache.size();
    }
}
