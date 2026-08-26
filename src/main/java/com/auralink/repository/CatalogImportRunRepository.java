package com.auralink.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.auralink.entity.CatalogImportRun;

@Repository
public interface CatalogImportRunRepository extends JpaRepository<CatalogImportRun, Long> {

    Optional<CatalogImportRun> findByPublicId(String publicId);

    boolean existsByPublicId(String publicId);

    Optional<CatalogImportRun> findTopBySourceSha256AndStatusOrderByFinishedAtDesc(
            String sourceSha256,
            String status);
}
