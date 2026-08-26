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
        name = "creation_favorites",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_creation_favorites_public_id", columnNames = "public_id"),
                @UniqueConstraint(
                        name = "uq_creation_favorites_user_creation",
                        columnNames = {"user_id", "creation_id"}
                )
        },
        indexes = {
                @Index(name = "idx_creation_favorites_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_creation_favorites_user_created_public", columnList = "user_id, created_at, public_id"),
                @Index(name = "idx_creation_favorites_creation_user", columnList = "creation_id, user_id")
        }
)
public class CreationFavorite extends BasePublicIdEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_creation_favorites_user")
    )
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "creation_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_creation_favorites_creation")
    )
    private Creation creation;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void initializeCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
