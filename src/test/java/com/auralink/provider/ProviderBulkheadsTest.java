package com.auralink.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;

class ProviderBulkheadsTest {

    @Test
    void usesRequiredDefaultLimits() {
        CreationProviderProperties properties = new CreationProviderProperties();

        assertThat(properties.getMaxConcurrentSeedream()).isEqualTo(2);
        assertThat(properties.getMaxConcurrentQwen()).isEqualTo(4);
        assertThat(properties.getMaxConcurrentVmm()).isEqualTo(1);
    }

    @Test
    void immediatelyRejectsAtCapacityAndReleasesPermitAfterSuccess() throws Exception {
        CreationProviderProperties properties = new CreationProviderProperties();
        properties.setMaxConcurrentVmm(1);
        ProviderBulkheads bulkheads = new ProviderBulkheads(properties);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> active = executor.submit(() -> bulkheads.execute(ProviderBulkheadKind.VMM, () -> {
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> bulkheads.execute(ProviderBulkheadKind.VMM, () -> "unexpected"))
                    .isInstanceOf(ProviderExecutionException.class)
                    .extracting(exception -> ((ProviderExecutionException) exception).category())
                    .isEqualTo(ProviderErrorCategory.PROVIDER_CAPACITY_EXCEEDED);

            release.countDown();
            assertThat(active.get(2, TimeUnit.SECONDS)).isEqualTo("done");
            assertThat(bulkheads.execute(ProviderBulkheadKind.VMM, () -> "next")).isEqualTo("next");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void releasesPermitAfterProviderFailure() {
        ProviderBulkheads bulkheads = new ProviderBulkheads(new CreationProviderProperties());

        assertThatThrownBy(() -> bulkheads.execute(ProviderBulkheadKind.SEEDREAM, () -> {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_UNAVAILABLE, "safe failure");
        })).isInstanceOf(ProviderExecutionException.class);
        assertThat(bulkheads.execute(ProviderBulkheadKind.SEEDREAM, () -> "recovered"))
                .isEqualTo("recovered");
    }
}
