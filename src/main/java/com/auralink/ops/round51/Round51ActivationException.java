package com.auralink.ops.round51;

/** Safe, operator-facing activation failure without filesystem or secret detail. */
public final class Round51ActivationException extends RuntimeException {

    private final String code;

    Round51ActivationException(String code, String message) {
        super(message);
        this.code = code;
    }

    Round51ActivationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
