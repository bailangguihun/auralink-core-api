package com.auralink.guide.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.GuideProperties;

class PaintingGuideGenerationGuardTest {

    @Test
    void rateLimitsOnlyWithinTheConfiguredWindowAndStoresNoRawPrincipal() {
        GuideProperties properties = new GuideProperties();
        properties.setUserGenerationLimit(2);
        properties.setUserGenerationWindow(Duration.ofMinutes(10));
        MutableClock clock = new MutableClock();
        PaintingGuideGenerationGuard guard = new PaintingGuideGenerationGuard(properties, clock);

        guard.recordCacheMiss("Sensitive-User-Name");
        guard.recordCacheMiss("Sensitive-User-Name");
        assertThatThrownBy(() -> guard.recordCacheMiss("Sensitive-User-Name"))
                .isInstanceOfSatisfying(ApiV1Exception.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ApiErrorCode.GUIDE_RATE_LIMITED));
        assertThat(guard.trackedRequesterCount()).isOne();

        clock.advance(Duration.ofMinutes(11));
        guard.recordCacheMiss("Sensitive-User-Name");
    }

    @Test
    void globalBulkheadFailsFastAndAlwaysReleasesItsPermit() throws Exception {
        GuideProperties properties = new GuideProperties();
        properties.setMaxConcurrentGenerations(1);
        PaintingGuideGenerationGuard guard = new PaintingGuideGenerationGuard(properties);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(() -> guard.withPaidGeneration("first", () -> {
                entered.countDown();
                try {
                    return release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> guard.withPaidGeneration("second", () -> "unexpected"))
                    .isInstanceOfSatisfying(ApiV1Exception.class, exception ->
                            assertThat(exception.getCode())
                                    .isEqualTo(ApiErrorCode.GUIDE_PROVIDER_UNAVAILABLE));
            release.countDown();
            assertThat(running.get()).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(guard.availableProviderPermits()).isOne();
        assertThat(guard.globalUsageCount()).isOne();
    }

    @Test
    void globalRollingQuotaCannotBeBypassedWithDifferentPrincipalNames() {
        GuideProperties properties = new GuideProperties();
        properties.setGlobalGenerationLimit(2);
        properties.setGlobalGenerationWindow(Duration.ofHours(1));
        properties.setUserGenerationLimit(100);
        MutableClock clock = new MutableClock();
        PaintingGuideGenerationGuard guard = new PaintingGuideGenerationGuard(properties, clock);

        guard.recordCacheMiss("first-account");
        guard.recordCacheMiss("second-account");
        assertThatThrownBy(() -> guard.recordCacheMiss("new-third-account"))
                .isInstanceOfSatisfying(ApiV1Exception.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ApiErrorCode.GUIDE_RATE_LIMITED));
        for (int index = 0; index < 100; index++) {
            String freshPrincipal = "rejected-fresh-account-" + index;
            assertThatThrownBy(() -> guard.recordCacheMiss(freshPrincipal))
                    .isInstanceOf(ApiV1Exception.class);
        }
        assertThat(guard.trackedRequesterCount()).isEqualTo(2);

        clock.advance(Duration.ofHours(2));
        guard.recordCacheMiss("new-third-account");
    }

    @Test
    void globalQuotaUsesATrueSlidingWindowAcrossTheOriginalBoundary() {
        GuideProperties properties = new GuideProperties();
        properties.setGlobalGenerationLimit(2);
        properties.setGlobalGenerationWindow(Duration.ofHours(1));
        properties.setUserGenerationLimit(100);
        MutableClock clock = new MutableClock();
        PaintingGuideGenerationGuard guard = new PaintingGuideGenerationGuard(properties, clock);

        guard.recordCacheMiss("first-account");
        clock.advance(Duration.ofMinutes(59));
        guard.recordCacheMiss("second-account");
        clock.advance(Duration.ofMinutes(2));

        // The first event has expired, but the second still occupies the rolling window.
        guard.recordCacheMiss("third-account");
        assertThatThrownBy(() -> guard.recordCacheMiss("fourth-account"))
                .isInstanceOfSatisfying(ApiV1Exception.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ApiErrorCode.GUIDE_RATE_LIMITED));
    }

    @Test
    void rejectedPerUserAttemptDoesNotConsumeGlobalQuota() {
        GuideProperties properties = new GuideProperties();
        properties.setUserGenerationLimit(1);
        properties.setGlobalGenerationLimit(10);
        PaintingGuideGenerationGuard guard = new PaintingGuideGenerationGuard(properties);

        guard.recordCacheMiss("same-account");
        assertThatThrownBy(() -> guard.recordCacheMiss("same-account"))
                .isInstanceOf(ApiV1Exception.class);

        assertThat(guard.globalUsageCount()).isOne();
    }

    @Test
    void caseDistinctLegacyPrincipalNamesHaveIndependentQuotaKeys() {
        GuideProperties properties = new GuideProperties();
        properties.setUserGenerationLimit(1);
        properties.setGlobalGenerationLimit(10);
        PaintingGuideGenerationGuard guard = new PaintingGuideGenerationGuard(properties);

        guard.recordCacheMiss("legacy-user");
        guard.recordCacheMiss("LEGACY-USER");

        assertThat(guard.trackedRequesterCount()).isEqualTo(2);
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
