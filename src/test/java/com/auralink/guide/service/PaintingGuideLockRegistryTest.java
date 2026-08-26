package com.auralink.guide.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;

class PaintingGuideLockRegistryTest {

    @Test
    @Timeout(10)
    void serializesSamePaintingAndCleansEntry() throws Exception {
        PaintingGuideLockRegistry registry = new PaintingGuideLockRegistry();
        ExecutorService executor = Executors.newFixedThreadPool(
                PaintingGuideLockRegistry.MAX_PARTICIPANTS_PER_PAINTING);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int index = 0;
                    index < PaintingGuideLockRegistry.MAX_PARTICIPANTS_PER_PAINTING;
                    index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return registry.withPaintingLock("painting-a", () -> {
                        int current = inFlight.incrementAndGet();
                        maximum.accumulateAndGet(current, Math::max);
                        try {
                            Thread.sleep(15L);
                            return current;
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    });
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(maximum).hasValue(1);
        assertThat(registry.activeEntryCount()).isZero();
    }

    @Test
    @Timeout(10)
    void boundsSamePaintingWaitersAndFailsExcessAdmissionFast() throws Exception {
        PaintingGuideLockRegistry registry = new PaintingGuideLockRegistry();
        ExecutorService executor = Executors.newFixedThreadPool(
                PaintingGuideLockRegistry.MAX_PARTICIPANTS_PER_PAINTING);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<String> owner = executor.submit(() -> registry.withPaintingLock("painting-a", () -> {
                entered.countDown();
                await(release);
                return "owner";
            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            List<Future<String>> waiters = new ArrayList<>();
            for (int index = 1;
                    index < PaintingGuideLockRegistry.MAX_PARTICIPANTS_PER_PAINTING;
                    index++) {
                int waiterIndex = index;
                waiters.add(executor.submit(() -> registry.withPaintingLock(
                        "painting-a", () -> "waiter-" + waiterIndex)));
            }
            awaitParticipants(registry, "painting-a",
                    PaintingGuideLockRegistry.MAX_PARTICIPANTS_PER_PAINTING);

            assertThatThrownBy(() -> registry.withPaintingLock("painting-a", () -> "excess"))
                    .isInstanceOfSatisfying(ApiV1Exception.class, exception ->
                            assertThat(exception.getCode())
                                    .isEqualTo(ApiErrorCode.GUIDE_PROVIDER_UNAVAILABLE));

            release.countDown();
            assertThat(owner.get(2, TimeUnit.SECONDS)).isEqualTo("owner");
            for (Future<String> waiter : waiters) {
                assertThat(waiter.get(2, TimeUnit.SECONDS)).startsWith("waiter-");
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(registry.activeEntryCount()).isZero();
    }

    @Test
    @Timeout(10)
    void permitsDifferentPaintingsToRunConcurrently() throws Exception {
        PaintingGuideLockRegistry registry = new PaintingGuideLockRegistry();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> registry.withPaintingLock("a", () -> waitTogether(entered, release)));
            Future<Boolean> second = executor.submit(() -> registry.withPaintingLock("b", () -> waitTogether(entered, release)));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(first.get()).isTrue();
            assertThat(second.get()).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(registry.activeEntryCount()).isZero();
    }

    private static boolean waitTogether(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            return release.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void awaitParticipants(
            PaintingGuideLockRegistry registry,
            String paintingId,
            int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (registry.activeParticipantCount(paintingId) != expected
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(registry.activeParticipantCount(paintingId)).isEqualTo(expected);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", exception);
        }
    }
}
