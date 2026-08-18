package com.fongmi.android.tv.player;

public final class PlaybackServiceReleasePolicy {

    public enum Action {
        DETACH,
        RESET_SESSION,
        SUSPEND_AND_RESET,
        SHUTDOWN
    }

    private PlaybackServiceReleasePolicy() {
    }

    public static Action decide(boolean transientPlayback, boolean owner, boolean keepAlive, boolean hasConsumer) {
        if (transientPlayback) return hasConsumer ? Action.RESET_SESSION : Action.SHUTDOWN;
        if (owner && keepAlive) return Action.RESET_SESSION;
        if (hasConsumer) return owner ? Action.SUSPEND_AND_RESET : Action.RESET_SESSION;
        return owner ? Action.SHUTDOWN : Action.DETACH;
    }
}
