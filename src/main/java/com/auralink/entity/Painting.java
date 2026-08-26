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
        name = "paintings",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_paintings_public_id", columnNames = "public_id"),
                @UniqueConstraint(name = "uq_paintings_source_key", columnNames = "source_key")
        },
        indexes = {
                @Index(name = "idx_paintings_image_storage_name", columnList = "image_storage_name"),
                @Index(name = "idx_paintings_gallery_status", columnList = "visible_in_gallery, status"),
                @Index(name = "idx_paintings_dynasty", columnList = "creation_dynasty_normalized"),
                @Index(name = "idx_paintings_category", columnList = "category"),
                @Index(name = "idx_paintings_author_name", columnList = "author_name"),
                @Index(name = "idx_paintings_image_asset", columnList = "image_asset_id")
        }
)
public class Painting extends BasePublicIdEntity {

    @Column(name = "source_key", nullable = false, length = 1024)
    private String sourceKey;

    @Column(name = "source_sequence", length = 64)
    private String sourceSequence;

    @Column(name = "image_storage_name", nullable = false, length = 512)
    private String imageStorageName;

    @Column(length = 512)
    private String title;

    @Column(name = "author_name", length = 255)
    private String authorName;

    @Column(name = "author_birth_year", length = 64)
    private String authorBirthYear;

    @Column(name = "author_birth_place", length = 512)
    private String authorBirthPlace;

    @Column(name = "author_school", length = 255)
    private String authorSchool;

    @Column(name = "creation_year", length = 255)
    private String creationYear;

    @Column(name = "creation_dynasty_raw", length = 255)
    private String creationDynastyRaw;

    @Column(name = "creation_dynasty_normalized", length = 255)
    private String creationDynastyNormalized;

    @Column(name = "actual_size", length = 255)
    private String actualSize;

    @Column(name = "collection_institution", length = 512)
    private String collectionInstitution;

    @Column(length = 255)
    private String category;

    @Column(length = 512)
    private String subject;

    @Column(name = "painting_school", length = 255)
    private String paintingSchool;

    @Column(columnDefinition = "TEXT")
    private String style;

    @Column(columnDefinition = "TEXT")
    private String color;

    @Column(columnDefinition = "TEXT")
    private String composition;

    @Column(name = "artistic_conception", columnDefinition = "TEXT")
    private String artisticConception;

    @Column(columnDefinition = "TEXT")
    private String brushwork;

    @Column(name = "ink_method", columnDefinition = "TEXT")
    private String inkMethod;

    @Column(name = "painting_material", columnDefinition = "TEXT")
    private String paintingMaterial;

    @Column(columnDefinition = "TEXT")
    private String pigment;

    @Column(columnDefinition = "TEXT")
    private String seal;

    @Column(name = "cultural_symbol", columnDefinition = "TEXT")
    private String culturalSymbol;

    @Column(name = "generated_text", columnDefinition = "TEXT")
    private String generatedText;

    @Column(name = "music_scene_description", columnDefinition = "TEXT")
    private String musicSceneDescription;

    @Column(name = "collection_platform", length = 512)
    private String collectionPlatform;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "image_asset_id",
            foreignKey = @ForeignKey(name = "fk_paintings_image_asset")
    )
    private MediaAsset imageAsset;

    @Builder.Default
    @Column(name = "image_available", nullable = false)
    private boolean imageAvailable = false;

    @Builder.Default
    @Column(name = "visible_in_gallery", nullable = false)
    private boolean visibleInGallery = true;

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
