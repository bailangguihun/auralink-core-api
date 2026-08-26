package com.auralink.service.painting;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.auralink.entity.Painting;
import com.auralink.entity.User;
import com.auralink.repository.PaintingFavoriteRepository;
import com.auralink.service.CurrentUserService;

import lombok.RequiredArgsConstructor;

/** Idempotent current-user favorite mutations for official paintings. */
@Service
@RequiredArgsConstructor
public class PaintingFavoriteService {

    private final PaintingFavoriteRepository favoriteRepository;
    private final PaintingQueryService paintingQueryService;
    private final CurrentUserService currentUserService;

    @Transactional
    public void favorite(String paintingId) {
        User user = currentUserService.requireCurrentUser();
        Painting painting = paintingQueryService.requireActivePainting(paintingId);
        favoriteRepository.insertIfAbsent(
                UUID.randomUUID().toString(),
                user.getId(),
                painting.getId(),
                LocalDateTime.now());
    }

    @Transactional
    public void unfavorite(String paintingId) {
        User user = currentUserService.requireCurrentUser();
        Painting painting = paintingQueryService.requireActivePainting(paintingId);
        favoriteRepository.deleteByUserIdAndPaintingId(user.getId(), painting.getId());
    }
}
