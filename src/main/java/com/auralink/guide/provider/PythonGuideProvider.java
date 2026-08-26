package com.auralink.guide.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.auralink.config.properties.GuideProperties;
import com.auralink.guide.context.PaintingGuideContext;
import com.auralink.guide.knowledge.KnowledgeItem;
import com.auralink.guide.model.GuideResult;
import com.auralink.guide.model.GuideResultCodec;
import com.auralink.guide.model.GuideResultValidationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

/** Blocking internal client for the loopback Python Guide service. */
@Component
public class PythonGuideProvider implements GuideProvider {

    static final String INTERNAL_TOKEN_HEADER = "X-Auralink-Internal-Token";
    static final int MAX_INTERNAL_RESPONSE_BYTES = 262_144;

    private final RestClient restClient;
    private final GuideProperties properties;
    private final GuideResultCodec resultCodec;
    private final ObjectMapper objectMapper;
    private final ObjectReader envelopeReader;

    public PythonGuideProvider(
            @Qualifier("guideRestClient") RestClient restClient,
            GuideProperties properties,
            GuideResultCodec resultCodec,
            ObjectMapper objectMapper) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.resultCodec = Objects.requireNonNull(resultCodec, "resultCodec");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.envelopeReader = this.objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readerFor(ResponseEnvelope.class);
    }

    @Override
    public GuideGenerationResult generate(String requestId, PaintingGuideContext context) {
        String canonicalRequestId = requireCanonicalRequestId(requestId);
        PaintingGuideContext safeContext = Objects.requireNonNull(context, "context");
        String token = requireConfiguration(properties.getInternalToken(), "internal token");
        String schemaVersion = requireConfiguration(properties.getSchemaVersion(), "schema version");
        URI endpoint = endpoint();
        GenerateRequest request = new GenerateRequest(
                canonicalRequestId,
                schemaVersion,
                new PaintingPayload(
                        safeContext.paintingId(),
                        safeContext.basic(),
                        safeContext.artist(),
                        safeContext.art(),
                        safeContext.officialAnnotations()),
                safeContext.knowledge() == null ? List.of() : List.copyOf(safeContext.knowledge()),
                new GenerationOptions("zh-CN", "STANDARD"));

        // The Python adapter may retry only a DNS/connect failure proven to precede
        // submission. Retrying this internal request could multiply a paid call.
        return execute(endpoint, token, request, safeContext.knowledge());
    }

    private GuideGenerationResult execute(
            URI endpoint,
            String token,
            GenerateRequest request,
            List<KnowledgeItem> allowedKnowledge) {
        try {
            InternalResponse response = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(INTERNAL_TOKEN_HEADER, token)
                    .body(request)
                    .exchange((ignoredRequest, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        byte[] body;
                        try {
                            body = readBounded(
                                    clientResponse.getBody(), clientResponse.getHeaders());
                        } catch (IOException | GuideProviderException exception) {
                            // Some HttpURLConnection implementations expose no error
                            // stream. A non-2xx status remains classifiable without it;
                            // successful responses still require a valid bounded body.
                            if (status.is2xxSuccessful()) {
                                throw exception;
                            }
                            body = new byte[0];
                        }
                        return new InternalResponse(status, body);
                    });
            return parseResponse(response, request.requestId(), request.schemaVersion(), allowedKnowledge);
        } catch (GuideProviderException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            if (hasCause(exception, SocketTimeoutException.class)) {
                throw new GuideProviderException(
                        GuideProviderException.Failure.TIMEOUT,
                        false,
                        "Guide service timed out",
                        exception);
            }
            throw new GuideProviderException(
                    GuideProviderException.Failure.UNAVAILABLE,
                    false,
                    "Guide service is unavailable",
                    exception);
        } catch (RestClientException exception) {
            throw new GuideProviderException(
                    GuideProviderException.Failure.UNAVAILABLE,
                    false,
                    "Guide service request failed",
                    exception);
        }
    }

    private GuideGenerationResult parseResponse(
            InternalResponse response,
            String requestId,
            String schemaVersion,
            List<KnowledgeItem> allowedKnowledge) {
        ResponseEnvelope envelope = null;
        if (response.body().length > 0) {
            try {
                envelope = envelopeReader.readValue(response.body());
            } catch (IOException exception) {
                if (response.status().is2xxSuccessful()) {
                    throw invalidResponse("Guide service returned malformed JSON", exception);
                }
            }
        }
        if (envelope != null && requestId.equals(envelope.requestId())
                && "FAILED".equals(envelope.status())) {
            throw failureEnvelope(envelope);
        }
        if (!response.status().is2xxSuccessful()) {
            throw httpFailure(response.status());
        }
        if (envelope == null || !requestId.equals(envelope.requestId())) {
            throw invalidResponse("Guide service requestId did not match");
        }
        if (!"SUCCESS".equals(envelope.status()) || !hasResult(envelope) || envelope.error() != null) {
            throw invalidResponse("Guide service returned an invalid response envelope");
        }

        try {
            GuideResult result = resultCodec.decode(
                    objectMapper.writeValueAsString(envelope.result()),
                    schemaVersion,
                    allowedKnowledge == null ? List.of() : allowedKnowledge);
            return new GuideGenerationResult(requestId, result);
        } catch (GuideResultValidationException | JsonProcessingException exception) {
            throw invalidResponse("Guide service returned an invalid structured result", exception);
        }
    }

    private GuideProviderException failureEnvelope(ResponseEnvelope envelope) {
        if (hasResult(envelope) || envelope.error() == null || envelope.error().code() == null) {
            return invalidResponse("Guide service returned an invalid failure envelope");
        }
        String code = envelope.error().code().trim().toUpperCase(Locale.ROOT);
        return switch (code) {
            case "PROVIDER_NOT_CONFIGURED", "SERVICE_NOT_CONFIGURED" ->
                    new GuideProviderException(
                            GuideProviderException.Failure.CONFIGURATION,
                            false,
                            "Guide provider is not configured");
            case "UNAUTHORIZED_INTERNAL_CALL" -> new GuideProviderException(
                    GuideProviderException.Failure.CONFIGURATION,
                    false,
                    "Guide service authentication is not configured correctly");
            case "INTERNAL_SERVICE_ERROR" -> new GuideProviderException(
                    GuideProviderException.Failure.UNAVAILABLE,
                    false,
                    "Guide service is temporarily unavailable");
            case "UPSTREAM_TIMEOUT", "TIMEOUT" -> new GuideProviderException(
                    GuideProviderException.Failure.TIMEOUT, false, "Guide provider timed out");
            case "UPSTREAM_RATE_LIMIT", "UPSTREAM_UNAVAILABLE", "UPSTREAM_SERVER_ERROR" ->
                    new GuideProviderException(
                            GuideProviderException.Failure.UNAVAILABLE,
                            false,
                            "Guide provider is temporarily unavailable");
            case "INVALID_RESULT", "INVALID_PROVIDER_RESPONSE" -> invalidResponse(
                    "Guide provider returned an invalid structured result");
            case "INVALID_REQUEST", "REQUEST_TOO_LARGE" -> invalidResponse(
                    "Guide service rejected the internal contract");
            case "UPSTREAM_REJECTED" -> new GuideProviderException(
                    GuideProviderException.Failure.REJECTED,
                    false,
                    "Guide provider rejected the request");
            default -> new GuideProviderException(
                    GuideProviderException.Failure.REJECTED,
                    false,
                    "Guide provider rejected the request");
        };
    }

    private boolean hasResult(ResponseEnvelope envelope) {
        return envelope.result() != null && !envelope.result().isNull();
    }

    private GuideProviderException httpFailure(HttpStatusCode status) {
        int value = status.value();
        if (value == 408 || value == 504) {
            return new GuideProviderException(
                    GuideProviderException.Failure.TIMEOUT, false, "Guide service timed out");
        }
        if (value == 429 || value == 502 || value == 503 || value >= 500) {
            return new GuideProviderException(
                    GuideProviderException.Failure.UNAVAILABLE,
                    false,
                    "Guide service is temporarily unavailable");
        }
        if (value == 401 || value == 403) {
            return new GuideProviderException(
                    GuideProviderException.Failure.CONFIGURATION,
                    false,
                    "Guide service authentication is not configured correctly");
        }
        if (value == 400 || value == 413 || value == 422) {
            return new GuideProviderException(
                    GuideProviderException.Failure.INVALID_RESPONSE,
                    false,
                    "Guide service rejected the internal contract");
        }
        return new GuideProviderException(
                GuideProviderException.Failure.UNAVAILABLE,
                false,
                "Guide service request failed");
    }

    private byte[] readBounded(InputStream input, HttpHeaders headers) {
        if (input == null) {
            return new byte[0];
        }
        long contentLength = headers.getContentLength();
        if (contentLength > MAX_INTERNAL_RESPONSE_BYTES) {
            throw invalidResponse("Guide service response exceeded the byte limit");
        }
        try (InputStream source = input;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > MAX_INTERNAL_RESPONSE_BYTES) {
                    throw invalidResponse("Guide service response exceeded the byte limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (GuideProviderException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new GuideProviderException(
                    GuideProviderException.Failure.UNAVAILABLE,
                    false,
                    "Guide service response could not be read",
                    exception);
        }
    }

    private URI endpoint() {
        String configured = requireConfiguration(
                Objects.toString(properties.getServiceUrl(), null), "service URL");
        try {
            URI base = new URI(configured);
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || base.getHost() == null
                    || !isLoopbackLiteral(base.getHost())
                    || base.getUserInfo() != null
                    || base.getQuery() != null
                    || base.getFragment() != null) {
                throw configuration("Guide service URL is invalid");
            }
            return base.resolve("/v1/guide/generate");
        } catch (URISyntaxException exception) {
            throw new GuideProviderException(
                    GuideProviderException.Failure.CONFIGURATION,
                    false,
                    "Guide service URL is invalid",
                    exception);
        }
    }

    private boolean isLoopbackLiteral(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "127.0.0.1".equals(normalized)
                || "::1".equals(normalized);
    }

    private String requireCanonicalRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw configuration("Guide requestId is invalid");
        }
        try {
            String canonical = UUID.fromString(requestId).toString();
            if (!canonical.equals(requestId.toLowerCase(Locale.ROOT))) {
                throw configuration("Guide requestId is invalid");
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw configuration("Guide requestId is invalid");
        }
    }

    private String requireConfiguration(String value, String name) {
        if (value == null || value.isBlank()) {
            throw configuration("Guide " + name + " is not configured");
        }
        return value;
    }

    private GuideProviderException configuration(String message) {
        return new GuideProviderException(
                GuideProviderException.Failure.CONFIGURATION, false, message);
    }

    private GuideProviderException invalidResponse(String message) {
        return new GuideProviderException(
                GuideProviderException.Failure.INVALID_RESPONSE, false, message);
    }

    private GuideProviderException invalidResponse(String message, Throwable cause) {
        return new GuideProviderException(
                GuideProviderException.Failure.INVALID_RESPONSE, false, message, cause);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private record GenerateRequest(
            String requestId,
            String schemaVersion,
            PaintingPayload painting,
            List<KnowledgeItem> knowledge,
            GenerationOptions options) {
    }

    private record PaintingPayload(
            String paintingId,
            Object basic,
            Object artist,
            Object art,
            Object officialAnnotations) {
    }

    private record GenerationOptions(String language, String detailLevel) {
    }

    private record ResponseEnvelope(
            String requestId,
            String status,
            JsonNode result,
            ErrorEnvelope error) {
    }

    private record ErrorEnvelope(String code, String message) {
    }

    private record InternalResponse(HttpStatusCode status, byte[] body) {
    }
}
