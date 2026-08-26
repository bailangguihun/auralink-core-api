package com.auralink.provider.validation;

import java.time.Duration;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;

/** Shared non-secret bounds checked by readiness and before provider submission. */
public final class CreationProviderConfigurationChecks {

    private CreationProviderConfigurationChecks() {
    }

    public static void requireSeedream(CreationProviderProperties properties) {
        requireCommon(properties);
        requirePositive(properties.getSeedreamReadTimeout());
        if (properties.getMaxImageOutputBytes() < 1
                || properties.getMaxConcurrentSeedream() < 1) {
            throw invalid();
        }
    }

    public static void requireQwen(CreationProviderProperties properties) {
        requireCommon(properties);
        requirePositive(properties.getQwenReadTimeout());
        if (properties.getMaxConcurrentQwen() < 1) {
            throw invalid();
        }
    }

    public static void requireVmm(CreationProviderProperties properties) {
        requireCommon(properties);
        requirePositive(properties.getVmmReadTimeout());
        if (properties.getMaxAudioOutputBytes() < 1
                || properties.getMaxConcurrentVmm() < 1) {
            throw invalid();
        }
    }

    private static void requireCommon(CreationProviderProperties properties) {
        if (properties == null
                || properties.getStagingDir() == null
                || !properties.getStagingDir().isAbsolute()
                || properties.getMaxImageInputBytes() < 1
                || properties.getMaxTextChars() < 1) {
            throw invalid();
        }
        requirePositive(properties.getConnectTimeout());
    }

    private static void requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw invalid();
        }
    }

    private static ProviderExecutionException invalid() {
        return new ProviderExecutionException(
                ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID,
                "Creation provider bounds are invalid");
    }
}
