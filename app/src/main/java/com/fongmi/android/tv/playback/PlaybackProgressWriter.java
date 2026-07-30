package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.db.dao.HistoryDao;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PlaybackProgressWriter {

    private PlaybackProgressWriter() {
    }

    public static PlaybackProgressApplyResult applyFromLocalApi(PlaybackProgressInput input) {
        if (!ViewingRecordSyncStore.isEnabled()) return PlaybackProgressApplyResult.failed(input, "观影记录同步未开启");
        if (!ViewingRecordSyncStore.isLocalWriteEnabled()) return PlaybackProgressApplyResult.failed(input, "本机 API 修改未开启");
        return applyInternal(input);
    }

    public static PlaybackProgressBatchResult applyFromLocalApi(List<PlaybackProgressInput> inputs) {
        PlaybackProgressBatchResult batch = new PlaybackProgressBatchResult();
        if (!ViewingRecordSyncStore.isEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressInput) null, "观影记录同步未开启"));
            return batch;
        }
        if (!ViewingRecordSyncStore.isLocalWriteEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressInput) null, "本机 API 修改未开启"));
            return batch;
        }
        return applyInternal(inputs);
    }

    public static PlaybackProgressBatchResult deleteFromLocalApi(List<PlaybackProgressDeleteInput> inputs) {
        PlaybackProgressBatchResult batch = new PlaybackProgressBatchResult();
        if (!ViewingRecordSyncStore.isEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressDeleteInput) null, "观影记录同步未开启"));
            return batch;
        }
        if (!ViewingRecordSyncStore.isLocalWriteEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressDeleteInput) null, "本机 API 修改未开启"));
            return batch;
        }
        if (inputs == null || inputs.isEmpty()) return batch;
        for (PlaybackProgressDeleteInput input : inputs) batch.add(deleteInternal(input));
        return batch;
    }

    public static PlaybackProgressBatchResult applyFromRemoteSync(List<PlaybackProgressInput> inputs, RemoteSyncConfig config) {
        return applyFromRemoteSync(inputs, config, Collections.emptyList());
    }

    public static PlaybackProgressBatchResult applyFromRemoteSync(List<PlaybackProgressInput> inputs, RemoteSyncConfig config, List<String> deletedKeys) {
        PlaybackProgressBatchResult batch = new PlaybackProgressBatchResult();
        if (!ViewingRecordSyncStore.isEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressInput) null, "观影记录同步未开启"));
            return batch;
        }
        for (PlaybackProgressInput input : inputs) {
            input.normalize();
            if (config != null && !config.matchesSite(input.siteKey)) {
                batch.add(PlaybackProgressApplyResult.skipped(input, input.targetHistoryKey(targetCid(input)), "站点不匹配", 0));
            } else if (!TextUtils.isEmpty(input.configKey) && targetCid(input) <= 0) {
                batch.add(PlaybackProgressApplyResult.skipped(input, input.historyKey, "接口不匹配", 0));
            } else {
                batch.add(applyInternal(input));
            }
        }
        // 按服务端返回的"已删除墓碑"直接匹配本地记录身份并删除。
        // 无论该记录是本地原生创建还是从远端拉取，都能被正确删除。
        pruneByDeleted(deletedKeys);
        return batch;
    }

    // 按服务端返回的"已删除墓碑"（dedupeKey 集合）直接匹配本地记录身份并删除。
    // 匹配基于与服务端完全一致的 dedupeKey 算法，
    // 原生创建或拉取得到的记录都能被正确清理；且只删除服务端被删除过的记录，不会误删纯本地记录。
    private static void pruneByDeleted(List<String> deletedKeys) {
        if (deletedKeys == null || deletedKeys.isEmpty()) return;
        Set<String> keys = new HashSet<>(deletedKeys);
        HistoryDao dao = AppDatabase.get().getHistoryDao();
        List<History> locals = dao.findAll();
        if (locals.isEmpty()) return;
        int removed = 0;
        for (History item : locals) {
            String dk = PlaybackRecord.dedupeKeyFor(item);
            if (keys.contains(dk)) {
                if (dao.delete(item.getCid(), item.getKey()) > 0) {
                    AppDatabase.get().getTrackDao().delete(item.getKey());
                    removed++;
                }
            }
        }
        if (removed > 0) {
            RefreshEvent.history();
            SpiderDebug.log("playback-remote-sync", "pruned-by-deleted removed=%s", removed);
        }
    }

    private static PlaybackProgressBatchResult applyInternal(List<PlaybackProgressInput> inputs) {
        PlaybackProgressBatchResult batch = new PlaybackProgressBatchResult();
        if (inputs == null || inputs.isEmpty()) return batch;
        for (PlaybackProgressInput input : inputs) batch.add(applyInternal(input));
        return batch;
    }

    private static PlaybackProgressApplyResult applyInternal(PlaybackProgressInput input) {
        if (Setting.isIncognito()) return PlaybackProgressApplyResult.failed(input, "隐身模式不允许写入");
        if (input == null) return PlaybackProgressApplyResult.failed((PlaybackProgressInput) null, "请求体不能为空");
        String error = input.validate();
        if (!TextUtils.isEmpty(error)) return PlaybackProgressApplyResult.failed(input, error);
        int cid = targetCid(input);
        if (cid <= 0) return PlaybackProgressApplyResult.skipped(input, input.historyKey, "接口不匹配", 0);
        String key = input.targetHistoryKey(cid);
        History local = findLocal(cid, input, key);
        if (local != null && input.updatedAt <= local.getCreateTime()) {
            return PlaybackProgressApplyResult.skipped(input, local.getKey(), "远端记录不新于本地", local.getCreateTime());
        }
        History history = local == null ? new History() : local.copy();
        boolean sameEpisode = isSameEpisode(local, input);
        history.setKey(key);
        history.setCid(cid);
        history.setVodName(input.vodName);
        history.setVodPic(input.vodPic);
        history.setVodFlag(input.flag);
        history.setVodRemarks(input.episodeName);
        history.setEpisodeUrl(input.episodeUrl);
        if (!sameEpisode) history.setTmdbEpisodePosition(null);
        history.setPosition(input.positionMs);
        history.setDuration(input.durationMs);
        applySpeed(history, input.speed, input.speedOverride);
        history.setCreateTime(input.updatedAt);
        if (local == null) {
            AppDatabase.get().getHistoryDao().insertOrUpdate(history);
            RefreshEvent.history();
            return PlaybackProgressApplyResult.created(input, history.getKey());
        }
        AppDatabase.get().getHistoryDao().insertOrUpdate(history);
        RefreshEvent.history();
        return PlaybackProgressApplyResult.updated(input, history.getKey());
    }

    static boolean isSameEpisode(History local, PlaybackProgressInput input) {
        if (local == null || input == null) return false;
        return Episode.create(input.episodeName, input.episodeUrl).matchesPlayback(local.getEpisode());
    }

    static void applySpeed(History history, float speed, Boolean speedOverride) {
        if (history == null) return;
        float value = speed <= 0 ? 1f : speed;
        if (speedOverride == null) {
            history.setSpeed(value);
        } else if (speedOverride) {
            history.setUserSpeed(value);
        } else {
            history.setSpeed(1f);
            history.setSpeedOverride(false);
        }
    }

    private static PlaybackProgressApplyResult deleteInternal(PlaybackProgressDeleteInput input) {
        if (Setting.isIncognito()) return PlaybackProgressApplyResult.failed(input, "隐身模式不允许清理");
        if (input == null) return PlaybackProgressApplyResult.failed((PlaybackProgressDeleteInput) null, "请求体不能为空");
        input.normalize();
        int cid = targetCid(input);
        if (cid <= 0) return PlaybackProgressApplyResult.skipped(input, input.historyKey, "接口不匹配");
        HistoryDao dao = AppDatabase.get().getHistoryDao();
        int affected;
        String historyKey = input.historyKey;
        if (input.isAllScope()) {
            if (!input.confirm) return PlaybackProgressApplyResult.failed(input, "全量清理需要confirm=true");
            affected = dao.delete(cid);
            if (affected > 0) RefreshEvent.history();
            return affected > 0 ? PlaybackProgressApplyResult.deleted(input, "", affected) : PlaybackProgressApplyResult.skipped(input, "", "本地记录不存在");
        }
        if (!TextUtils.isEmpty(historyKey)) {
            affected = dao.delete(cid, historyKey);
            if (affected > 0) RefreshEvent.history();
            return affected > 0 ? PlaybackProgressApplyResult.deleted(input, historyKey, affected) : PlaybackProgressApplyResult.skipped(input, historyKey, "本地记录不存在");
        }
        if (!TextUtils.isEmpty(input.siteKey) && !TextUtils.isEmpty(input.vodId)) {
            String baseKey = input.siteKey + AppDatabase.SYMBOL + input.vodId;
            affected = dao.delete(cid, baseKey);
            affected += dao.deleteByKeyPrefix(cid, baseKey + AppDatabase.SYMBOL);
            if (affected > 0) RefreshEvent.history();
            return affected > 0 ? PlaybackProgressApplyResult.deleted(input, baseKey, affected) : PlaybackProgressApplyResult.skipped(input, baseKey, "本地记录不存在");
        }
        if (!TextUtils.isEmpty(input.siteKey) && (input.isSiteScope() || input.confirm)) {
            String prefix = input.siteKey + AppDatabase.SYMBOL;
            affected = dao.deleteByKeyPrefix(cid, prefix);
            if (affected > 0) RefreshEvent.history();
            return affected > 0 ? PlaybackProgressApplyResult.deleted(input, prefix, affected) : PlaybackProgressApplyResult.skipped(input, prefix, "本地记录不存在");
        }
        if (!TextUtils.isEmpty(input.siteKey)) return PlaybackProgressApplyResult.failed(input, "按站点清理需要scope=site或confirm=true");
        return PlaybackProgressApplyResult.failed(input, "historyKey、siteKey+vodId或siteKey不能为空");
    }

    private static History findLocal(int cid, PlaybackProgressInput input, String key) {
        History exact = AppDatabase.get().getHistoryDao().find(cid, key);
        if (exact != null) return exact;
        String baseKey = input.siteKey + AppDatabase.SYMBOL + input.vodId;
        History base = AppDatabase.get().getHistoryDao().find(cid, baseKey);
        if (base != null) return base;
        List<History> items = AppDatabase.get().getHistoryDao().findByKeyPrefix(cid, baseKey + AppDatabase.SYMBOL);
        if (items.isEmpty()) return null;
        return bestEpisodeMatch(items, input);
    }

    private static History bestEpisodeMatch(List<History> items, PlaybackProgressInput input) {
        for (History item : items) if (!TextUtils.isEmpty(input.episodeUrl) && TextUtils.equals(input.episodeUrl, item.getEpisodeUrl())) return item;
        for (History item : items) if (!TextUtils.isEmpty(input.flag) && TextUtils.equals(input.flag, item.getVodFlag()) && TextUtils.equals(input.episodeName, item.getVodRemarks())) return item;
        for (History item : items) if (TextUtils.equals(input.episodeName, item.getVodRemarks())) return item;
        return items.get(0);
    }

    private static int targetCid(PlaybackProgressInput input) {
        int cid = PlaybackConfigIdentity.cidForKey(input.configKey);
        if (cid > 0) return cid;
        if (!TextUtils.isEmpty(input.configKey)) return 0;
        return input.cid > 0 ? input.cid : VodConfig.getCid();
    }

    private static int targetCid(PlaybackProgressDeleteInput input) {
        int cid = PlaybackConfigIdentity.cidForKey(input.configKey);
        if (cid > 0) return cid;
        if (!TextUtils.isEmpty(input.configKey)) return 0;
        if (input.cid > 0) return input.cid;
        try {
            int index = input.historyKey.lastIndexOf(AppDatabase.SYMBOL);
            if (index >= 0) return Integer.parseInt(input.historyKey.substring(index + AppDatabase.SYMBOL.length()));
        } catch (Exception ignored) {
        }
        return VodConfig.getCid();
    }
}
