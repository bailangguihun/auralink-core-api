package com.auralink.provider.seedream;

import org.springframework.stereotype.Component;

import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.service.SafeRemoteResourceFetcher;

import lombok.RequiredArgsConstructor;

/** Uses the hardened DNS-pinned redirect-safe fetcher without forwarding headers. */
@Component
@RequiredArgsConstructor
public class SafeSeedreamResultFetcher implements SeedreamResultFetcher {

    private final SafeRemoteResourceFetcher remoteResourceFetcher;
    private final ProviderArtifactStagingService stagingService;

    @Override
    public void prepare() {
        stagingService.prepare();
    }

    @Override
    public ProviderArtifact fetch(String resultUrl) {
        try {
            return stagingService.stageOutputImage(
                    target -> remoteResourceFetcher.fetchTo(resultUrl, target));
        } catch (ProviderExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                    "Seedream image result was rejected",
                    exception);
        }
    }
}
