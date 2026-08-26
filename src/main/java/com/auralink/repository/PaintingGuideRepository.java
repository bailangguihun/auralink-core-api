package com.auralink.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.auralink.entity.PaintingGuide;

@Repository
public interface PaintingGuideRepository extends JpaRepository<PaintingGuide, Long> {

    Optional<PaintingGuide> findByPublicId(String publicId);

    boolean existsByPublicId(String publicId);

    Optional<PaintingGuide> findByPaintingId(Long paintingId);

    boolean existsByPaintingId(Long paintingId);
}
