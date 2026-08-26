package com.auralink.api.v1.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.fasterxml.jackson.databind.ObjectMapper;

class ApiV1ExceptionHandlerTest {

    private final ApiV1ExceptionHandler handler = new ApiV1ExceptionHandler();

    @Test
    void controlledExceptionUsesStableSafeEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/example");
        request.addHeader(ApiV1ExceptionHandler.CORRELATION_HEADER, "request-123");

        var response = handler.handleApiException(
                new ApiV1Exception(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, "资源不存在"),
                request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("NOT_FOUND", response.getBody().code());
        assertEquals("资源不存在", response.getBody().message());
        assertEquals("/api/v1/example", response.getBody().path());
        assertEquals("request-123", response.getBody().correlationId());
        assertEquals(Map.of(), response.getBody().validationErrors());
    }

    @Test
    void unexpectedExceptionDoesNotExposeExceptionMessageOrStackTrace() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/example");

        var response = handler.handleUnexpected(
                new IllegalStateException("secret at /srv/private/provider.key"),
                request);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response.getBody());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(json.contains("INTERNAL_ERROR"));
        assertFalse(json.contains("/srv/private"));
        assertFalse(json.toLowerCase().contains("stack"));
        assertFalse(json.toLowerCase().contains("exception"));
    }

    @Test
    void adviceIsScopedOnlyToV1ControllerPackages() {
        RestControllerAdvice annotation = ApiV1ExceptionHandler.class
                .getAnnotation(RestControllerAdvice.class);
        assertNotNull(annotation);

        assertEquals(
                Arrays.asList("com.auralink.api.v1", "com.auralink.controller.v1"),
                Arrays.asList(annotation.basePackages()));
        assertFalse(Arrays.asList(annotation.basePackages()).contains("com.auralink.controller"));
    }

    @Test
    void frameworkMultipartLimitUsesSafeAssetTooLargeEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/assets/uploads");

        var response = handler.handleMaximumUploadSize(
                new MaxUploadSizeExceededException(10L * 1024L * 1024L), request);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ASSET_TOO_LARGE", response.getBody().code());
        assertFalse(response.getBody().message().contains("/"));
    }
}
