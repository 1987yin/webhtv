package com.fongmi.android.tv.ad.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class SignedRulePackageSource implements AdAudioRuleSource {

    private static final String SOURCE_PREFIX = "signed:";
    private static final String CACHE_RECOVERED = "CACHE_RECOVERED";

    private final String sourceId;
    private final Supplier<SignedRulePackageStore.LoadResult> loader;

    public SignedRulePackageSource(SignedRulePackageStore store) {
        this(requireStore(store).packageId(), store::load);
    }

    SignedRulePackageSource(
            String packageId, Supplier<SignedRulePackageStore.LoadResult> loader) {
        if (packageId == null || packageId.isEmpty() || loader == null) {
            throw new IllegalArgumentException("package id and loader are required");
        }
        this.sourceId = SOURCE_PREFIX + packageId;
        this.loader = loader;
    }

    @Override
    public AdAudioRuleSnapshot load() {
        SignedRulePackageStore.LoadResult loaded = loader.get();
        if (!loaded.hasPackage()) {
            SignedRulePackageException.Code error = loaded.errorCode();
            String errorCode = error == null
                    ? SignedRulePackageException.Code.NO_VALID_SIGNED_PACKAGE.name()
                    : error.name();
            return new AdAudioRuleSnapshot(
                    sourceId, "", AudioFingerprintRuleSet.empty(), List.of(), errorCode);
        }

        VerifiedRulePackage verified = loaded.verifiedPackage();
        List<String> warnings = new ArrayList<>(loaded.warnings());
        if (loaded.recoveredPrevious() && !warnings.contains(CACHE_RECOVERED)) {
            warnings.add(CACHE_RECOVERED);
        }
        String version = verified.revision() + ":" + verified.payloadSha256().substring(0, 16);
        return new AdAudioRuleSnapshot(
                sourceId, version, verified.ruleSet(), warnings, "");
    }

    private static SignedRulePackageStore requireStore(SignedRulePackageStore store) {
        if (store == null) throw new IllegalArgumentException("store is required");
        return store;
    }
}
