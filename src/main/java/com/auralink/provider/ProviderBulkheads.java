package com.auralink.provider;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;

/** Immediate-fail provider bulkheads with no waiting queue or worker threads. */
@Component
public class ProviderBulkheads {

    private final Map<ProviderBulkheadKind, Semaphore> permits;

    public ProviderBulkheads(CreationProviderProperties properties) {
        EnumMap<ProviderBulkheadKind, Semaphore> configured =
                new EnumMap<>(ProviderBulkheadKind.class);
        configured.put(ProviderBulkheadKind.SEEDREAM,
                semaphore(properties.getMaxConcurrentSeedream(), "Seedream"));
        configured.put(ProviderBulkheadKind.QWEN,
                semaphore(properties.getMaxConcurrentQwen(), "Qwen"));
        configured.put(ProviderBulkheadKind.VMM,
                semaphore(properties.getMaxConcurrentVmm(), "VMM"));
        permits = Map.copyOf(configured);
    }

    public <T> T execute(ProviderBulkheadKind kind, Supplier<T> operation) {
        if (kind == null || operation == null) {
            throw new IllegalArgumentException("Provider bulkhead kind and operation are required");
        }
        Semaphore semaphore = permits.get(kind);
        if (semaphore == null) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_INTERNAL_CONTRACT_ERROR,
                    "Provider bulkhead is unavailable");
        }
        if (!semaphore.tryAcquire()) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_CAPACITY_EXCEEDED,
                    "Provider capacity is currently exhausted");
        }
        try {
            return operation.get();
        } finally {
            semaphore.release();
        }
    }

    int availablePermits(ProviderBulkheadKind kind) {
        return permits.get(kind).availablePermits();
    }

    private Semaphore semaphore(int limit, String name) {
        if (limit < 1) {
            throw new IllegalArgumentException(name + " concurrency limit must be positive");
        }
        return new Semaphore(limit, true);
    }
}
