package com.auralink.provider.vmm;

import java.net.URI;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.http.ProviderHttpExecutor;
import com.auralink.provider.http.ProviderHttpResponse;
import com.auralink.provider.validation.StrictProviderJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict current VMM JSON transport; absolute response paths are never read. */
@Component
public class VmmHttpClient {

    private static final long MAX_RESPONSE_BYTES = 64L * 1024L;
    public static final int INHERITED_DURATION_SECONDS = 30;

    private final RestClient restClient;
    private final ProviderHttpExecutor httpExecutor;
    private final ObjectMapper objectMapper;
    private final CreationProviderProperties properties;
    private final VmmEndpointResolver endpointResolver;

    public VmmHttpClient(
            @Qualifier("vmmProviderRestClient") RestClient restClient,
            ProviderHttpExecutor httpExecutor,
            ObjectMapper objectMapper,
            CreationProviderProperties properties,
            VmmEndpointResolver endpointResolver) {
        this.restClient = restClient;
        this.httpExecutor = httpExecutor;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.endpointResolver = endpointResolver;
    }

    public String generate(String requestId, String imageDataUrl) {
        URI endpoint = endpointResolver.resolveGenerationEndpoint();
        long maxRequestBytes = Math.multiplyExact(properties.getMaxImageInputBytes(), 2L);
        ProviderHttpResponse response = httpExecutor.postJson(
                restClient,
                endpoint,
                null,
                requestId,
                new VmmGenerationRequest(imageDataUrl, INHERITED_DURATION_SECONDS),
                maxRequestBytes,
                MAX_RESPONSE_BYTES);
        return parseFileName(response.body());
    }

    private String parseFileName(byte[] responseBody) {
        final JsonNode root;
        try {
            root = StrictProviderJson.parse(objectMapper, responseBody);
        } catch (Exception exception) {
            throw invalid("VMM returned malformed JSON", exception);
        }
        if (root == null || !root.isObject()) {
            throw invalid("VMM response is not an object", null);
        }
        JsonNode success = root.get("success");
        if (success != null && success.isBoolean() && !success.booleanValue()) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_REJECTED,
                    "VMM rejected the generation request");
        }
        if (success == null || !success.isBoolean() || !success.booleanValue()) {
            throw invalid("VMM response success marker is missing", null);
        }
        JsonNode fileName = root.get("fileName");
        if (fileName == null || !fileName.isTextual() || fileName.textValue().isBlank()) {
            throw invalid("VMM response file name is missing", null);
        }
        return fileName.textValue().trim();
    }

    private ProviderExecutionException invalid(String message, Throwable cause) {
        return cause == null
                ? new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_INVALID_RESPONSE, message)
                : new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_INVALID_RESPONSE, message, cause);
    }
}
