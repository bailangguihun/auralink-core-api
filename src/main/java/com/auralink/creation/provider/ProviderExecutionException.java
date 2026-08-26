package com.auralink.creation.provider;

import java.util.regex.Pattern;

/** Provider failure carrying only a stable category and public-safe summary. */
public class ProviderExecutionException extends RuntimeException {

    private static final Pattern SAFE_PROVIDER_ERROR_CODE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final Pattern SAFE_REQUEST_ID =
            Pattern.compile("sha256:[0-9a-f]{32}");

    private final ProviderErrorCategory category;
    private final Integer providerHttpStatus;
    private final String providerErrorCode;
    private final String safeRequestId;
    private final ProviderSafeDiagnostic<?, ?, ?> safeDiagnostic;

    public ProviderExecutionException(ProviderErrorCategory category, String safeMessage) {
        this(category, safeMessage, null, null, null, null, null);
    }

    public ProviderExecutionException(
            ProviderErrorCategory category,
            String safeMessage,
            Throwable cause) {
        this(category, safeMessage, cause, null, null, null, null);
    }

    public static ProviderExecutionException fromProviderResponse(
            ProviderErrorCategory category,
            String safeMessage,
            int providerHttpStatus,
            String providerErrorCode,
            String safeRequestId) {
        return new ProviderExecutionException(
                category,
                safeMessage,
                null,
                requireHttpStatus(providerHttpStatus),
                requireSafeToken(providerErrorCode, SAFE_PROVIDER_ERROR_CODE),
                requireSafeToken(safeRequestId, SAFE_REQUEST_ID),
                null);
    }

    public static ProviderExecutionException fromSafeDiagnostic(
            ProviderErrorCategory category,
            String safeMessage,
            ProviderSafeDiagnostic<?, ?, ?> safeDiagnostic) {
        if (safeDiagnostic == null) {
            throw new IllegalArgumentException("Provider safe diagnostic is required");
        }
        return new ProviderExecutionException(
                category,
                safeMessage,
                null,
                null,
                null,
                null,
                safeDiagnostic);
    }

    private ProviderExecutionException(
            ProviderErrorCategory category,
            String safeMessage,
            Throwable cause,
            Integer providerHttpStatus,
            String providerErrorCode,
            String safeRequestId,
            ProviderSafeDiagnostic<?, ?, ?> safeDiagnostic) {
        super(safeMessage, cause);
        this.category = requireCategory(category);
        this.providerHttpStatus = providerHttpStatus;
        this.providerErrorCode = providerErrorCode;
        this.safeRequestId = safeRequestId;
        this.safeDiagnostic = safeDiagnostic;
    }

    public ProviderErrorCategory category() {
        return category;
    }

    public Integer providerHttpStatus() {
        return providerHttpStatus;
    }

    public String providerErrorCode() {
        return providerErrorCode;
    }

    public String safeRequestId() {
        return safeRequestId;
    }

    public ProviderSafeDiagnostic<?, ?, ?> safeDiagnostic() {
        return safeDiagnostic;
    }

    private static ProviderErrorCategory requireCategory(ProviderErrorCategory category) {
        if (category == null) {
            throw new IllegalArgumentException("Provider error category is required");
        }
        return category;
    }

    private static Integer requireHttpStatus(int status) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Provider HTTP status is invalid");
        }
        return status;
    }

    private static String requireSafeToken(String value, Pattern pattern) {
        if (value == null) {
            return null;
        }
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Provider diagnostic token is invalid");
        }
        return value;
    }
}
