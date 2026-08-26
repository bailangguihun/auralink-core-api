package com.auralink.guide.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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
import com.auralink.guide.knowledge.StaticKnowledgeLoader.KnowledgeLoadingException;
import com.auralink.guide.model.GuideResult;
import com.auralink.guide.model.GuideResultCodec;
import com.auralink.guide.model.GuideResultValidationException;
import com.auralink.guide.provider.GuideGenerationResult;
import com.auralink.guide.provider.GuideProvider;
import com.auralink.guide.provider.GuideProviderException;
import com.auralink.service.painting.PaintingQueryService;

import lombok.RequiredArgsConstructor;

/** Builds, validates, and persistently caches one standard Guide per Painting. */
@Service
@RequiredArgsConstructor
public class PaintingGuideService {

    private final PaintingQueryService paintingQueryService;
    private final PaintingGuideContextBuilder contextBuilder;
    private final KnowledgeContextBuilder knowledgeContextBuilder;
    private final GuideSourceHasher sourceHasher;
    private final GuideResultCodec resultCodec;
    private final GuideProvider guideProvider;
    private final PaintingGuideCacheStore cacheStore;
    private final PaintingGuideLockRegistry lockRegistry;
    private final PaintingGuideGenerationGuard generationGuard;
    private final GuideProperties properties;

    /** Cache-only read: this method never calls a provider and never writes a row. */
    public PaintingGuideOutcome getCurrentGuide(String paintingId) {
        PreparedGuide prepared = prepare(paintingId);
        return current(prepared, GuideCacheStatus.HIT).orElseThrow(PaintingGuideService::notAvailable);
    }

    /** Return a valid cache entry or generate exactly one standard guide. */
    public PaintingGuideOutcome ensureGuide(String paintingId, String requester) {
        PreparedGuide initial = prepare(paintingId);
        Optional<PaintingGuideOutcome> cached = current(initial, GuideCacheStatus.HIT);
        if (cached.isPresent()) {
            return cached.orElseThrow();
        }
        requireGenerationEnabled();

        return lockRegistry.withPaintingLock(initial.painting().getPublicId(), () -> {
            // Rebuild inside the lock so waiting callers observe annotation/knowledge changes.
            PreparedGuide prepared = prepare(initial.painting().getPublicId());
            Optional<PaintingGuideOutcome> secondRead = current(prepared, GuideCacheStatus.HIT);
            if (secondRead.isPresent()) {
                return secondRead.orElseThrow();
            }
            requireGenerationEnabled();
            // Charge only the caller that will actually make a paid provider call;
            // same-Painting waiters reuse the newly persisted guide for free.
            return generationGuard.withPaidGeneration(
                    requester, () -> generateAndStore(prepared));
        });
    }

    /** Validate the public Painting ID before returning the intentionally reserved TTS error. */
    public void requirePaintingForReservedAudio(String paintingId) {
        paintingQueryService.requireActivePainting(paintingId);
        throw new ApiV1Exception(
                HttpStatus.NOT_IMPLEMENTED,
                ApiErrorCode.GUIDE_TTS_NOT_ENABLED,
                "画作导览语音功能尚未启用");
    }

    private PreparedGuide prepare(String paintingId) {
        Painting painting = paintingQueryService.requireActivePainting(paintingId);
        try {
            PaintingGuideContext base = contextBuilder.build(painting);
            KnowledgeSelection selection = knowledgeContextBuilder.build(base);
            PaintingGuideContext context = base.withKnowledge(selection.items());
            String sourceHash = sourceHasher.hash(properties.getSchemaVersion(), context, selection);
            return new PreparedGuide(painting, context, selection, sourceHash);
        } catch (KnowledgeLoadingException | IllegalArgumentException exception) {
            throw new ApiV1Exception(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCode.GUIDE_CONTEXT_INVALID,
                    "画作导览上下文当前不可用");
        }
    }

    private Optional<PaintingGuideOutcome> current(
            PreparedGuide prepared,
            GuideCacheStatus cacheStatus) {
        return cacheStore.findByPaintingId(prepared.painting().getId())
                .flatMap(row -> decodeCurrent(row, prepared, cacheStatus));
    }

    private Optional<PaintingGuideOutcome> decodeCurrent(
            PaintingGuide row,
            PreparedGuide prepared,
            GuideCacheStatus cacheStatus) {
        if (!PaintingGuideCacheStore.SUCCESS_STATUS.equals(row.getStatus())
                || !prepared.sourceHash().equals(row.getSourceHash())
                || row.getGeneratedAt() == null
                || row.getUpdatedAt() == null) {
            return Optional.empty();
        }
        try {
            GuideResult result = resultCodec.decode(
                    row.getResultJson(), properties.getSchemaVersion(), prepared.context().knowledge());
            return Optional.of(new PaintingGuideOutcome(
                    prepared.painting().getPublicId(),
                    result,
                    cacheStatus,
                    row.getGeneratedAt(),
                    row.getUpdatedAt()));
        } catch (GuideResultValidationException exception) {
            // Corrupt or obsolete cached JSON is never returned. POST may regenerate it.
            return Optional.empty();
        }
    }

    private PaintingGuideOutcome generateAndStore(PreparedGuide prepared) {
        String requestId = UUID.randomUUID().toString();
        GuideGenerationResult generated;
        try {
            generated = guideProvider.generate(requestId, prepared.context());
        } catch (GuideProviderException exception) {
            throw publicProviderFailure(exception);
        }
        if (generated == null || !requestId.equals(generated.requestId())) {
            throw invalidProviderResponse();
        }

        final String canonicalJson;
        try {
            canonicalJson = resultCodec.encodeCanonical(
                    generated.result(), properties.getSchemaVersion(), prepared.context().knowledge());
        } catch (GuideResultValidationException exception) {
            throw invalidProviderResponse();
        }

        try {
            cacheStore.saveSuccess(prepared.painting(), prepared.sourceHash(), canonicalJson);
            // Re-read after the REQUIRES_NEW write commits so GENERATED is built from
            // SQLite's final millisecond-precision representation, just like HIT and GET.
            return current(prepared, GuideCacheStatus.GENERATED).orElseThrow(
                    PaintingGuideService::invalidProviderResponse);
        } catch (DataIntegrityViolationException exception) {
            // The DB uniqueness constraint is the final authority if another caller won a race.
            return current(prepared, GuideCacheStatus.HIT).orElseThrow(
                    PaintingGuideService::invalidProviderResponse);
        }
    }

    private void requireGenerationEnabled() {
        if (!properties.isEnabled()
                || properties.getInternalToken() == null
                || properties.getInternalToken().isBlank()) {
            throw new ApiV1Exception(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCode.GUIDE_DISABLED,
                    "画作导览生成功能当前未启用");
        }
    }

    private ApiV1Exception publicProviderFailure(GuideProviderException exception) {
        return switch (exception.getFailure()) {
            case CONFIGURATION -> new ApiV1Exception(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCode.GUIDE_DISABLED,
                    "画作导览生成功能当前未配置");
            case TIMEOUT -> new ApiV1Exception(
                    HttpStatus.GATEWAY_TIMEOUT,
                    ApiErrorCode.GUIDE_PROVIDER_TIMEOUT,
                    "画作导览生成超时");
            case UNAVAILABLE -> new ApiV1Exception(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCode.GUIDE_PROVIDER_UNAVAILABLE,
                    "画作导览生成服务暂时不可用");
            case REJECTED -> new ApiV1Exception(
                    HttpStatus.BAD_GATEWAY,
                    ApiErrorCode.GUIDE_PROVIDER_REJECTED,
                    "画作导览生成请求未被上游接受");
            case INVALID_RESPONSE -> invalidProviderResponse();
        };
    }

    private static ApiV1Exception notAvailable() {
        return new ApiV1Exception(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.GUIDE_NOT_AVAILABLE,
                "该画作尚无可用的标准导览");
    }

    private static ApiV1Exception invalidProviderResponse() {
        return new ApiV1Exception(
                HttpStatus.BAD_GATEWAY,
                ApiErrorCode.GUIDE_INVALID_RESPONSE,
                "画作导览服务返回了无效结果");
    }

    private record PreparedGuide(
            Painting painting,
            PaintingGuideContext context,
            KnowledgeSelection selection,
            String sourceHash) {
    }
}
