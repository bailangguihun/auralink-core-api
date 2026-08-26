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
        name = "creations",
        uniqueConstraints = @UniqueConstraint(name = "uq_creations_public_id", columnNames = "public_id"),
        indexes = {
                @Index(name = "idx_creations_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_creations_user_status", columnList = "user_id, status"),
                @Index(name = "idx_creations_workflow", columnList = "workflow_id"),
                @Index(name = "idx_creations_source_painting", columnList = "source_painting_id"),
                @Index(name = "idx_creations_source_asset", columnList = "source_asset_id"),
                @Index(name = "idx_creations_final_asset", columnList = "final_asset_id"),
                @Index(name = "idx_creations_status_created_id", columnList = "status, created_at, id"),
                @Index(name = "idx_creations_status_lease_id", columnList = "status, lease_expires_at, id"),
                @Index(name = "idx_creations_user_created_public", columnList = "user_id, created_at, public_id")
        }
)
public class Creation extends BasePublicIdEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_creations_user")
    )
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "workflow_id",
            foreignKey = @ForeignKey(name = "fk_creations_workflow")
    )
    private UserWorkflow workflow;

    @Column(name = "workflow_snapshot", nullable = false, columnDefinition = "TEXT")
    private String workflowSnapshot;

    @Column(name = "source_modality", nullable = false, length = 64)
    private String sourceModality;

    @Column(name = "source_text", columnDefinition = "TEXT")
    private String sourceText;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "source_painting_id",
            foreignKey = @ForeignKey(name = "fk_creations_source_painting")
    )
    private Painting sourcePainting;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "source_asset_id",
            foreignKey = @ForeignKey(name = "fk_creations_source_asset")
    )
    private MediaAsset sourceAsset;

    @Column(nullable = false, length = 64)
    private String status;

    @Column(name = "final_modality", length = 64)
    private String finalModality;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "final_asset_id",
            foreignKey = @ForeignKey(name = "fk_creations_final_asset")
    )
    private MediaAsset finalAsset;

    @Column(name = "final_output_json", columnDefinition = "TEXT")
    private String finalOutputJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "error_code", length = 128)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "claim_token", length = 64)
    private String claimToken;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "retry_version", nullable = false)
    @lombok.Builder.Default
    private int retryVersion = 0;

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
