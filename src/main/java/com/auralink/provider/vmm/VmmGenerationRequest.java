package com.auralink.provider.vmm;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Exact active VMM image-only request; duration preserves inherited behavior. */
@JsonPropertyOrder({"image", "duration"})
public record VmmGenerationRequest(String image, int duration) {
}
