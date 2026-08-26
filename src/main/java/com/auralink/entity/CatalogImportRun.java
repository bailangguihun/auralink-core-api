package com.auralink.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "catalog_import_runs",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_catalog_import_runs_public_id",
                columnNames = "public_id"
        ),
        indexes = {
                @Index(name = "idx_catalog_import_runs_status_started", columnList = "status, started_at"),
                @Index(name = "idx_catalog_import_runs_source_sha256", columnList = "source_sha256")
        }
)
public class CatalogImportRun extends BasePublicIdEntity {

    @Column(name = "source_name", nullable = false, length = 512)
    private String sourceName;

    @Column(name = "source_sha256", nullable = false, length = 64)
    private String sourceSha256;

    @Builder.Default
    @Column(name = "total_rows", nullable = false)
    private int totalRows = 0;

    @Builder.Default
    @Column(name = "inserted_rows", nullable = false)
    private int insertedRows = 0;

    @Builder.Default
    @Column(name = "updated_rows", nullable = false)
    private int updatedRows = 0;

    @Builder.Default
    @Column(name = "unchanged_rows", nullable = false)
    private int unchangedRows = 0;

    @Builder.Default
    @Column(name = "matched_images", nullable = false)
    private int matchedImages = 0;

    @Builder.Default
    @Column(name = "missing_images", nullable = false)
    private int missingImages = 0;

    @Builder.Default
    @Column(name = "orphan_images", nullable = false)
    private int orphanImages = 0;

    @Column(nullable = false, length = 64)
    private String status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    protected void initializeStartedAt() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
    }
}
