package com.auralink.exception;

/**
 * Raised when a caller-provided storage path cannot be contained within the
 * configured storage root.
 */
public class InvalidStoragePathException extends StorageException {

    public InvalidStoragePathException(String message) {
        super(message);
    }

    public InvalidStoragePathException(String message, Throwable cause) {
        super(message, cause);
    }
}
