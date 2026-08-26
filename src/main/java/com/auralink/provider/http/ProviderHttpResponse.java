package com.auralink.provider.http;

/** Bounded internal HTTP response; callers must parse and immediately discard it. */
public record ProviderHttpResponse(int statusCode, byte[] body, String safeRequestId) {

    public ProviderHttpResponse(int statusCode, byte[] body) {
        this(statusCode, body, null);
    }

    public ProviderHttpResponse {
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
