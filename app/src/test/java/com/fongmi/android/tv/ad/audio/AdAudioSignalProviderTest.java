package com.fongmi.android.tv.ad.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AdAudioSignalProviderTest {

    @Test
    public void sessionContextDefensivelyCopiesHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", "https://media.example/");

        AdAudioSignalProvider.SessionContext context =
                new AdAudioSignalProvider.SessionContext(
                        7L, 2L, "media-1", "https://media.example/video.m3u8", headers);
        headers.put("Authorization", "late mutation");

        assertEquals(Map.of("Referer", "https://media.example/"), context.headers());
        assertThrows(UnsupportedOperationException.class,
                () -> context.headers().put("X-Test", "value"));
    }

    @Test
    public void boundaryValuesRejectInvalidTimelineAndCandidateData() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdAudioSignalProvider.SessionContext(
                        -1L, 0L, "media", "https://media.example/video", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AdAudioSignalProvider.HostPosition(
                        1L, 0L, -1L, 10_000L, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> new AdAudioSignalProvider.AdAudioCandidate(
                        1L, 0L, "rule", "v1", 2_000L, 1_000L,
                        true, 1.0d, "pcm"));
        assertThrows(IllegalArgumentException.class,
                () -> new AdAudioSignalProvider.AdAudioCandidate(
                        1L, 0L, "rule", "v1", 1_000L, 2_000L,
                        true, Double.NaN, "pcm"));
    }

    @Test
    public void noopProviderHasNoResourcesOrCallbacksAndClosesIdempotently() {
        NoopAdAudioSignalProvider provider = new NoopAdAudioSignalProvider("probe");
        AtomicInteger callbacks = new AtomicInteger();
        AdAudioSignalProvider.Listener listener = new AdAudioSignalProvider.Listener() {
            @Override
            public void onCandidate(AdAudioSignalProvider.AdAudioCandidate candidate) {
                callbacks.incrementAndGet();
            }

            @Override
            public void onProviderError(AdAudioSignalProvider.ProviderError error) {
                callbacks.incrementAndGet();
            }
        };

        assertEquals("probe", provider.id());
        assertEquals(AdAudioSignalProvider.ProviderState.DISABLED, provider.state());
        provider.setEnabled(true);
        assertEquals(AdAudioSignalProvider.ProviderState.IDLE, provider.state());
        provider.start(context(7L, 2L), snapshot(), listener);
        provider.onHostPosition(new AdAudioSignalProvider.HostPosition(
                6L, 1L, 1_000L, 10_000L, true, false));
        provider.onTimelineReset(new AdAudioSignalProvider.TimelineReset(
                6L, 1L, AdAudioSignalProvider.ResetReason.SEEK, 2_000L));

        assertEquals(0, callbacks.get());
        assertEquals(AdAudioSignalProvider.ProviderState.IDLE, provider.state());

        provider.close();
        provider.close();
        provider.setEnabled(true);
        provider.start(context(8L, 0L), snapshot(), listener);

        assertEquals(0, callbacks.get());
        assertEquals(AdAudioSignalProvider.ProviderState.CLOSED, provider.state());
    }

    @Test
    public void disablingNoopReturnsItToDisabledState() {
        NoopAdAudioSignalProvider provider = new NoopAdAudioSignalProvider("probe");

        provider.setEnabled(true);
        provider.setEnabled(false);

        assertEquals(AdAudioSignalProvider.ProviderState.DISABLED, provider.state());
    }

    private static AdAudioSignalProvider.SessionContext context(long sessionId, long generation) {
        return new AdAudioSignalProvider.SessionContext(
                sessionId, generation, "media", "https://media.example/video.m3u8", Map.of());
    }

    private static AdAudioRuleSnapshot snapshot() {
        return new AdAudioRuleSnapshot(
                "test", "v1", AudioFingerprintRuleSet.empty(), List.of(), "");
    }
}
