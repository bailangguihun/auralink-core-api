package com.auralink.service.media;

/** Raised after the streamed byte count crosses an explicitly configured cap. */
public class MediaAssetSizeLimitException extends RuntimeException {

    public MediaAssetSizeLimitException(String message) {
        super(message);
    }
}
