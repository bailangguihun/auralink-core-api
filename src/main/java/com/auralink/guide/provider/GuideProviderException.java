package com.auralink.guide.provider;

import lombok.Getter;

/** Safe typed provider failure; messages contain no request, response, URL, or secret. */
@Getter
public class GuideProviderException extends RuntimeException {

    private final Failure failure;
    private final boolean retryable;

    public GuideProviderException(Failure failure, boolean retryable, String safeMessage) {
        super(safeMessage);
        this.failure = failure;
        this.retryable = retryable;
    }

    public GuideProviderException(
            Failure failure,
            boolean retryable,
            String safeMessage,
            Throwable cause) {
        super(safeMessage, cause);
        this.failure = failure;
        this.retryable = retryable;
    }

    public enum Failure {
        CONFIGURATION,
        UNAVAILABLE,
        TIMEOUT,
        REJECTED,
        INVALID_RESPONSE
    }
}
