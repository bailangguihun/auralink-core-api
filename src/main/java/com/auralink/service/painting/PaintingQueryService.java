package com.auralink.service.painting;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.api.v1.painting.PaintingDetailResponse;
import com.auralink.api.v1.painting.PaintingPageResponse;
import com.auralink.api.v1.painting.PaintingSummaryResponse;
import com.auralink.catalog.DynastyNormalizer;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.entity.Painting;
import com.auralink.entity.PaintingFavorite;
import com.auralink.entity.User;
import com.auralink.repository.PaintingFavoriteRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.service.CurrentUserService;

import lombok.RequiredArgsConstructor;

/** Read-only official catalog queries and current-user favorite projections. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaintingQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 200;
    private static final int MAX_FILTER_LENGTH = 512;
    private static final Map<String, String> SORT_FIELDS = Map.ofEntries(
            Map.entry("source", "sourceKey"),
            Map.entry("title", "title"),
            Map.entry("author", "authorName"),
            Map.entry("dynasty", "creationDynastyNormalized"));

    private final PaintingRepository paintingRepository;
    private final PaintingFavoriteRepository favoriteRepository;
    private final CurrentUserService currentUserService;
    private final PaintingResponseMapper responseMapper;
    private final PaintingProperties paintingProperties;
    private final DynastyNormalizer dynastyNormalizer;

    public PaintingPageResponse listPaintings(
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
            String collectionPlatform,
            int page,
            int size,
            String sort,
            String direction) {
        validatePage(page, size);
        String safeKeyword = normalizeFilter(keyword, "keyword", MAX_KEYWORD_LENGTH);
        String safeDynasty = normalizedDynasty(dynasty);
        String safeCategory = normalizeFilter(category, "category", MAX_FILTER_LENGTH);
        String safeAuthor = normalizeFilter(author, "author", MAX_FILTER_LENGTH);
        String safeSubject = normalizeFilter(subject, "subject", MAX_FILTER_LENGTH);
        String safePaintingSchool = normalizeFilter(
                paintingSchool, "paintingSchool", MAX_FILTER_LENGTH);
        String safeStyle = normalizeFilter(style, "style", MAX_FILTER_LENGTH);
        String safeArtisticConception = normalizeFilter(
                artisticConception, "artisticConception", MAX_FILTER_LENGTH);
        String safePaintingMaterial = normalizeFilter(
                paintingMaterial, "paintingMaterial", MAX_FILTER_LENGTH);
        String safeCollectionInstitution = normalizeFilter(
                collectionInstitution, "collectionInstitution", MAX_FILTER_LENGTH);
        String safeCollectionPlatform = normalizeFilter(
                collectionPlatform, "collectionPlatform", MAX_FILTER_LENGTH);

        Specification<Painting> specification = PaintingSpecifications.gallery(
                safeKeyword,
                safeDynasty,
                safeCategory,
                safeAuthor,
                safeSubject,
                safePaintingSchool,
                safeStyle,
                safeArtisticConception,
                safePaintingMaterial,
                safeCollectionInstitution,
                safeCollectionPlatform);
        Page<Painting> paintings = paintingRepository.findAll(
                specification,
                PageRequest.of(page, size, sort(sort, direction)));

        Set<String> favoriteIds = favoritedIds(
                currentUserService.findCurrentUser(), paintings.getContent());
        List<PaintingSummaryResponse> items = paintings.getContent().stream()
                .map(painting -> responseMapper.toSummary(
                        painting, favoriteIds.contains(painting.getPublicId())))
                .toList();
        return PaintingPageResponse.from(paintings, items);
    }

    public PaintingSummaryResponse getDailyPainting() {
        Specification<Painting> specification = PaintingSpecifications.gallery(
                null, null, null, null, null, null, null, null, null, null, null);
        long total = paintingRepository.count(specification);
        if (total == 0) {
            throw paintingNotFound();
        }

        long index = Math.floorMod(
                LocalDate.now(paintingProperties.getDailyZone()).toEpochDay(), total);
        Page<Painting> result = paintingRepository.findAll(
                specification,
                PageRequest.of(
                        Math.toIntExact(index),
                        1,
                        Sort.by(Sort.Direction.ASC, "sourceKey")
                                .and(Sort.by(Sort.Direction.ASC, "publicId"))));
        Painting painting = result.stream().findFirst().orElseThrow(PaintingQueryService::paintingNotFound);
        boolean favorited = currentUserService.findCurrentUser()
                .map(user -> favoriteRepository.existsByUserIdAndPaintingId(
                        user.getId(), painting.getId()))
                .orElse(false);
        return responseMapper.toSummary(painting, favorited);
    }

    public PaintingDetailResponse getPainting(String paintingId) {
        User user = currentUserService.requireCurrentUser();
        Painting painting = requireActivePainting(paintingId);
        boolean favorited = favoriteRepository.existsByUserIdAndPaintingId(
                user.getId(), painting.getId());
        return responseMapper.toDetail(painting, favorited);
    }

    public PaintingPageResponse listCurrentUserFavorites(int page, int size) {
        validatePage(page, size);
        User user = currentUserService.requireCurrentUser();
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.ASC, "publicId"));
        Page<PaintingFavorite> favorites = favoriteRepository.findByUserIdAndPaintingStatus(
                user.getId(),
                PaintingSpecifications.ACTIVE_STATUS,
                PageRequest.of(page, size, sort));
        List<PaintingSummaryResponse> items = favorites.getContent().stream()
                .map(PaintingFavorite::getPainting)
                .map(painting -> responseMapper.toSummary(painting, true))
                .toList();
        return PaintingPageResponse.from(favorites, items);
    }

    /** Internal lookup shared with favorite mutations; hidden/missing-image rows remain valid. */
    public Painting requireActivePainting(String paintingId) {
        String canonicalId = requireCanonicalUuid(paintingId);
        return paintingRepository.findByPublicIdAndStatus(
                        canonicalId, PaintingSpecifications.ACTIVE_STATUS)
                .orElseThrow(PaintingQueryService::paintingNotFound);
    }

    private Set<String> favoritedIds(Optional<User> user, List<Painting> paintings) {
        if (user.isEmpty() || paintings.isEmpty()) {
            return Set.of();
        }
        List<Long> paintingIds = paintings.stream().map(Painting::getId).toList();
        return favoriteRepository.findFavoritedPaintingPublicIds(
                        user.orElseThrow().getId(), paintingIds)
                .stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    private Sort sort(String requestedSort, String requestedDirection) {
        String sortName = requestedSort == null || requestedSort.isBlank()
                ? "source"
                : requestedSort.trim();
        String property = SORT_FIELDS.get(sortName);
        if (property == null) {
            throw invalidSort("不支持的排序字段");
        }

        String directionName = requestedDirection == null || requestedDirection.isBlank()
                ? "asc"
                : requestedDirection.trim().toLowerCase(Locale.ROOT);
        Sort.Direction direction = switch (directionName) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw invalidSort("排序方向必须为 asc 或 desc");
        };
        Sort requested = Sort.by(direction, property);
        return "publicId".equals(property)
                ? requested
                : requested.and(Sort.by(Sort.Direction.ASC, "publicId"));
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw invalidQuery("page 不能小于 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiV1Exception(
                    HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PAGE_SIZE,
                    "size 必须在 1 到 100 之间");
        }
    }

    private String normalizeFilter(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw invalidQuery(field + " 长度超过允许上限");
        }
        return normalized;
    }

    private String normalizedDynasty(String value) {
        String filtered = normalizeFilter(value, "dynasty", MAX_FILTER_LENGTH);
        if (filtered == null) {
            return null;
        }
        String normalized = dynastyNormalizer.normalize(filtered);
        return normalized == null || normalized.isBlank() ? null : normalized;
    }

    private String requireCanonicalUuid(String value) {
        if (value == null || value.isBlank()) {
            throw invalidPaintingId();
        }
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value.toLowerCase(Locale.ROOT))) {
                throw invalidPaintingId();
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw invalidPaintingId();
        }
    }

    private static ApiV1Exception invalidPaintingId() {
        return new ApiV1Exception(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.INVALID_PAINTING_ID,
                "画作标识格式无效");
    }

    private static ApiV1Exception paintingNotFound() {
        return new ApiV1Exception(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.PAINTING_NOT_FOUND,
                "画作不存在或当前不可用");
    }

    private static ApiV1Exception invalidSort(String message) {
        return new ApiV1Exception(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_SORT, message);
    }

    private static ApiV1Exception invalidQuery(String message) {
        return new ApiV1Exception(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.INVALID_PAINTING_QUERY,
                message);
    }
}
