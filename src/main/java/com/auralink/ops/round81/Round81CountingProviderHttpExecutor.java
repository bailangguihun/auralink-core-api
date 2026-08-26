package com.auralink.ops.round81;

import java.net.URI;

import org.springframework.web.client.RestClient;

import com.auralink.provider.http.ProviderHttpExecutor;
import com.auralink.provider.http.ProviderHttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Counts the single transport entry for each provider without retaining request data. */
final class Round81CountingProviderHttpExecutor extends ProviderHttpExecutor {

    private final Round81ProviderCallLedger ledger;

    Round81CountingProviderHttpExecutor(ObjectMapper objectMapper, Round81ProviderCallLedger ledger) {
        super(objectMapper);
        this.ledger = ledger;
    }

    @Override
    public ProviderHttpResponse postJson(
            RestClient client,
            URI endpoint,
            String bearerToken,
            String requestId,
            Object request,
            long maxRequestBytes,
            long maxResponseBytes) {
        ledger.record(endpoint);
        return super.postJson(
                client,
                endpoint,
                bearerToken,
                requestId,
                request,
                maxRequestBytes,
                maxResponseBytes);
    }
}
