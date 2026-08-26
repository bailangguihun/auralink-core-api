package com.auralink.provider.qwen;

import java.net.URI;

/** Injectable resolver permits loopback fixtures without weakening production policy. */
@FunctionalInterface
public interface QwenEndpointResolver {
    URI resolveChatCompletionsEndpoint();
}
