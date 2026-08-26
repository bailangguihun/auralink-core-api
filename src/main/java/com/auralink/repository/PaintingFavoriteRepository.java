package com.auralink.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.auralink.entity.PaintingFavorite;

@Repository
public interface PaintingFavoriteRepository extends JpaRepository<PaintingFavorite, Long> {

    Optional<PaintingFavorite> findByPublicId(String publicId);

    boolean existsByPublicId(String publicId);

    boolean existsByUserIdAndPaintingId(Long userId, Long paintingId);

    Optional<PaintingFavorite> findByUserIdAndPaintingId(Long userId, Long paintingId);

    /**
     * SQLite's INSERT OR IGNORE makes PUT favorite concurrency-safe and
     * idempotent without catching a constraint violation in a rollback-only
     * JPA transaction.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT OR IGNORE INTO painting_favorites
                (public_id, user_id, painting_id, created_at)
            VALUES
                (:publicId, :userId, :paintingId, :createdAt)
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("publicId") String publicId,
            @Param("userId") Long userId,
            @Param("paintingId") Long paintingId,
            @Param("createdAt") LocalDateTime createdAt);

    long deleteByUserIdAndPaintingId(Long userId, Long paintingId);

    @EntityGraph(attributePaths = {"painting", "painting.imageAsset"})
    Page<PaintingFavorite> findByUserIdAndPaintingStatus(
            Long userId,
            String paintingStatus,
            Pageable pageable);

    @Query("""
            SELECT favorite.painting.publicId
            FROM PaintingFavorite favorite
            WHERE favorite.user.id = :userId
              AND favorite.painting.id IN :paintingIds
            """)
    List<String> findFavoritedPaintingPublicIds(
            @Param("userId") Long userId,
            @Param("paintingIds") Collection<Long> paintingIds);
}
