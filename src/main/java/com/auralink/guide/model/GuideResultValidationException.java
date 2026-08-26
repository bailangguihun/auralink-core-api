package com.auralink.guide.model;

/** Internal validation failure. Messages identify fields but never include provider content. */
public class GuideResultValidationException extends RuntimeException {

    public GuideResultValidationException(String message) {
        super(message);
    }

    public GuideResultValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
