package com.auralink.provider.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.validation.StrictProviderJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/** One bounded POST with no automatic retry, redirect, cookie, or raw-body logging. */
@Component
@RequiredArgsConstructor
public class ProviderHttpExecutor {

    private static final int BUFFER_SIZE = 16 * 1024;
    private static final int MAX_REQUEST_ID_CHARS = 512;
    private static final Pattern SAFE_PROVIDER_ERROR_CODE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    private final ObjectMapper objectMapper;

    public ProviderHttpResponse postJson(
            RestClient client,
            URI endpoint,
            String bearerToken,
            String requestId,
            Object request,
            long maxRequestBytes,
            long maxResponseBytes) {
        if (client == null || endpoint == null || requestId == null || requestId.isBlank() || request == null
                || maxRequestBytes < 1 || maxResponseBytes < 1) {
            throw internal("Provider HTTP contract is incomplete", null);
        }
        final byte[] requestBytes;
        try {
            requestBytes = objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException exception) {
            throw internal("Provider request could not be serialized", exception);
        }
        if (requestBytes.length > maxRequestBytes) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_REJECTED,
                    "Provider request exceeds the configured byte limit");
        }

        try {
            ProviderHttpResponse response = client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        setBearer(headers, bearerToken);
                        headers.set("X-Auralink-Request-Id", requestId);
                    })
                    .body(requestBytes)
                    .exchange((httpRequest, httpResponse) -> {
                        long declaredLength = httpResponse.getHeaders().getContentLength();
                        if (declaredLength > maxResponseBytes) {
                            throw new ProviderExecutionException(
                                    ProviderErrorCategory.PROVIDER_INVALID_RESPONSE,
                                    "Provider response exceeds the configured byte limit");
                        }
                        try (InputStream input = httpResponse.getBody()) {
                            return new ProviderHttpResponse(
                                    httpResponse.getStatusCode().value(),
                                    readBounded(input, maxResponseBytes),
                                    safeRequestId(httpResponse.getHeaders()));
                        }
                    });
            requireSuccessfulStatus(response);
            return response;
        } catch (ProviderExecutionException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            if (containsTimeout(exception)) {
                throw new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_TIMEOUT,
                        "Provider request timed out",
                        exception);
            }
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_UNAVAILABLE,
                    "Provider service is unavailable",
                    exception);
        } catch (RestClientException exception) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_UNAVAILABLE,
                    "Provider transport failed",
                    exception);
        }
    }

    private byte[] readBounded(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) {
                continue;
            }
            if (total > maxBytes - read) {
                throw new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_INVALID_RESPONSE,
                        "Provider response exceeds the configured byte limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
    }

    private void requireSuccessfulStatus(ProviderHttpResponse response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        ProviderErrorCategory category;
        String safeMessage;
        if (status == 401 || status == 403) {
            category = ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID;
            safeMessage = "Provider authentication was rejected";
        } else if (status == 429) {
            category = ProviderErrorCategory.PROVIDER_RATE_LIMITED;
            safeMessage = "Provider rate limit was reached";
        } else if (status >= 500) {
            category = ProviderErrorCategory.PROVIDER_UNAVAILABLE;
            safeMessage = "Provider service returned a failure";
        } else {
            category = ProviderErrorCategory.PROVIDER_REJECTED;
            safeMessage = "Provider rejected the request";
        }
        SafeProviderError safe = safeProviderError(response);
        throw ProviderExecutionException.fromProviderResponse(
                category,
                safeMessage,
                status,
                safe.errorCode(),
                safe.safeRequestId());
    }

    private SafeProviderError safeProviderError(ProviderHttpResponse response) {
        String errorCode = null;
        String safeRequestId = response.safeRequestId();
        try {
            JsonNode root = StrictProviderJson.parse(objectMapper, response.body());
            if (root != null && root.isObject()) {
                JsonNode error = root.get("error");
                JsonNode code = error != null && error.isObject()
                        ? error.get("code")
                        : root.get("code");
                errorCode = safeErrorCode(code);
                if (safeRequestId == null) {
                    JsonNode requestId = error != null && error.isObject()
                            ? firstPresent(error.get("request_id"), error.get("requestId"))
                            : null;
                    requestId = firstPresent(
                            requestId,
                            root.get("request_id"),
                            root.get("requestId"));
                    safeRequestId = requestId != null && requestId.isTextual()
                            ? hashRequestId(requestId.textValue())
                            : null;
                }
            }
        } catch (Exception ignored) {
            // An unparseable rejection body contributes no diagnostic fields.
        }
        return new SafeProviderError(errorCode, safeRequestId);
    }

    private JsonNode firstPresent(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null && !candidate.isNull()) {
                return candidate;
            }
        }
        return null;
    }

    private String safeErrorCode(JsonNode code) {
        if (code == null || !code.isTextual()) {
            return null;
        }
        String value = code.textValue().trim();
        return SAFE_PROVIDER_ERROR_CODE.matcher(value).matches() ? value : null;
    }

    private String safeRequestId(HttpHeaders headers) {
        String value = headers.getFirst("X-Request-Id");
        if (value == null || value.isBlank()) {
            value = headers.getFirst("X-Tt-Logid");
        }
        return hashRequestId(value);
    }

    private String hashRequestId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()
                || normalized.length() > MAX_REQUEST_ID_CHARS
                || containsControl(normalized)) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw internal("Provider response metadata could not be sanitized", exception);
        }
    }

    private void setBearer(HttpHeaders headers, String bearerToken) {
        if (bearerToken == null) {
            return;
        }
        if (bearerToken.isBlank() || containsControl(bearerToken)) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID,
                    "Provider authentication configuration is invalid");
        }
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
    }

    private boolean containsControl(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl((char) character));
    }

    private boolean containsTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ProviderExecutionException internal(String message, Throwable cause) {
        return cause == null
                ? new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_INTERNAL_CONTRACT_ERROR, message)
                : new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_INTERNAL_CONTRACT_ERROR, message, cause);
    }

    private record SafeProviderError(String errorCode, String safeRequestId) {
    }
}
