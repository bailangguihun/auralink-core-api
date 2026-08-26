package com.auralink.ops.round81;

import java.net.URI;
import java.util.EnumMap;
import java.util.Map;

/** Process-local exact invocation ledger; it records no request or endpoint values. */
final class Round81ProviderCallLedger {

    private final EnumMap<Round81ProviderFamily, Integer> counts =
            new EnumMap<>(Round81ProviderFamily.class);
    private boolean executionEntered;
    private Round81ProviderFamily lastProviderFamily;

    Round81ProviderCallLedger() {
        reset();
    }

    synchronized void reset() {
        for (Round81ProviderFamily family : Round81ProviderFamily.values()) {
            counts.put(family, 0);
        }
        executionEntered = false;
        lastProviderFamily = null;
    }

    synchronized void enterExecution() {
        if (executionEntered) {
            throw new Round81ValidationException(
                    "EXECUTION_COUNT_EXCEEDED", "Validation execution may be entered only once");
        }
        executionEntered = true;
    }

    synchronized void record(URI endpoint) {
        Round81ProviderFamily family = classify(endpoint);
        counts.put(family, Math.addExact(counts.get(family), 1));
        lastProviderFamily = family;
    }

    synchronized Map<String, Integer> safeCounts() {
        return Map.of(
                "seedream", counts.get(Round81ProviderFamily.SEEDREAM),
                "qwen", counts.get(Round81ProviderFamily.QWEN),
                "vmm", counts.get(Round81ProviderFamily.VMM));
    }

    synchronized String safeLastProviderFamily() {
        return lastProviderFamily == null ? null : lastProviderFamily.name();
    }

    synchronized int totalCallCount() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    synchronized void requireExact(Round81ValidationOperation operation) {
        if (!executionEntered) {
            throw new Round81ValidationException(
                    "EXECUTION_COUNT_INVALID", "Provider execution was not entered");
        }
        for (Round81ProviderFamily family : Round81ProviderFamily.values()) {
            if (counts.get(family) != operation.expectedCalls(family)) {
                throw new Round81ValidationException(
                        "PROVIDER_CALL_COUNT_MISMATCH", "Provider invocation count did not match the reviewed budget");
            }
        }
    }

    private Round81ProviderFamily classify(URI endpoint) {
        if (endpoint == null || endpoint.getPath() == null) {
            throw new Round81ValidationException(
                    "PROVIDER_CALL_UNCLASSIFIED", "Provider invocation could not be classified");
        }
        String path = endpoint.getPath();
        if (path.endsWith("/images/generations")) {
            return Round81ProviderFamily.SEEDREAM;
        }
        if (path.endsWith("/chat/completions")) {
            return Round81ProviderFamily.QWEN;
        }
        if (path.endsWith("/api/generate_with_image")) {
            return Round81ProviderFamily.VMM;
        }
        throw new Round81ValidationException(
                "PROVIDER_CALL_UNCLASSIFIED", "Provider invocation could not be classified");
    }
}
