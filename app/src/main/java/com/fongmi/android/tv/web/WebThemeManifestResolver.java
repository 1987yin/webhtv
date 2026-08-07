package com.fongmi.android.tv.web;

import android.content.Context;

import java.io.IOException;

/** Resolves a validated Manifest page into the concrete target consumed by the page host. */
final class WebThemeManifestResolver {

    record Resolution(WebHomeTarget target, WebThemeManifestLoader.CacheState cacheState,
            IOException refreshFailure) {
        boolean usedLastKnownGood() {
            return cacheState == WebThemeManifestLoader.CacheState.LAST_KNOWN_GOOD;
        }
    }

    private final Context context;
    private final String platformTarget;

    WebThemeManifestResolver(Context context, String platformTarget) {
        this.context = context;
        this.platformTarget = platformTarget;
    }

    Resolution resolvePageResult(WebHomeTarget configured, WebThemePage page, boolean force) throws IOException {
        if (configured == null || !configured.isManifest() || page == null) return null;
        WebThemeManifestLoader.LoadResult loaded = WebThemeManifestLoader.loadResult(
                context, configured.getUrl(), platformTarget, force);
        WebHomeTarget target = WebHomeTarget.forManifestPage(configured, loaded.manifest(), page);
        return new Resolution(target, loaded.state(), loaded.refreshFailure());
    }

    WebHomeTarget resolvePage(WebHomeTarget configured, WebThemePage page, boolean force) throws IOException {
        Resolution resolved = resolvePageResult(configured, page, force);
        return resolved == null ? null : resolved.target();
    }
}
