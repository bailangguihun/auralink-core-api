package com.auralink.guide.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.auralink.entity.Painting;
import com.auralink.entity.PaintingGuide;
import com.auralink.repository.PaintingGuideRepository;

import lombok.RequiredArgsConstructor;

/**
 * Short database transactions for the persistent standard-guide cache.
 *
 * <p>Provider calls deliberately live outside this class so a slow upstream
 * request never holds a SQLite write transaction open.</p>
 */
@Service
@RequiredArgsConstructor
public class PaintingGuideCacheStore {

    public static final String SUCCESS_STATUS = "SUCCESS";

    private final PaintingGuideRepository repository;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<PaintingGuide> findByPaintingId(Long paintingId) {
        return repository.findByPaintingId(paintingId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaintingGuide saveSuccess(Painting painting, String sourceHash, String canonicalResultJson) {
        PaintingGuide guide = repository.findByPaintingId(painting.getId())
                .orElseGet(() -> PaintingGuide.builder().painting(painting).build());
        LocalDateTime now = LocalDateTime.now();
        guide.setSourceHash(sourceHash);
        guide.setResultJson(canonicalResultJson);
        guide.setStatus(SUCCESS_STATUS);
        guide.setGeneratedAt(now);
        guide.setUpdatedAt(now);
        return repository.saveAndFlush(guide);
    }
}
