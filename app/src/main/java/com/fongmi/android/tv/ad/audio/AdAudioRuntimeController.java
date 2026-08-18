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

        default AdAudioSignalProvider.SessionContext sessionContext(
                long sessionId, long generation) {
            return new AdAudioSignalProvider.SessionContext(
                    sessionId, generation, "session-" + sessionId, "", Map.of());
        }
    }

    @FunctionalInterface
    public interface ProbeProviderFactory {
        AdAudioSignalProvider create(ProbeRuleSidecar sidecar);
    }

    private static final int RUNTIME_CANDIDATE_CAPACITY = 1_024;

    private final PlaybackMediaSignalHub hub;
    private final PlaybackMediaClock clock;
    private final AdAudioRuleSource ruleSource;
    private final PlaybackPort playback;
    private final Executor worker;
    private final Runnable workerShutdown;
    private final ProbeProviderFactory probeProviderFactory;
    private final AdAudioDiagnostics diagnostics = new AdAudioDiagnostics();

    private AdAudioRuleSnapshot snapshot = new AdAudioRuleSnapshot(
            "local", "", AudioFingerprintRuleSet.empty(), java.util.List.of(), "");
    private AdSkipCoordinator.UiPort ui;
    private AdSkipCoordinator coordinator;
    private PcmAdAudioSignalProvider pcmProvider;
    private AdAudioSignalProvider probeProvider;
    private AdAudioDetectionMultiplexer multiplexer;
    private AdSkipPolicyController policy;
    private AdSkipPolicyController.Mode skipMode = AdSkipPolicyController.Mode.PROMPT;
    private boolean enabled;
    private boolean closed;

    public AdAudioRuntimeController(PlaybackMediaSignalHub hub, PlaybackMediaClock clock,
                                    AdAudioRuleSource ruleSource, PlaybackPort playback) {
        this(hub, clock, ruleSource, playback, createWorker());
    }

    private AdAudioRuntimeController(PlaybackMediaSignalHub hub, PlaybackMediaClock clock,
                                     AdAudioRuleSource ruleSource, PlaybackPort playback,
                                     Worker worker) {
        this(hub, clock, ruleSource, playback, worker.executor,
                worker.executor::shutdownNow, ignored ->
                        new NoopAdAudioSignalProvider("probe"));
    }

    AdAudioRuntimeController(PlaybackMediaSignalHub hub, PlaybackMediaClock clock,
                             AdAudioRuleSource ruleSource, PlaybackPort playback,
                             Executor worker, Runnable workerShutdown) {
        this(hub, clock, ruleSource, playback, worker, workerShutdown,
                ignored -> new NoopAdAudioSignalProvider("probe"));
    }

    AdAudioRuntimeController(PlaybackMediaSignalHub hub, PlaybackMediaClock clock,
                             AdAudioRuleSource ruleSource, PlaybackPort playback,
                             Executor worker, Runnable workerShutdown,
                             ProbeProviderFactory probeProviderFactory) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ruleSource = Objects.requireNonNull(ruleSource, "ruleSource");
        this.playback = Objects.requireNonNull(playback, "playback");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.workerShutdown = workerShutdown;
        this.probeProviderFactory = Objects.requireNonNull(
                probeProviderFactory, "probeProviderFactory");
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
        return isActiveLocked();
    }

    public synchronized AdAudioRuleSnapshot snapshot() {
        return snapshot;
    }

    public synchronized AdSkipPolicyController.Mode skipMode() {
        return skipMode;
    }

    public synchronized void setSkipMode(AdSkipPolicyController.Mode mode) {
        if (closed) return;
        skipMode = Objects.requireNonNull(mode, "mode");
        if (policy != null) policy.setMode(mode);
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
        if (multiplexer != null && isActiveLocked()) {
            publishHostPositionLocked(session);
            return;
        }
        activateLocked(session);
        publishHostPositionLocked(session);
    }

    private void activateLocked(PlaybackMediaSignalHub.Session session) {
        AdSkipCoordinator currentCoordinator = coordinator;
        if (currentCoordinator == null) return;
        AdAudioSignalProvider.SessionContext context;
        try {
            context = playback.sessionContext(session.id(), session.generation());
        } catch (RuntimeException e) {
            context = null;
        }
        if (context == null || context.sessionId() != session.id()
                || context.generation() != session.generation()) {
            diagnostics.record(AdAudioDiagnostics.Code.MATCHER_ERROR);
            return;
        }

        AdSkipPolicyController nextPolicy = new AdSkipPolicyController(
                context, snapshot.version(), RUNTIME_CANDIDATE_CAPACITY,
                currentCoordinator::onCandidate,
                currentCoordinator::onAutoCandidate);
        nextPolicy.setMode(skipMode);
        AdAudioDetectionMultiplexer[] muxHolder = new AdAudioDetectionMultiplexer[1];
        AdAudioSignalProvider.Listener output = new AdAudioSignalProvider.Listener() {
            @Override
            public void onCandidate(AdAudioSignalProvider.AdAudioCandidate candidate) {
                synchronized (AdAudioRuntimeController.this) {
                    if (multiplexer != muxHolder[0] || policy != nextPolicy
                            || coordinator != currentCoordinator) return;
                }
                nextPolicy.onCandidate(candidate);
            }

            @Override
            public void onProviderError(AdAudioSignalProvider.ProviderError error) {
                diagnostics.record(AdAudioDiagnostics.Code.MATCHER_ERROR);
            }

            @Override
            public void onTimelineReset(AdAudioSignalProvider.TimelineReset reset) {
                AdAudioSignalProvider currentProbe;
                synchronized (AdAudioRuntimeController.this) {
                    if (multiplexer != muxHolder[0] || policy != nextPolicy
                            || coordinator != currentCoordinator) return;
                    currentProbe = probeProvider;
                }
                nextPolicy.onTimelineReset(reset);
                currentCoordinator.onTimelineReset(new PlaybackMediaSignalHub.Lifecycle(
                        reset.sessionId(), reset.generation(),
                        PlaybackMediaSignalHub.ResetReason.valueOf(reset.reason().name()),
                        reset.mediaAnchorMs()));
                notifyTimelineReset(currentProbe, reset);
            }
        };
        AdAudioDetectionMultiplexer nextMux = new AdAudioDetectionMultiplexer(
                context, snapshot.version(), RUNTIME_CANDIDATE_CAPACITY, output);
        muxHolder[0] = nextMux;
        PcmAdAudioSignalProvider nextPcm = new PcmAdAudioSignalProvider(
                hub, worker, diagnostics);
        AdAudioSignalProvider nextProbe = createProbeProviderLocked();

        policy = nextPolicy;
        multiplexer = nextMux;
        pcmProvider = nextPcm;
        probeProvider = nextProbe;

        startProvider(nextPcm, true, context, nextMux);
        startProvider(nextProbe, snapshot.probeAvailable(), context, nextMux);
        if (nextPcm.state() != AdAudioSignalProvider.ProviderState.RUNNING) {
            closeProvider(nextPcm);
            if (pcmProvider == nextPcm) pcmProvider = null;
        }
    }

    private void deactivateLocked() {
        PcmAdAudioSignalProvider oldPcm = pcmProvider;
        AdAudioSignalProvider oldProbe = probeProvider;
        AdAudioDetectionMultiplexer oldMux = multiplexer;
        AdSkipPolicyController oldPolicy = policy;
        pcmProvider = null;
        probeProvider = null;
        multiplexer = null;
        policy = null;
        closeProvider(oldProbe);
        closeProvider(oldPcm);
        if (oldMux != null) oldMux.close();
        if (oldPolicy != null) oldPolicy.close();
    }

    private boolean isActiveLocked() {
        return isRunning(pcmProvider) || isRunning(probeProvider);
    }

    private void publishHostPositionLocked(PlaybackMediaSignalHub.Session session) {
        if (multiplexer == null) return;
        AdSkipCoordinator.PlaybackSnapshot playbackSnapshot;
        try {
            playbackSnapshot = playback.snapshot(session.id(), session.generation());
        } catch (RuntimeException e) {
            return;
        }
        if (playbackSnapshot == null
                || playbackSnapshot.sessionId() != session.id()
                || playbackSnapshot.generation() != session.generation()) return;
        AdAudioSignalProvider.HostPosition position =
                new AdAudioSignalProvider.HostPosition(
                        session.id(), session.generation(),
                        Math.max(0L, playbackSnapshot.positionMs()),
                        Math.max(-1L, playbackSnapshot.durationMs()),
                        playbackSnapshot.seekable(), playbackSnapshot.live());
        multiplexer.onHostPosition(position);
        notifyHostPosition(pcmProvider, position);
        notifyHostPosition(probeProvider, position);
    }

    private AdAudioSignalProvider createProbeProviderLocked() {
        if (!snapshot.probeAvailable()) return new NoopAdAudioSignalProvider("probe");
        try {
            AdAudioSignalProvider provider =
                    probeProviderFactory.create(snapshot.probeSidecar());
            return provider == null ? new NoopAdAudioSignalProvider("probe") : provider;
        } catch (RuntimeException e) {
            diagnostics.record(AdAudioDiagnostics.Code.MATCHER_ERROR);
            return new NoopAdAudioSignalProvider("probe");
        }
    }

    private void startProvider(AdAudioSignalProvider provider, boolean providerEnabled,
                               AdAudioSignalProvider.SessionContext context,
                               AdAudioSignalProvider.Listener listener) {
        try {
            provider.setEnabled(providerEnabled);
            provider.start(context, snapshot, listener);
        } catch (RuntimeException e) {
            diagnostics.record(AdAudioDiagnostics.Code.MATCHER_ERROR);
            closeProvider(provider);
        }
    }

    private static boolean isRunning(AdAudioSignalProvider provider) {
        return provider != null
                && provider.state() == AdAudioSignalProvider.ProviderState.RUNNING;
    }

    private static void notifyTimelineReset(
            AdAudioSignalProvider provider,
            AdAudioSignalProvider.TimelineReset reset) {
        if (provider == null) return;
        try {
            provider.onTimelineReset(reset);
        } catch (RuntimeException ignored) {
        }
    }

    private static void notifyHostPosition(
            AdAudioSignalProvider provider,
            AdAudioSignalProvider.HostPosition position) {
        if (provider == null) return;
        try {
            provider.onHostPosition(position);
        } catch (RuntimeException ignored) {
        }
    }

    private static void closeProvider(AdAudioSignalProvider provider) {
        if (provider == null) return;
        try {
            provider.close();
        } catch (RuntimeException ignored) {
        }
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
