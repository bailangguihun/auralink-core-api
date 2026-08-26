package com.auralink.provider.seedream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Typed Ark image-generation requests with operation-specific field sets. */
public sealed interface SeedreamGenerationRequest
        permits SeedreamTextGenerationRequest, SeedreamImageGenerationRequest {

    static SeedreamGenerationRequest text(
            String model,
            String prompt,
            String size,
            boolean watermark) {
        return new SeedreamTextGenerationRequest(
                model, prompt, "url", size, false, watermark);
    }

    static SeedreamGenerationRequest image(
            String model,
            String prompt,
            String image,
            String size,
            boolean watermark) {
        return new SeedreamImageGenerationRequest(
                model, prompt, image, "url", size, false, watermark);
    }
}

/** Current Seedream 5.0 Pro non-streaming, single-image text request. */
@JsonPropertyOrder({
        "model", "prompt", "response_format", "size", "stream", "watermark"
})
record SeedreamTextGenerationRequest(
        String model,
        String prompt,
        @JsonProperty("response_format") String responseFormat,
        String size,
        boolean stream,
        boolean watermark) implements SeedreamGenerationRequest {
}

/** Current Seedream 5.0 Pro non-streaming, single-image transformation request. */
@JsonPropertyOrder({
        "model", "prompt", "image", "response_format", "size", "stream", "watermark"
})
record SeedreamImageGenerationRequest(
        String model,
        String prompt,
        String image,
        @JsonProperty("response_format") String responseFormat,
        String size,
        boolean stream,
        boolean watermark) implements SeedreamGenerationRequest {
}
