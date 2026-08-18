package com.fongmi.android.tv.ad.audio;

import com.fongmi.android.tv.player.audio.PlaybackMediaClock;
import com.fongmi.android.tv.player.audio.PlaybackMediaSignalHub;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    @FunctionalInterface
    public interface SpeechProviderFactory {
        AdAudioSignalProvider create();
    }

    private static final int RUNTIME_CANDIDATE_CAPACITY = 1_024;

    private final PlaybackMediaSignalHub hub;
    private final PlaybackMediaClock clock;
    private final AdAudioRuleSource ruleSource;
    private final PlaybackPort playback;
    private final Executor worker;
    private final Runnable workerShutdown;
    private final ProbeProviderFactory probeProviderFactory;
    private final SpeechProviderFactory speechProviderFactory;
    private final AdAudioDiagnostics diagnostics = new AdAudioDiagnostics();

    private AdAudioRuleSnapshot snapshot = new AdAudioRuleSnapshot(
            "local", "", AudioFingerprintRuleSet.empty(), java.util.List.of(), "");
    private AdSkipCoordinator.UiPort ui;
    private AdSkipCoordinator coordinator;
    private PcmAdAudioSignalProvider pcmProvider;
    private AdAudioSignalProvider probeProvider;
    private AdAudioSignalProvider speechProvider;
    private AdAudioDetectionMultiplexer multiplexer;
    private AdSkipPolicyController policy;
    private AdSkipPolicyController.Mode skipMode = AdSkipPolicyController.Mode.PROMPT;
    private SpeechAdConfig speechConfig = SpeechAdConfig.defaults();
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
                worker.executor::shutdownNow,
                ignored -> new NoopAdAudioSignalProvider("probe"),
                () -> new NoopAdAudioSignalProvider(SpeechAdSignalProvider.ID));
    }

    AdAudioRuntimeController(PlaybackMediaSignalHub hub, PlaybackMediaClock clock,
                             AdAudioRuleSource ruleSource, PlaybackPort playback,
                             Executor worker, Runnable workerShutdown) {
        this(hub, clock, ruleSource, playback, worker, workerShutdown,
                ignored -> new NoopAdAudioSignalProvider("probe"),
                () -> new NoopAdAudioSignalProvider(SpeechAdSignalProvider.ID));
    }

    AdAudioRuntimeController(PlaybackMediaSignalHub hub, PlaybackMediaClock clock,
                             AdAudioRuleSource ruleSource, PlaybackPort playback,
                             Executor worker, Runnable workerShutdown,
                             ProbeProviderFactory probeProviderFactory) {
        this(hub, clock, ruleSource, playback, worker, workerShutdown,
                probeProviderFactory,
                () -> new NoopAdAudioSignalProvider(SpeechAdSignalProvider.ID));
    }

    AdAudioRuntimeController(PlaybackMediaSignalHub hub, PlaybackMediaClock clock,
                             AdAudioRuleSource ruleSource, PlaybackPort playback,
                             Executor worker, Runnable workerShutdown,
                             ProbeProviderFactory probeProviderFactory,
                             SpeechProviderFactory speechProviderFactory) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ruleSource = Objects.requireNonNull(ruleSource, "ruleSource");
        this.playback = Objects.requireNonNull(playback, "playback");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.workerShutdown = workerShutdown;
        this.probeProviderFactory = Objects.requireNonNull(
                probeProviderFactory, "probeProviderFactory");
        this.speechProviderFactory = Objects.requireNonNull(
                speechProviderFactory, "speechProviderFactory");
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
        return hub.isCaptureRequested(PlaybackMediaSignalHub.ConsumerKind.AD_AUDIO)
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
        if (policy != null) installModeResolver(policy);
    }

    public synchronized void setSpeechConfig(SpeechAdConfig config) {
        if (closed) return;
        SpeechAdConfig next = Objects.requireNonNull(config, "config");
        boolean rebuild = !next.equals(speechConfig);
        speechConfig = next;
        if (policy != null) installModeResolver(policy);
        if (rebuild) reconfigureLocked();
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
        boolean fingerprintReady = enabled && !snapshot.hasError() && snapshot.hasRules();
        boolean speechReady = speechConfig.enabled() && !speechConfig.keywords().isEmpty();
        if (ui == null || (!fingerprintReady && !speechReady)) {
            deactivateLocked();
            return;
        }
        PlaybackMediaSignalHub.Session session = hub.session();
        if (!playback.isEligible(session.id(), session.generation())) {
            deactivateLocked();
            return;
        }
        if (multiplexer != null) {
            if (isActiveLocked()) {
                publishHostPositionLocked(session);
                return;
            }
            deactivateLocked();
        }
        activateLocked(session, fingerprintReady, speechReady);
        publishHostPositionLocked(session);
    }

    private void activateLocked(PlaybackMediaSignalHub.Session session,
                                boolean fingerprintReady, boolean speechReady) {
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

        AdAudioRuleSnapshot routingSnapshot = routingSnapshotLocked();
        AdSkipPolicyController nextPolicy = new AdSkipPolicyController(
                context, routingSnapshot.version(), RUNTIME_CANDIDATE_CAPACITY,
                currentCoordinator::onCandidate,
                currentCoordinator::onAutoCandidate);
        installModeResolver(nextPolicy);
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
                if (error != null && !PcmAdAudioSignalProvider.ID.equals(error.providerId())) {
                    diagnostics.record(AdAudioDiagnostics.Code.MATCHER_ERROR);
                }
            }

            @Override
            public void onTimelineReset(AdAudioSignalProvider.TimelineReset reset) {
                PcmAdAudioSignalProvider currentPcm;
                AdAudioSignalProvider currentProbe;
                AdAudioSignalProvider currentSpeech;
                synchronized (AdAudioRuntimeController.this) {
                    if (multiplexer != muxHolder[0] || policy != nextPolicy
                            || coordinator != currentCoordinator) return;
                    currentPcm = pcmProvider;
                    currentProbe = probeProvider;
                    currentSpeech = speechProvider;
                }
                nextPolicy.onTimelineReset(reset);
                currentCoordinator.onTimelineReset(new PlaybackMediaSignalHub.Lifecycle(
                        reset.sessionId(), reset.generation(),
                        PlaybackMediaSignalHub.ResetReason.valueOf(reset.reason().name()),
                        reset.mediaAnchorMs()));
                notifyTimelineReset(currentPcm, reset);
                notifyTimelineReset(currentProbe, reset);
                notifyTimelineReset(currentSpeech, reset);
            }
        };
        Set<String> allowedRuleIds = new HashSet<>();
        if (fingerprintReady) {
            snapshot.ruleSet().rules().stream()
                    .map(AudioFingerprintRule::id)
                    .forEach(allowedRuleIds::add);
        }
        if (speechReady) allowedRuleIds.add(SpeechAdSignalProvider.RULE_ID);
        AdAudioDetectionMultiplexer nextMux = new AdAudioDetectionMultiplexer(
                context, routingSnapshot.version(), Set.copyOf(allowedRuleIds),
                RUNTIME_CANDIDATE_CAPACITY, output);
        muxHolder[0] = nextMux;
        PcmAdAudioSignalProvider nextPcm = fingerprintReady
                ? new PcmAdAudioSignalProvider(hub, worker, diagnostics) : null;
        AdAudioSignalProvider nextProbe = fingerprintReady
                ? createProbeProviderLocked() : null;
        AdAudioSignalProvider nextSpeech = speechReady
                ? createSpeechProviderLocked() : null;

        policy = nextPolicy;
        multiplexer = nextMux;
        pcmProvider = nextPcm;
        probeProvider = nextProbe;
        speechProvider = nextSpeech;

        startProvider(nextPcm, true, context, routingSnapshot, nextMux);
        startProvider(nextProbe, snapshot.probeAvailable(), context, routingSnapshot, nextMux);
        startProvider(nextSpeech, true, context, routingSnapshot, nextMux);
        if (nextPcm != null && !isRunning(nextPcm)) {
            closeProvider(nextPcm);
            if (pcmProvider == nextPcm) pcmProvider = null;
        }
        if (nextProbe != null && !isRunning(nextProbe)) {
            closeProvider(nextProbe);
            if (probeProvider == nextProbe) probeProvider = null;
        }
        if (nextSpeech != null && !isRunning(nextSpeech)) {
            closeProvider(nextSpeech);
            if (speechProvider == nextSpeech) speechProvider = null;
        }
    }
    private void deactivateLocked() {
        PcmAdAudioSignalProvider oldPcm = pcmProvider;
        AdAudioSignalProvider oldProbe = probeProvider;
        AdAudioSignalProvider oldSpeech = speechProvider;
        AdAudioDetectionMultiplexer oldMux = multiplexer;
        AdSkipPolicyController oldPolicy = policy;
        pcmProvider = null;
        probeProvider = null;
        speechProvider = null;
        multiplexer = null;
        policy = null;
        closeProvider(oldSpeech);
        closeProvider(oldProbe);
        closeProvider(oldPcm);
        if (oldMux != null) oldMux.close();
        if (oldPolicy != null) oldPolicy.close();
    }

    private boolean isActiveLocked() {
        return isRunning(pcmProvider) || isRunning(probeProvider)
                || isRunning(speechProvider);
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
        notifyHostPosition(speechProvider, position);
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
                               AdAudioRuleSnapshot routingSnapshot,
                               AdAudioSignalProvider.Listener listener) {
        if (provider == null) return;
        try {
            provider.setEnabled(providerEnabled);
            provider.start(context, routingSnapshot, listener);
        } catch (RuntimeException e) {
            diagnostics.record(AdAudioDiagnostics.Code.MATCHER_ERROR);
            closeProvider(provider);
        }
    }

    private AdAudioSignalProvider createSpeechProviderLocked() {
        try {
            AdAudioSignalProvider provider = speechProviderFactory.create();
            return provider == null
                    ? new NoopAdAudioSignalProvider(SpeechAdSignalProvider.ID)
                    : provider;
        } catch (RuntimeException e) {
            diagnostics.record(AdAudioDiagnostics.Code.MATCHER_ERROR);
            return new NoopAdAudioSignalProvider(SpeechAdSignalProvider.ID);
        }
    }

    private AdAudioRuleSnapshot routingSnapshotLocked() {
        if (!snapshot.version().isEmpty()) return snapshot;
        return new AdAudioRuleSnapshot(
                snapshot.sourceId(), "speech-runtime-v1", snapshot.ruleSet(),
                snapshot.warnings(), snapshot.lastError(), snapshot.probeSidecar());
    }

    private void installModeResolver(AdSkipPolicyController target) {
        target.setMode(skipMode);
        target.setModeResolver(providerId -> SpeechAdSignalProvider.ID.equals(providerId)
                ? speechConfig.mode() : skipMode);
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
