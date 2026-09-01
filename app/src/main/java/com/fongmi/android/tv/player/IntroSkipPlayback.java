package com.fongmi.android.tv.player;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.service.IntroSkipService;
import com.fongmi.android.tv.service.IntroSkipService.IntroSkipPlan;
import com.fongmi.android.tv.service.IntroSkipService.Segment;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;

import java.util.HashSet;
import java.util.Set;

public class IntroSkipPlayback {

    public interface SkipConfirmListener {
        void onSkipConfirm(Segment segment, Runnable action);
    }

    /**
     * 跳过已执行的回调，用于给用户一个「刚才发生了什么」的提示。
     *
     * @param seeked true 表示跳到了段末，false 表示该段延伸到文件结束、按「本集看完」处理
     */
    public interface SkipNoticeListener {
        void onSkipped(Segment segment, boolean seeked);
    }

    private static final long TOLERANCE_MS = 1500;
    private static final long MIN_SKIP_DELTA_MS = 1500;
    /** 时长归一粒度：HLS 的时长会随 manifest 精化抖动，别为几百毫秒反复重解析。 */
    private static final long DURATION_BUCKET_MS = 2000;

    private final IntroSkipService service = new IntroSkipService();
    private final Set<String> skipped = new HashSet<>();
    private IntroSkipPlan plan = IntroSkipPlan.empty();
    private String loadedKey = "";
    private String loadingKey = "";
    private int generation;
    private boolean loading;
    private long resumeMs;
    private SkipConfirmListener skipConfirmListener;
    private SkipNoticeListener skipNoticeListener;

    public void reset() {
        generation++;
        loading = false;
        loadedKey = "";
        loadingKey = "";
        plan = IntroSkipPlan.empty();
        skipped.clear();
        resumeMs = 0;
    }

    /**
     * 记录本次续播基准位置。起点在该位置及之前的片头段视为"用户已越过或正处于"，
     * 不再触发自动跳过/确认提示，避免 seekTo 尚未落地时误弹确认框。
     */
    public void setResumePosition(long ms) {
        resumeMs = Math.max(0, ms);
    }

    public void setSkipConfirmListener(SkipConfirmListener listener) {
        this.skipConfirmListener = listener;
    }

    public void setSkipNoticeListener(SkipNoticeListener listener) {
        this.skipNoticeListener = listener;
    }

    /**
     * 探测到的片头落点（正片从这里继续），无数据返回 -1。
     *
     * <p>仅用于控制栏展示。一集可能有多段片头（回顾 + OP，中间夹正片），这里给最先触发的那
     * 一段，与用户接下来真正会看到的跳过动作一致；不把多段合成一个数，那会谎报中间的正片。
     */
    public long getDetectedOpeningMs() {
        for (Segment segment : plan.getOpenings()) {
            // 续播已越过的段不会再触发，显示它等于报一个不会发生的时间
            if (segment.getEndMs() > 0 && isKindEnabled(segment) && !passedOnResume(segment, 0)) return segment.getEndMs();
        }
        return -1;
    }

    /**
     * 探测到的片尾时长（距本集结尾多久开始），无数据返回 -1。
     *
     * <p>换算成「距结尾的剩余时长」，与手动片尾按钮同一语义，两者显示出来才可比。
     */
    public long getDetectedEndingMs(long durationMs) {
        if (durationMs <= 0) return -1;
        for (Segment segment : plan.getEndings()) {
            long start = segment.getStartMs();
            if (start > 0 && start < durationMs && isKindEnabled(segment) && !passedOnResume(segment, durationMs)) return durationMs - start;
        }
        return -1;
    }

    /**
     * 去重键带上归一后的时长：折算依赖时长，而时长在 onPrepare 时还是 0、到 STATE_READY 才有值。
     * 只按身份去重会让第二次请求被跳过，计划永远停在「按时长 0 折算」的状态。重解析走的是
     * 服务层的原始段缓存，不会再发网络请求。
     */
    private String signature(IntroSkipService.Query query) {
        long duration = query.getDurationMs();
        return query.cacheKey() + "@" + (duration <= 0 ? 0 : duration / DURATION_BUCKET_MS);
    }

    /**
     * 后台预热某一集的数据，只灌缓存，不碰当前计划、不回调、不参与去重状态。
     * 重复调用是安全的：命中缓存就直接返回。
     */
    public void preload(IntroSkipService.Query query) {
        if (query == null || !query.hasLookupKey()) return;
        Task.execute(() -> service.preload(query));
    }

    public void request(IntroSkipService.Query query, Runnable onLoaded) {
        if (query == null || !query.hasLookupKey()) return;
        String key = signature(query);
        if (key.equals(loadedKey) || (loading && key.equals(loadingKey))) return;
        int current = ++generation;
        loading = true;
        loadingKey = key;
        Task.execute(() -> {
            IntroSkipPlan loaded = service.load(query);
            App.post(() -> {
                if (current != generation || !key.equals(loadingKey)) return;
                loading = false;
                loadedKey = key;
                if (!loaded.isEmpty() || plan.isEmpty()) plan = loaded;
                SpiderDebug.log("intro-skip", "plan ready key=%s resumeMs=%d segments=%s", key, resumeMs, describe(plan));
                if (onLoaded != null) onLoaded.run();
            });
        });
    }

    /**
     * 按时间顺序逐段判定。四类片段（回顾、片头、片尾、预告）走同一条通路，差别只在
     * 「用户是否开了这一类」和「有没有可 seek 的落点」，不再按片头/片尾分两套逻辑。
     */
    public boolean apply(PlayerManager player, Runnable onEnding) {
        if (player == null || plan == null || plan.isEmpty()) return false;
        int mode = Setting.getIntroSkipMode();
        if (mode == Setting.INTRO_SKIP_OFF) return false;
        long position = player.getPosition();
        long duration = player.getDuration();
        if (position < 0) return false;

        for (Segment segment : plan.getAll()) {
            String id = id(segment);
            if (skipped.contains(id)) continue;
            long start = segment.getStartMs();
            // 用户关掉了这一类：标记掉，避免每个 tick 重复判定
            if (!isKindEnabled(segment)) {
                skipped.add(id);
                continue;
            }
            if (duration > 0 && start >= duration) {
                skipped.add(id);
                continue;
            }
            if (passedOnResume(segment, duration)) {
                skipped.add(id);
                continue;
            }
            long target = skipTarget(segment, position, duration);
            // 片头/回顾没有落点就无事可做（跳过去等于没跳），尾部段则交给「本集看完」
            if (target <= 0 && segment.isOpening()) {
                skipped.add(id);
                continue;
            }
            if (position + TOLERANCE_MS < start) continue;

            skipped.add(id);
            SpiderDebug.log("intro-skip", "hit kind=%s provider=%s from=%d start=%d end=%d openEnded=%s target=%d duration=%d mode=%d", segment.getKind(), segment.getProvider(), position, start, segment.getEndMs(), segment.isOpenEnded(), target, duration, mode);
            if (mode == Setting.INTRO_SKIP_AUTO) {
                runSkip(player, segment, target, onEnding);
            } else if (mode == Setting.INTRO_SKIP_CONFIRM && skipConfirmListener != null) {
                skipConfirmListener.onSkipConfirm(segment, () -> runSkip(player, segment, target, onEnding));
            }
            return true;
        }
        return false;
    }

    private void runSkip(PlayerManager player, Segment segment, long target, Runnable onEnding) {
        boolean seeked = target > 0;
        if (seeked) player.seekTo(target);
        else if (onEnding != null) onEnding.run();
        if (skipNoticeListener != null) skipNoticeListener.onSkipped(segment, seeked);
    }

    /**
     * 续播落点是否已经越过本段，越过则不再干预。
     *
     * <p>片头和片尾的判断刻意不对称：
     * <ul>
     * <li>片头/回顾看<b>结束点</b>。续播落在片头中间（例如片头 0→45s、续播到 15s）说明用户
     *     正处在片头里，往前跳到段末正是这个功能该做的事。早先按起点判断，而接口对没给
     *     start_ms 的片头会填 0，于是「起点 0 ≤ 任何续播位置」恒成立，只要有观看历史，
     *     开头那段片头就永久不再触发。</li>
     * <li>片尾/预告看<b>起点</b>。续播落点在片尾之内是用户自己挑的位置，此时立刻判定
     *     「本集看完」跳下一集非常突兀。</li>
     * </ul>
     */
    private boolean passedOnResume(Segment segment, long duration) {
        if (resumeMs <= 0) return false;
        if (segment.isEnding()) return segment.getStartMs() <= resumeMs;
        long end = segment.getEndMs();
        if (end <= 0 && duration > 0) end = duration;
        return end > 0 && resumeMs >= end - MIN_SKIP_DELTA_MS;
    }

    private boolean isKindEnabled(Segment segment) {
        switch (segment.getKind()) {
            case RECAP: return Setting.isIntroSkipKindEnabled(Setting.INTRO_SKIP_KIND_RECAP);
            case INTRO: return Setting.isIntroSkipKindEnabled(Setting.INTRO_SKIP_KIND_INTRO);
            case OUTRO: return Setting.isIntroSkipKindEnabled(Setting.INTRO_SKIP_KIND_OUTRO);
            case PREVIEW: return Setting.isIntroSkipKindEnabled(Setting.INTRO_SKIP_KIND_PREVIEW);
            default: return false;
        }
    }

    /**
     * 本段的 seek 落点，无可跳之处返回 -1。
     *
     * <p>尾部段多数「一直放到文件结束」，后面没有正片，seek 过去等于原地结束，
     * 这时返回 -1 让调用方按「本集看完」处理（跳下一集）。
     */
    private long skipTarget(Segment segment, long position, long duration) {
        if (segment.isOpenEnded()) return -1;
        long end = segment.getEndMs();
        if (end <= 0) return -1;
        if (duration > 0 && end > duration) end = duration;
        if (duration > 0 && end >= duration - TOLERANCE_MS) return -1;
        return end - position > MIN_SKIP_DELTA_MS ? end : -1;
    }

    /** 段落边界摘要。只打数量不打边界，出问题时无法分辨「没数据」和「有数据但被护栏拦了」。 */
    private String describe(IntroSkipPlan value) {
        StringBuilder text = new StringBuilder();
        for (Segment segment : value.getAll()) {
            if (text.length() > 0) text.append(',');
            text.append(segment.getKind()).append('[').append(segment.getStartMs()).append('-').append(segment.getEndMs());
            if (segment.isOpenEnded()) text.append("|open");
            text.append(']');
        }
        return text.length() == 0 ? "none" : text.toString();
    }

    private String id(Segment segment) {
        return segment.getKind() + "|" + segment.getProvider() + "|" + segment.getStartMs() + "|" + segment.getEndMs();
    }
}
