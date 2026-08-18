package com.fongmi.android.tv.ad.audio;

import com.fongmi.android.tv.player.audio.PlaybackMediaClock;
import com.fongmi.android.tv.player.audio.PlaybackMediaSignalHub;

import java.util.Objects;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AdAudioRuntimeController implements AutoCloseable {

    public interface PlaybackPort extends AdSkipCoordinator.PlaybackPort {
        boolean isEligible(long sessionId, long generation);
    }

    private final PlaybackMediaSignalHub hub;
    private final PlaybackMediaClock clock;
    private final AdAudioRuleSource ruleSource;
    private final PlaybackPort playback;
    private final Executor worker;
    private final Runnable workerShutdown;
    private final AdAudioDiagnostics diagnostics = new AdAudioDiagnostics();

    private AdAudioRuleSnapshot snapshot = new AdAudioRuleSnapshot(
            "local", "", AudioFingerprintRuleSet.empty(), java.util.List.of(), "");
    private AdSkipCoordinator.UiPort ui;
    private AdSkipCoordinator coordinator;
    private PcmAdAudioSignalProvider pcmProvider;
    private boolean enabled;
    private boolean closed;

    public AdAudioRuntimeController(PlaybackMediaSignalHub hub, PlaybackMediaClock clock,
                                    AdAudioRuleSource ruleSource, PlaybackPort playback) {
        this(hub, clock, ruleSource, playback, createWorker());
    }

    private AdAudioRuntimeController(PlaybackMediaSignalHub hub, PlaybackMediaClock clock,
                                     AdAudioRuleSource ruleSource, PlaybackPort playback,
                                     Worker worker) {
        this(hub, clock, ruleSource, playback, worker.executor, worker.executor::shutdownNow);
    }

    AdAudioRuntimeController(PlaybackMediaSignalHub hub, PlaybackMediaClock clock,
                             AdAudioRuleSource ruleSource, PlaybackPort playback,
                             Executor worker, Runnable workerShutdown) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ruleSource = Objects.requireNonNull(ruleSource, "ruleSource");
        this.playback = Objects.requireNonNull(playback, "playback");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.workerShutdown = workerShutdown;
    }

    public synchronized void start(boolean enabled) {
        if (closed) return;
        this.enabled = enabled;
        reconfigureLocked();
    }

    public synchronized void reloadRules() {
        if (closed) return;
        reconfigureLocked();
    }

    private void reconfigureLocked() {
        loadRulesLocked();
        deactivateLocked();
        PlaybackMediaSignalHub.Session session = hub.session();
        if (coordinator != null) coordinator.reset(session.id());
        refreshLocked();
    }

    public synchronized void bindUi(AdSkipCoordinator.UiPort ui) {
        if (closed) return;
        if (coordinator != null) coordinator.close();
        this.ui = Objects.requireNonNull(ui, "ui");
        this.coordinator = new AdSkipCoordinator(playback, ui, 5_000L, diagnostics);
        refreshLocked();
    }

    public synchronized void unbindUi() {
        if (coordinator != null) coordinator.close();
        coordinator = null;
        ui = null;
        deactivateLocked();
    }

    public synchronized void refresh() {
        if (closed) return;
        refreshLocked();
    }

    public synchronized void suspend() {
        if (closed) return;
        deactivateLocked();
        PlaybackMediaSignalHub.Session session = hub.session();
        if (coordinator != null) coordinator.reset(session.id());
    }

    public synchronized boolean needsPipelineRebuild() {
        return pcmProvider != null && pcmProvider.state()
                == AdAudioSignalProvider.ProviderState.RUNNING
                && !hub.isPipelineAttached();
    }

    public synchronized boolean isActive() {
        return pcmProvider != null
                && pcmProvider.state() == AdAudioSignalProvider.ProviderState.RUNNING;
    }

    public synchronized AdAudioRuleSnapshot snapshot() {
        return snapshot;
    }

    public AdAudioDiagnostics.Snapshot diagnostics() {
        return diagnostics.snapshot();
    }

    public synchronized void stop() {
        if (closed) return;
        enabled = false;
        deactivateLocked();
        if (coordinator != null) coordinator.close();
        coordinator = null;
        ui = null;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        deactivateLocked();
        if (coordinator != null) coordinator.close();
        coordinator = null;
        ui = null;
        if (workerShutdown != null) workerShutdown.run();
    }

    private void loadRulesLocked() {
        try {
            AdAudioRuleSnapshot loaded = ruleSource.load();
            if (loaded == null) {
                diagnostics.record(AdAudioDiagnostics.Code.RULE_LOAD_FAILED);
                snapshot = new AdAudioRuleSnapshot(
                        "local", "", AudioFingerprintRuleSet.empty(), java.util.List.of(), "RULE_LOAD_FAILED");
            } else {
                snapshot = loaded;
                if (loaded.hasError()) diagnostics.record(AdAudioDiagnostics.Code.RULE_LOAD_FAILED);
            }
        } catch (RuntimeException e) {
            diagnostics.record(AdAudioDiagnostics.Code.RULE_LOAD_FAILED);
            snapshot = new AdAudioRuleSnapshot(
                    "local", "", AudioFingerprintRuleSet.empty(), java.util.List.of(), "RULE_LOAD_FAILED");
        }
    }

    private void refreshLocked() {
        if (!enabled || ui == null || snapshot.hasError() || !snapshot.hasRules()) {
            deactivateLocked();
            return;
        }
        PlaybackMediaSignalHub.Session session = hub.session();
        if (!playback.isEligible(session.id(), session.generation())) {
            deactivateLocked();
            return;
        }
        if (pcmProvider != null
                && pcmProvider.state() == AdAudioSignalProvider.ProviderState.RUNNING) return;
        activateLocked(session);
    }

    private void activateLocked(PlaybackMediaSignalHub.Session session) {
        AdSkipCoordinator currentCoordinator = coordinator;
        if (currentCoordinator == null) return;
        PcmAdAudioSignalProvider[] holder = new PcmAdAudioSignalProvider[1];
        PcmAdAudioSignalProvider nextProvider = new PcmAdAudioSignalProvider(
                hub, worker, diagnostics);
        holder[0] = nextProvider;
        AdAudioSignalProvider.Listener listener = new AdAudioSignalProvider.Listener() {
            @Override
            public void onCandidate(AdAudioSignalProvider.AdAudioCandidate candidate) {
                synchronized (AdAudioRuntimeController.this) {
                    if (pcmProvider != holder[0] || coordinator != currentCoordinator) return;
                }
                currentCoordinator.onCandidate(toLegacyCandidate(candidate));
            }

            @Override
            public void onProviderError(AdAudioSignalProvider.ProviderError error) {
                diagnostics.record(AdAudioDiagnostics.Code.MATCHER_ERROR);
            }

            @Override
            public void onTimelineReset(AdAudioSignalProvider.TimelineReset reset) {
                currentCoordinator.onTimelineReset(new PlaybackMediaSignalHub.Lifecycle(
                        reset.sessionId(), reset.generation(),
                        PlaybackMediaSignalHub.ResetReason.valueOf(reset.reason().name()),
                        reset.mediaAnchorMs()));
            }
        };
        nextProvider.setEnabled(true);
        nextProvider.start(new AdAudioSignalProvider.SessionContext(
                session.id(), session.generation(),
                "session-" + session.id(), "", Map.of()), snapshot, listener);
        if (nextProvider.state() == AdAudioSignalProvider.ProviderState.RUNNING) {
            pcmProvider = nextProvider;
        } else {
            nextProvider.close();
        }
    }

    private void deactivateLocked() {
        if (pcmProvider != null) {
            pcmProvider.close();
            pcmProvider = null;
        }
    }

    private static AdAudioConsumer.Candidate toLegacyCandidate(
            AdAudioSignalProvider.AdAudioCandidate candidate) {
        AudioFingerprintMatcher.MatchEvent event = new AudioFingerprintMatcher.MatchEvent(
                candidate.fullMatch() ? AudioFingerprintMatcher.Type.FULL_MATCHED
                        : AudioFingerprintMatcher.Type.START_MATCHED,
                candidate.ruleId(), candidate.startMs(), candidate.endMs(),
                (float) candidate.similarity(), 0);
        return new AdAudioConsumer.Candidate(candidate.sessionId(), candidate.generation(), event);
    }

    private static Worker createWorker() {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ad-audio-matcher");
            thread.setDaemon(true);
            return thread;
        });
        return new Worker(executor);
    }

    private record Worker(ExecutorService executor) {
    }
}
