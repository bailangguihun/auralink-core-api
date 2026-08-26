package com.auralink.creation.provider;

/** Validated provider output; raw envelopes and URLs never cross this boundary. */
public sealed interface ProviderOutput permits ProviderTextOutput, ProviderBinaryOutput {
}
