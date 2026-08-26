package com.auralink.service.painting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.auralink.entity.Painting;
import com.auralink.media.MediaAssetValues;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/** Composable, parameter-bound gallery filters for official paintings. */
public final class PaintingSpecifications {

    static final String ACTIVE_STATUS = "ACTIVE";

    private static final List<String> KEYWORD_FIELDS = List.of(
            "title",
            "authorName",
            "subject",
            "paintingSchool",
            "style",
            "composition",
            "artisticConception",
            "brushwork",
            "inkMethod",
            "culturalSymbol",
            "generatedText",
            "musicSceneDescription",
            "collectionInstitution");

    private PaintingSpecifications() {
    }

    public static Specification<Painting> gallery(
            String keyword,
            String dynasty,
            String category,
            String author,
            String subject,
            String paintingSchool,
            String style,
            String artisticConception,
            String paintingMaterial,
            String collectionInstitution,
            String collectionPlatform) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("status"), ACTIVE_STATUS));
            predicates.add(criteriaBuilder.isTrue(root.get("visibleInGallery")));
            predicates.add(criteriaBuilder.isTrue(root.get("imageAvailable")));
            predicates.add(criteriaBuilder.equal(
                    root.get("imageAsset").get("status"), MediaAssetValues.Status.ACTIVE));
            predicates.add(criteriaBuilder.equal(
                    root.get("imageAsset").get("visibility"), MediaAssetValues.Visibility.PUBLIC));

            addKeywordPredicate(predicates, root, criteriaBuilder, keyword);
            addExactPredicate(predicates, root, criteriaBuilder,
                    "creationDynastyNormalized", dynasty);
            addExactPredicate(predicates, root, criteriaBuilder, "category", category);
            addContainsPredicate(predicates, root, criteriaBuilder, "authorName", author);
            addContainsPredicate(predicates, root, criteriaBuilder, "subject", subject);
            addContainsPredicate(predicates, root, criteriaBuilder, "paintingSchool", paintingSchool);
            addContainsPredicate(predicates, root, criteriaBuilder, "style", style);
            addContainsPredicate(predicates, root, criteriaBuilder,
                    "artisticConception", artisticConception);
            addContainsPredicate(predicates, root, criteriaBuilder,
                    "paintingMaterial", paintingMaterial);
            addContainsPredicate(predicates, root, criteriaBuilder,
                    "collectionInstitution", collectionInstitution);
            addContainsPredicate(predicates, root, criteriaBuilder,
                    "collectionPlatform", collectionPlatform);

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static void addKeywordPredicate(
            List<Predicate> predicates,
            Root<Painting> root,
            CriteriaBuilder criteriaBuilder,
            String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return;
        }
        String pattern = containsPattern(normalized);
        Predicate[] alternatives = KEYWORD_FIELDS.stream()
                .map(field -> criteriaBuilder.like(
                        criteriaBuilder.lower(root.get(field)), pattern, '\\'))
                .toArray(Predicate[]::new);
        predicates.add(criteriaBuilder.or(alternatives));
    }

    private static void addExactPredicate(
            List<Predicate> predicates,
            Root<Painting> root,
            CriteriaBuilder criteriaBuilder,
            String field,
            String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            predicates.add(criteriaBuilder.equal(
                    criteriaBuilder.lower(root.get(field)), normalized));
        }
    }

    private static void addContainsPredicate(
            List<Predicate> predicates,
            Root<Painting> root,
            CriteriaBuilder criteriaBuilder,
            String field,
            String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            predicates.add(criteriaBuilder.like(
                    criteriaBuilder.lower(root.get(field)),
                    containsPattern(normalized),
                    '\\'));
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String containsPattern(String normalized) {
        String escaped = normalized
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
