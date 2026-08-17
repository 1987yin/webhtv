package com.fongmi.android.tv.player;

import java.util.OptionalLong;

public final class TransientPlaybackCoordinator {

    private boolean launchActive;
    private TransientPlaybackSnapshot launchSnapshot;
    private TransientPlaybackSnapshot queuedRestore;
    private TransientPlaybackSnapshot inFlightRestore;

    public boolean canBeginLaunch() {
        return !launchActive && queuedRestore == null && inFlightRestore == null;
    }

    public boolean beginLaunch(TransientPlaybackSnapshot snapshot) {
        return beginLaunch(snapshot, false);
    }

    public boolean beginLaunch(TransientPlaybackSnapshot snapshot, boolean hasExistingSession) {
        if (!canBeginLaunch()) return false;
        if (hasExistingSession && (snapshot == null || !snapshot.isRestorable())) return false;
        launchActive = true;
        launchSnapshot = snapshot;
        return true;
    }

    public TransientPlaybackSnapshot cancelLaunch() {
        if (!launchActive) return null;
        TransientPlaybackSnapshot snapshot = launchSnapshot;
        launchActive = false;
        launchSnapshot = null;
        return snapshot;
    }

    public void queueRestoreAfterResult() {
        if (!launchActive) return;
        queuedRestore = launchSnapshot != null && launchSnapshot.isRestorable() ? launchSnapshot : null;
        launchActive = false;
        launchSnapshot = null;
    }

    public TransientPlaybackSnapshot beginRestore() {
        if (queuedRestore == null || inFlightRestore != null) return null;
        inFlightRestore = queuedRestore;
        queuedRestore = null;
        return inFlightRestore;
    }

    public void requeueInFlightRestore() {
        if (inFlightRestore == null) return;
        if (queuedRestore == null) queuedRestore = inFlightRestore;
        inFlightRestore = null;
    }

    public void failRestore() {
        queuedRestore = null;
        inFlightRestore = null;
    }

    public OptionalLong consumePreparedPosition(String key, String realUrl) {
        if (inFlightRestore == null) return OptionalLong.empty();
        if (!inFlightRestore.key().equals(key)) return OptionalLong.empty();
        if (!inFlightRestore.result().getRealUrl().equals(realUrl)) return OptionalLong.empty();
        long positionMs = inFlightRestore.positionMs();
        inFlightRestore = null;
        return OptionalLong.of(positionMs);
    }

    public boolean isLaunchActive() {
        return launchActive;
    }

    public boolean hasQueuedRestore() {
        return queuedRestore != null;
    }

    public boolean hasInFlightRestore() {
        return inFlightRestore != null;
    }

    public void clear() {
        launchActive = false;
        launchSnapshot = null;
        queuedRestore = null;
        inFlightRestore = null;
    }
}
