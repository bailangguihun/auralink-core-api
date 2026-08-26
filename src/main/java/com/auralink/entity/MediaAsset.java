package com.auralink.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
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
        name = "media_assets",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_media_assets_public_id", columnNames = "public_id"),
                @UniqueConstraint(name = "uq_media_assets_storage_key", columnNames = "storage_key")
        },
        indexes = {
                @Index(name = "idx_media_assets_owner_created", columnList = "owner_user_id, created_at"),
                @Index(name = "idx_media_assets_type_status", columnList = "asset_type, semantic_type, status"),
                @Index(name = "idx_media_assets_sha256", columnList = "sha256")
        }
)
public class MediaAsset extends BasePublicIdEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "owner_user_id",
            foreignKey = @ForeignKey(name = "fk_media_assets_owner_user")
    )
    private User ownerUser;

    @Column(name = "storage_key", nullable = false, length = 1024)
    private String storageKey;

    @Column(name = "original_filename", nullable = false, length = 512)
    private String originalFilename;

    @Column(name = "mime_type", length = 255)
    private String mimeType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(length = 64)
    private String sha256;

    private Integer width;

    private Integer height;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    @Column(name = "asset_type", nullable = false, length = 64)
    private String assetType;

    @Column(name = "semantic_type", nullable = false, length = 64)
    private String semanticType;

    @Column(name = "source_type", nullable = false, length = 64)
    private String sourceType;

    @Column(nullable = false, length = 64)
    private String visibility;

    @Column(nullable = false, length = 64)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void initializeTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void updateTimestamp() {
        updatedAt = LocalDateTime.now();
    }
}
