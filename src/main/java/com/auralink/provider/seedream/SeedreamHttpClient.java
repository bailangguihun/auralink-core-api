package com.auralink.provider.seedream;

import java.net.URI;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.ProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.http.ProviderHttpExecutor;
import com.auralink.provider.http.ProviderHttpResponse;
import com.auralink.provider.validation.StrictProviderJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict Ark JSON transport with no retry and no response-body logging. */
@Component
public class SeedreamHttpClient {

    private static final long MAX_RESPONSE_BYTES = 1024L * 1024L;

    private final RestClient restClient;
    private final ProviderHttpExecutor httpExecutor;
    private final ObjectMapper objectMapper;
    private final CreationProviderProperties creationProperties;
    private final ProviderProperties.Provider provider;
    private final SeedreamEndpointResolver endpointResolver;

    public SeedreamHttpClient(
            @Qualifier("seedreamProviderRestClient") RestClient restClient,
            ProviderHttpExecutor httpExecutor,
            ObjectMapper objectMapper,
            CreationProviderProperties creationProperties,
            ProviderProperties providerProperties,
            SeedreamEndpointResolver endpointResolver) {
        this.restClient = restClient;
        this.httpExecutor = httpExecutor;
        this.objectMapper = objectMapper;
        this.creationProperties = creationProperties;
        this.provider = providerProperties.getSeedream();
        this.endpointResolver = endpointResolver;
    }

    public String generate(String requestId, String prompt, String imageDataUrl) {
        URI endpoint = endpointResolver.resolveGenerationEndpoint();
        SeedreamGenerationRequest request = imageDataUrl == null
                ? SeedreamGenerationRequest.text(
                        provider.getModel().trim(),
                        prompt,
                        creationProperties.getSeedreamDefaultSize(),
                        creationProperties.isSeedreamWatermark())
                : SeedreamGenerationRequest.image(
                        provider.getModel().trim(),
                        prompt,
                        imageDataUrl,
                        creationProperties.getSeedreamDefaultSize(),
                        creationProperties.isSeedreamWatermark());

        long maxRequestBytes = Math.addExact(
                Math.multiplyExact(creationProperties.getMaxImageInputBytes(), 2L),
                Math.multiplyExact((long) creationProperties.getMaxTextChars(), 4L));
        ProviderHttpResponse response = httpExecutor.postJson(
                restClient,
                endpoint,
                provider.getApiKey(),
                requestId,
                request,
                maxRequestBytes,
                MAX_RESPONSE_BYTES);
        return parseSingleUrl(response.body());
    }

    private String parseSingleUrl(byte[] responseBody) {
        final JsonNode root;
        try {
            root = StrictProviderJson.parse(objectMapper, responseBody);
        } catch (Exception exception) {
            throw invalid("Seedream returned malformed JSON", exception);
        }
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray() || data.size() != 1 || !data.get(0).isObject()) {
            throw invalid("Seedream must return exactly one image result", null);
        }
        JsonNode item = data.get(0);
        JsonNode urlNode = item.get("url");
        if (urlNode == null || !urlNode.isTextual() || urlNode.textValue().isBlank()
                || item.hasNonNull("b64_json")) {
            throw invalid("Seedream image result is missing or ambiguous", null);
        }
        String url = urlNode.textValue().trim();
        try {
            URI parsed = URI.create(url);
            if (!("http".equalsIgnoreCase(parsed.getScheme())
                    || "https".equalsIgnoreCase(parsed.getScheme()))) {
                throw new IllegalArgumentException("unsupported scheme");
            }
        } catch (RuntimeException exception) {
            throw invalid("Seedream image URL is invalid", exception);
        }
        return url;
    }

    private ProviderExecutionException invalid(String message, Throwable cause) {
        return cause == null
                ? new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_INVALID_RESPONSE, message)
                : new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_INVALID_RESPONSE, message, cause);
    }
}
