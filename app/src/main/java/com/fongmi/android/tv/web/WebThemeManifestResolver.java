package com.fongmi.android.tv.web;

import android.content.Context;

import java.io.IOException;

/** Resolves a validated Manifest page into the concrete target consumed by the page host. */
final class WebThemeManifestResolver {

    private final Context context;
    private final String platformTarget;

    WebThemeManifestResolver(Context context, String platformTarget) {
        this.context = context;
        this.platformTarget = platformTarget;
    }

    WebHomeTarget resolvePage(WebHomeTarget configured, WebThemePage page, boolean force) throws IOException {
        if (configured == null || !configured.isManifest() || page == null) return null;
        WebThemeManifest manifest = WebThemeManifestLoader.load(context, configured.getUrl(), platformTarget, force);
        return WebHomeTarget.forManifestPage(configured, manifest, page);
    }
}
