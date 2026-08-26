package com.auralink.guide.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataIntegrityViolationException;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.GuideProperties;
import com.auralink.entity.Painting;
import com.auralink.entity.PaintingGuide;
import com.auralink.guide.context.PaintingGuideContext;
import com.auralink.guide.context.PaintingGuideContextBuilder;
import com.auralink.guide.hash.GuideSourceHasher;
import com.auralink.guide.knowledge.KnowledgeContextBuilder;
import com.auralink.guide.knowledge.KnowledgeSelection;
import com.auralink.guide.model.GuideResult;
import com.auralink.guide.model.GuideResultCodec;
import com.auralink.guide.model.GuideSections;
import com.auralink.guide.provider.GuideGenerationResult;
import com.auralink.guide.provider.GuideProvider;
import com.auralink.guide.provider.GuideProviderException;
import com.auralink.service.painting.PaintingQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;

class PaintingGuideServiceTest {

    private static final String SOURCE_HASH = "a".repeat(64);

    private PaintingQueryService paintings;
    private PaintingGuideContextBuilder contexts;
    private KnowledgeContextBuilder knowledge;
    private GuideSourceHasher hasher;
    private GuideProvider provider;
    private PaintingGuideCacheStore cache;
    private GuideProperties properties;
    private GuideResultCodec codec;
    private PaintingGuideService service;
    private Painting painting;
    private PaintingGuideContext context;
    private GuideResult result;

    @BeforeEach
    void setUp() {
        paintings = mock(PaintingQueryService.class);
        contexts = mock(PaintingGuideContextBuilder.class);
        knowledge = mock(KnowledgeContextBuilder.class);
        hasher = mock(GuideSourceHasher.class);
        provider = mock(GuideProvider.class);
        cache = mock(PaintingGuideCacheStore.class);
        properties = new GuideProperties();
        properties.setEnabled(true);
        properties.setInternalToken("test-internal-token");
        properties.setUserGenerationLimit(10);
        properties.setGlobalGenerationLimit(100);
        codec = new GuideResultCodec(new ObjectMapper().findAndRegisterModules());
        service = new PaintingGuideService(
                paintings,
                contexts,
                knowledge,
                hasher,
                codec,
                provider,
                cache,
                new PaintingGuideLockRegistry(),
                new PaintingGuideGenerationGuard(properties),
                properties);

        painting = Painting.builder()
                .id(42L)
                .publicId(UUID.randomUUID().toString())
                .imageStorageName("test.jpg")
                .status("ACTIVE")
                .build();
        context = context(painting.getPublicId());
        result = validResult();
        when(paintings.requireActivePainting(painting.getPublicId())).thenReturn(painting);
        when(contexts.build(painting)).thenReturn(context);
        when(knowledge.build(context)).thenReturn(new KnowledgeSelection(List.of(), Map.of()));
        when(hasher.hash(properties.getSchemaVersion(), context, new KnowledgeSelection(List.of(), Map.of())))
                .thenReturn(SOURCE_HASH);
    }

    @Test
    void validCurrentHashIsHitWithoutProviderCall() {
        when(cache.findByPaintingId(42L)).thenReturn(Optional.of(validRow()));

        PaintingGuideOutcome outcome = service.ensureGuide(painting.getPublicId(), "test-user");

        assertThat(outcome.cacheStatus()).isEqualTo(GuideCacheStatus.HIT);
        assertThat(outcome.result().summary()).isEqualTo("这是一段有依据的标准导览。");
        verify(provider, never()).generate(anyString(), any());
        verify(cache, never()).saveSuccess(any(), anyString(), anyString());
    }

    @Test
    void cacheOnlyGetRejectsMissingAndCorruptRowsWithoutWriting() {
        when(cache.findByPaintingId(42L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(row("{}", SOURCE_HASH)));

        assertGuideCode(() -> service.getCurrentGuide(painting.getPublicId()), ApiErrorCode.GUIDE_NOT_AVAILABLE);
        assertGuideCode(() -> service.getCurrentGuide(painting.getPublicId()), ApiErrorCode.GUIDE_NOT_AVAILABLE);
        verify(provider, never()).generate(anyString(), any());
        verify(cache, never()).saveSuccess(any(), anyString(), anyString());
    }

    @Test
    void missingCacheRequiresExplicitEnabledConfiguration() {
        properties.setEnabled(false);
        when(cache.findByPaintingId(42L)).thenReturn(Optional.empty());

        assertGuideCode(() -> service.ensureGuide(painting.getPublicId(), "test-user"), ApiErrorCode.GUIDE_DISABLED);
        verify(provider, never()).generate(anyString(), any());
    }

    @Test
    void successfulProviderResultIsCanonicalizedPersistedAndReloaded() {
        LocalDateTime inMemoryTime = LocalDateTime.of(2026, 8, 15, 0, 51, 11, 120_107_280);
        LocalDateTime persistedTime = inMemoryTime.withNano(120_000_000);
        PaintingGuide inMemoryRow = row(codec.encodeCanonical(result, "1", List.of()), SOURCE_HASH);
        inMemoryRow.setGeneratedAt(inMemoryTime);
        inMemoryRow.setUpdatedAt(inMemoryTime);
        PaintingGuide persistedRow = row(codec.encodeCanonical(result, "1", List.of()), SOURCE_HASH);
        persistedRow.setGeneratedAt(persistedTime);
        persistedRow.setUpdatedAt(persistedTime);
        when(cache.findByPaintingId(42L))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(persistedRow));
        when(provider.generate(anyString(), any())).thenAnswer(invocation ->
                new GuideGenerationResult(invocation.getArgument(0), result));
        when(cache.saveSuccess(any(), anyString(), anyString())).thenReturn(inMemoryRow);

        PaintingGuideOutcome outcome = service.ensureGuide(painting.getPublicId(), "test-user");

        assertThat(outcome.cacheStatus()).isEqualTo(GuideCacheStatus.GENERATED);
        assertThat(outcome.paintingId()).isEqualTo(painting.getPublicId());
        assertThat(outcome.generatedAt()).isEqualTo(persistedTime);
        assertThat(outcome.updatedAt()).isEqualTo(persistedTime);
        verify(provider).generate(anyString(), any());
        verify(cache).saveSuccess(painting, SOURCE_HASH, codec.encodeCanonical(result, "1", List.of()));
    }

    @Test
    void providerFailureDoesNotOverwriteStaleValidRow() {
        PaintingGuide stale = row(codec.encodeCanonical(result, "1", List.of()), "b".repeat(64));
        when(cache.findByPaintingId(42L)).thenReturn(Optional.of(stale));
        when(provider.generate(anyString(), any())).thenThrow(new GuideProviderException(
                GuideProviderException.Failure.TIMEOUT, true, "safe timeout"));

        assertGuideCode(() -> service.ensureGuide(painting.getPublicId(), "test-user"), ApiErrorCode.GUIDE_PROVIDER_TIMEOUT);
        verify(cache, never()).saveSuccess(any(), anyString(), anyString());
        assertThat(stale.getResultJson()).isEqualTo(codec.encodeCanonical(result, "1", List.of()));
    }

    @Test
    void uniqueConflictReconcilesToWinningValidGuide() {
        PaintingGuide winner = validRow();
        when(cache.findByPaintingId(42L))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
        when(provider.generate(anyString(), any())).thenAnswer(invocation ->
                new GuideGenerationResult(invocation.getArgument(0), result));
        when(cache.saveSuccess(any(), anyString(), anyString()))
                .thenThrow(new DataIntegrityViolationException("synthetic unique race"));

        PaintingGuideOutcome outcome = service.ensureGuide(painting.getPublicId(), "test-user");

        assertThat(outcome.cacheStatus()).isEqualTo(GuideCacheStatus.HIT);
        verify(provider).generate(anyString(), any());
    }

    @Test
    @Timeout(10)
    void concurrentSamePaintingCallsProviderOnceAndReusePersistedGuide() throws Exception {
        AtomicReference<PaintingGuide> persisted = new AtomicReference<>();
        AtomicInteger providerCalls = new AtomicInteger();
        CountDownLatch providerEntered = new CountDownLatch(1);
        when(cache.findByPaintingId(42L)).thenAnswer(ignored -> Optional.ofNullable(persisted.get()));
        when(provider.generate(anyString(), any())).thenAnswer(invocation -> {
            providerCalls.incrementAndGet();
            providerEntered.countDown();
            Thread.sleep(50L);
            return new GuideGenerationResult(invocation.getArgument(0), result);
        });
        when(cache.saveSuccess(any(), anyString(), anyString())).thenAnswer(invocation -> {
            PaintingGuide saved = row(invocation.getArgument(2), invocation.getArgument(1));
            persisted.set(saved);
            return saved;
        });

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<PaintingGuideOutcome>> futures = List.of(
                    executor.submit(() -> service.ensureGuide(painting.getPublicId(), "test-user")),
                    executor.submit(() -> service.ensureGuide(painting.getPublicId(), "test-user")),
                    executor.submit(() -> service.ensureGuide(painting.getPublicId(), "test-user")),
                    executor.submit(() -> service.ensureGuide(painting.getPublicId(), "test-user")));
            assertThat(providerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            for (Future<PaintingGuideOutcome> future : futures) {
                assertThat(future.get().result().summary()).isEqualTo(result.summary());
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(providerCalls).hasValue(1);
        verify(cache, times(1)).saveSuccess(any(), anyString(), anyString());
    }

    private PaintingGuide validRow() {
        return row(codec.encodeCanonical(result, "1", List.of()), SOURCE_HASH);
    }

    private PaintingGuide row(String json, String sourceHash) {
        LocalDateTime now = LocalDateTime.now();
        return PaintingGuide.builder()
                .id(7L)
                .painting(painting)
                .resultJson(json)
                .sourceHash(sourceHash)
                .status(PaintingGuideCacheStore.SUCCESS_STATUS)
                .generatedAt(now)
                .updatedAt(now)
                .build();
    }

    private static PaintingGuideContext context(String paintingId) {
        return new PaintingGuideContext(
                paintingId,
                new PaintingGuideContext.Basic("山水图", "1650", "清朝", "清代", null, null),
                new PaintingGuideContext.Artist("测试画家", null, null, null),
                new PaintingGuideContext.Art(null, "山水", null, null, null, null, null,
                        null, null, null, null, null, null),
                new PaintingGuideContext.OfficialAnnotations("官方注释", "幽远笛声"),
                List.of());
    }

    private static GuideResult validResult() {
        return new GuideResult(
                "1",
                "这是一段有依据的标准导览。",
                new GuideSections("画家与时代", null, null, null, null, "意境说明", null, "音乐联想"),
                List.of("观察构图层次", "留意笔墨节奏"),
                List.of());
    }

    private static void assertGuideCode(Runnable action, ApiErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiV1Exception.class)
                .extracting(exception -> ((ApiV1Exception) exception).getCode())
                .isEqualTo(expected);
    }
}
