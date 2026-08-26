package com.auralink.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
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
        name = "painting_guides",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_painting_guides_public_id", columnNames = "public_id"),
                @UniqueConstraint(name = "uq_painting_guides_painting", columnNames = "painting_id")
        },
        indexes = {
                @Index(name = "idx_painting_guides_status", columnList = "status"),
                @Index(name = "idx_painting_guides_source_hash", columnList = "source_hash")
        }
)
public class PaintingGuide extends BasePublicIdEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "painting_id",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(name = "fk_painting_guides_painting")
    )
    private Painting painting;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "source_hash", length = 64)
    private String sourceHash;

    @Column(nullable = false, length = 64)
    private String status;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void initializeUpdatedAt() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void updateTimestamp() {
        updatedAt = LocalDateTime.now();
    }
}
