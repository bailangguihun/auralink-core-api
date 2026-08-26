package com.auralink.service.media;

/** Raised when bytes do not form an accepted, safely decodable image. */
public class InvalidImageContentException extends RuntimeException {

    public InvalidImageContentException(String message) {
        super(message);
    }

    public InvalidImageContentException(String message, Throwable cause) {
        super(message, cause);
    }
}
