package com.auralink.guide.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.GuideProperties;

/**
 * In-memory abuse boundary for paid generation calls.
 *
 * <p>The standard Guide and its cache remain user-independent. This component
 * stores only short-lived hashes of authenticated principal names for request
 * throttling; it creates no browsing-history or database rows.</p>
 */
@Component
public class PaintingGuideGenerationGuard {

    private static final int MAX_REQUESTER_LENGTH = 256;

    private final GuideProperties properties;
    private final Clock clock;
    private final Semaphore providerPermits;
    private final Map<String, ArrayDeque<Long>> usage = new HashMap<>();
    private final ArrayDeque<Long> globalUsage = new ArrayDeque<>();

    @Autowired
    public PaintingGuideGenerationGuard(GuideProperties properties) {
        this(properties, Clock.systemUTC());
    }

    PaintingGuideGenerationGuard(GuideProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.providerPermits = new Semaphore(properties.getMaxConcurrentGenerations(), true);
    }

    /** Count only cache-miss POST requests; GET and cache hits never consume quota. */
    public synchronized void recordCacheMiss(String requester) {
        String key = requesterKey(requester);
        long now = clock.millis();
        long userWindowMillis = positiveWindow().toMillis();
        long globalWindowMillis = positiveGlobalWindow().toMillis();

        prune(globalUsage, now, globalWindowMillis);
        if (globalUsage.size() >= properties.getGlobalGenerationLimit()) {
            throw new ApiV1Exception(
                    HttpStatus.TOO_MANY_REQUESTS,
                    ApiErrorCode.GUIDE_RATE_LIMITED,
                    "画作导览生成额度暂时已用完");
        }

        ArrayDeque<Long> currentUser = usage.get(key);
        if (currentUser != null) {
            prune(currentUser, now, userWindowMillis);
        }
        if (currentUser != null
                && currentUser.size() >= properties.getUserGenerationLimit()) {
            throw new ApiV1Exception(
                    HttpStatus.TOO_MANY_REQUESTS,
                    ApiErrorCode.GUIDE_RATE_LIMITED,
                    "画作导览生成请求过于频繁");
        }
        if (currentUser == null) {
            currentUser = new ArrayDeque<>();
            usage.put(key, currentUser);
        }
        currentUser.addLast(now);
        globalUsage.addLast(now);
        if (usage.size() > 1_024) {
            Iterator<Map.Entry<String, ArrayDeque<Long>>> iterator = usage.entrySet().iterator();
            while (iterator.hasNext()) {
                ArrayDeque<Long> timestamps = iterator.next().getValue();
                prune(timestamps, now, userWindowMillis);
                if (timestamps.isEmpty()) {
                    iterator.remove();
                }
            }
        }
    }

    /** Fail fast rather than tying up servlet threads or multiplying paid calls. */
    public <T> T withPaidGeneration(String requester, Supplier<T> action) {
        if (!providerPermits.tryAcquire()) {
            throw new ApiV1Exception(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCode.GUIDE_PROVIDER_UNAVAILABLE,
                    "画作导览生成服务当前繁忙");
        }
        try {
            recordCacheMiss(requester);
            return action.get();
        } finally {
            providerPermits.release();
        }
    }

    private Duration positiveWindow() {
        Duration window = properties.getUserGenerationWindow();
        if (window == null || window.isZero() || window.isNegative()) {
            throw new ApiV1Exception(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCode.GUIDE_DISABLED,
                    "画作导览生成功能当前未配置");
        }
        return window;
    }

    private Duration positiveGlobalWindow() {
        Duration window = properties.getGlobalGenerationWindow();
        if (window == null || window.isZero() || window.isNegative()) {
            throw new ApiV1Exception(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCode.GUIDE_DISABLED,
                    "画作导览生成功能当前未配置");
        }
        return window;
    }

    private String requesterKey(String requester) {
        if (requester == null || requester.isBlank() || requester.length() > MAX_REQUESTER_LENGTH) {
            throw new ApiV1Exception(
                    HttpStatus.UNAUTHORIZED,
                    ApiErrorCode.UNAUTHORIZED,
                    "需要身份验证");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(requester.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    int trackedRequesterCount() {
        return usage.size();
    }

    int availableProviderPermits() {
        return providerPermits.availablePermits();
    }

    synchronized int globalUsageCount() {
        return globalUsage.size();
    }

    private void prune(ArrayDeque<Long> timestamps, long now, long windowMillis) {
        long cutoff = now - windowMillis;
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
            timestamps.removeFirst();
        }
    }
}
