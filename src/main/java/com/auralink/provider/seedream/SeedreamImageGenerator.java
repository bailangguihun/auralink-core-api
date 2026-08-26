package com.auralink.provider.seedream;

import org.springframework.stereotype.Component;

import com.auralink.provider.ProviderBulkheadKind;
import com.auralink.provider.ProviderBulkheads;
import com.auralink.provider.artifact.ProviderArtifact;

import lombok.RequiredArgsConstructor;

/** Shared one-call Seedream stage used by direct and composite adapters. */
@Component
@RequiredArgsConstructor
public class SeedreamImageGenerator {

    private final SeedreamHttpClient httpClient;
    private final SeedreamResultFetcher resultFetcher;
    private final ProviderBulkheads bulkheads;

    public void prepare() {
        resultFetcher.prepare();
    }

    public ProviderArtifact generate(String requestId, String prompt, String imageDataUrl) {
        prepare();
        return bulkheads.execute(
                ProviderBulkheadKind.SEEDREAM,
                () -> {
                    String resultUrl = httpClient.generate(requestId, prompt, imageDataUrl);
                    return resultFetcher.fetch(resultUrl);
                });
    }
}
