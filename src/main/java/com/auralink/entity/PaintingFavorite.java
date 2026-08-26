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
        name = "painting_favorites",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_painting_favorites_public_id", columnNames = "public_id"),
                @UniqueConstraint(
                        name = "uq_painting_favorites_user_painting",
                        columnNames = {"user_id", "painting_id"}
                )
        },
        indexes = {
                @Index(name = "idx_painting_favorites_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_painting_favorites_painting_user", columnList = "painting_id, user_id")
        }
)
public class PaintingFavorite extends BasePublicIdEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_painting_favorites_user")
    )
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "painting_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_painting_favorites_painting")
    )
    private Painting painting;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void initializeCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
