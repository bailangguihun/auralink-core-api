package com.auralink.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import com.auralink.entity.Painting;

@Repository
public interface PaintingRepository extends JpaRepository<Painting, Long>, JpaSpecificationExecutor<Painting> {

    Optional<Painting> findByPublicId(String publicId);

    /**
     * Authenticated detail lookup. Visibility and image availability are deliberately
     * not part of this query: authenticated users may open any active official
     * catalog record, including records whose image is missing or hidden from
     * gallery browsing.
     */
    @EntityGraph(attributePaths = "imageAsset")
    Optional<Painting> findByPublicIdAndStatus(String publicId, String status);

    boolean existsByPublicId(String publicId);

    Optional<Painting> findBySourceKey(String sourceKey);

    boolean existsBySourceKey(String sourceKey);

    /** Fetch the optional image asset together with every paged query result. */
    @Override
    @EntityGraph(attributePaths = "imageAsset")
    Page<Painting> findAll(Specification<Painting> specification, Pageable pageable);
}
