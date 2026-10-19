package com.auralink.service.painting;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.api.v1.painting.PaintingAnnotationResponse;
import com.auralink.api.v1.painting.PaintingDetailResponse;
import com.auralink.api.v1.painting.PaintingImageResponse;
import com.auralink.api.v1.painting.PaintingPageResponse;
import com.auralink.api.v1.painting.PaintingSummaryResponse;
import com.auralink.catalog.DynastyNormalizer;
import com.auralink.catalogauth.CatalogAuthStore;
import com.auralink.catalogread.CatalogReadStore;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.entity.BasePublicIdEntity;
import com.auralink.entity.Painting;
import com.auralink.entity.PaintingFavorite;
import com.auralink.entity.User;
import com.auralink.repository.PaintingFavoriteRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.service.CurrentUserService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PaintingQueryService {
   private static final int MAX_PAGE_SIZE = 100;
   private static final int MAX_KEYWORD_LENGTH = 200;
   private static final int MAX_FILTER_LENGTH = 512;
   private static final Map<String, String> SORT_FIELDS = Map.ofEntries(
      Map.entry("source", "sourceKey"), Map.entry("title", "title"), Map.entry("author", "authorName"), Map.entry("dynasty", "creationDynastyNormalized")
   );
   private final PaintingRepository paintingRepository;
   private final PaintingFavoriteRepository favoriteRepository;
   private final CurrentUserService currentUserService;
   private final PaintingResponseMapper responseMapper;
   private final PaintingProperties paintingProperties;
   private final DynastyNormalizer dynastyNormalizer;
   private final ObjectProvider<CatalogReadStore> catalogReadStoreProvider;
   private final ObjectProvider<CatalogAuthStore> catalogAuthStoreProvider;
   private final Clock clock;

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
      String direction
   ) {
      return this.listPaintings(keyword, dynasty, category, author, subject, paintingSchool, style,
         artisticConception, paintingMaterial, collectionInstitution, collectionPlatform, page, size, sort, direction, null);
   }

   public PaintingPageResponse listPaintings(
      String keyword, String dynasty, String category, String author, String subject, String paintingSchool,
      String style, String artisticConception, String paintingMaterial, String collectionInstitution,
      String collectionPlatform, int page, int size, String sort, String direction, String requestedOrderDate
   ) {
      this.validatePage(page, size);
      String safeKeyword = this.normalizeFilter(keyword, "keyword", 200);
      String safeDynasty = this.normalizedDynasty(dynasty);
      String safeCategory = this.normalizeFilter(category, "category", 512);
      String safeAuthor = this.normalizeFilter(author, "author", 512);
      String safeSubject = this.normalizeFilter(subject, "subject", 512);
      String safePaintingSchool = this.normalizeFilter(paintingSchool, "paintingSchool", 512);
      String safeStyle = this.normalizeFilter(style, "style", 512);
      String safeArtisticConception = this.normalizeFilter(artisticConception, "artisticConception", 512);
      String safePaintingMaterial = this.normalizeFilter(paintingMaterial, "paintingMaterial", 512);
      String safeCollectionInstitution = this.normalizeFilter(collectionInstitution, "collectionInstitution", 512);
      String safeCollectionPlatform = this.normalizeFilter(collectionPlatform, "collectionPlatform", 512);
      String sortName = normalizedSort(sort);
      CatalogReadStore catalogReadStore = (CatalogReadStore)this.catalogReadStoreProvider.getIfAvailable();
      if (catalogReadStore != null) {
         boolean daily = "daily".equals(sortName);
         this.sort(daily ? "source" : sortName, direction);
         LocalDate orderDate = daily ? this.currentOrderDate(requestedOrderDate) : null;
         // A new day starts a new result sequence. Never return a later page from a
         // different date than the client used for its earlier pages.
         int effectivePage = daily && !orderDate.toString().equals(requestedOrderDate) ? 0 : page;
         CatalogReadStore.CatalogPage catalogPage = catalogReadStore.list(
            new CatalogReadStore.CatalogQuery(
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
               safeCollectionPlatform,
               effectivePage,
               size,
               sortName,
               normalizedDirection(direction),
               orderDate
            )
         );
         return this.catalogPage(catalogPage, this.catalogFavoritedIds(this.currentUserService.findCurrentUser(), catalogPage.items()))
            .withOrderDate(orderDate == null ? null : orderDate.toString());
      } else {
         if ("daily".equals(sortName)) {
            throw invalidSort("每日排序需要 PostgreSQL 画作目录");
         }
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
            safeCollectionPlatform
         );
         Page<Painting> paintings = this.paintingRepository.findAll(specification, PageRequest.of(page, size, this.sort(sort, direction)));
         Set<String> favoriteIds = this.favoritedIds(this.currentUserService.findCurrentUser(), paintings.getContent());
         List<PaintingSummaryResponse> items = paintings.getContent()
            .stream()
            .map(painting -> this.responseMapper.toSummary(painting, favoriteIds.contains(painting.getPublicId())))
            .toList();
         return PaintingPageResponse.from(paintings, items);
      }
   }

   private LocalDate currentOrderDate(String requested) {
      if (requested != null) {
         try {
            if (!requested.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !LocalDate.parse(requested).toString().equals(requested)) {
               throw invalidQuery("orderDate 必须为有效的 YYYY-MM-DD 日期");
            }
         } catch (DateTimeParseException exception) {
            throw invalidQuery("orderDate 必须为有效的 YYYY-MM-DD 日期");
         }
      }
      return LocalDate.now(this.clock.withZone(this.paintingProperties.getDailyZone()));
   }

   public PaintingSummaryResponse getDailyPainting() {
      CatalogReadStore catalogReadStore = (CatalogReadStore)this.catalogReadStoreProvider.getIfAvailable();
      if (catalogReadStore != null) {
         return this.catalogDaily(catalogReadStore, this.currentUserService.findCurrentUser());
      }

      Specification<Painting> specification = PaintingSpecifications.gallery(null, null, null, null, null, null, null, null, null, null, null);
      long total = this.paintingRepository.count(specification);
      if (total == 0L) {
         throw paintingNotFound();
      }

      long index = Math.floorMod(LocalDate.now(this.paintingProperties.getDailyZone()).toEpochDay(), total);
      Page<Painting> result = this.paintingRepository
         .findAll(
            specification,
            PageRequest.of(Math.toIntExact(index), 1, Sort.by(Direction.ASC, new String[]{"sourceKey"}).and(Sort.by(Direction.ASC, new String[]{"publicId"})))
         );
      Painting painting = (Painting)result.stream().findFirst().orElseThrow(PaintingQueryService::paintingNotFound);
      boolean favorited = this.currentUserService
         .findCurrentUser()
         .map(user -> this.favoriteRepository.existsByUserIdAndPaintingId(user.getId(), painting.getId()))
         .orElse(false);
      return this.responseMapper.toSummary(painting, favorited);
   }

   public PaintingDetailResponse getPainting(String paintingId) {
      User user = this.currentUserService.requireCurrentUser();
      CatalogReadStore catalogReadStore = (CatalogReadStore)this.catalogReadStoreProvider.getIfAvailable();
      if (catalogReadStore == null) {
         Painting painting = this.requireActivePainting(paintingId);
         boolean favorited = this.favoriteRepository.existsByUserIdAndPaintingId(user.getId(), painting.getId());
         return this.responseMapper.toDetail(painting, favorited);
      } else {
         String canonicalId = this.requireCanonicalUuid(paintingId);
         CatalogReadStore.CatalogPainting painting = catalogReadStore.findPainting(canonicalId).orElseThrow(PaintingQueryService::paintingNotFound);
         CatalogAuthStore catalogAuthStore = (CatalogAuthStore)this.catalogAuthStoreProvider.getIfAvailable();
         return this.catalogDetail(painting, catalogAuthStore != null && catalogAuthStore.isFavorited(user.getId(), canonicalId));
      }
   }

   public PaintingPageResponse listCurrentUserFavorites(int page, int size) {
      this.validatePage(page, size);
      CatalogReadStore catalogReadStore = (CatalogReadStore)this.catalogReadStoreProvider.getIfAvailable();
      if (catalogReadStore != null) {
         CatalogAuthStore catalogAuthStore = (CatalogAuthStore)this.catalogAuthStoreProvider.getIfAvailable();
         if (catalogAuthStore == null) {
            throw catalogWritesUnsupported();
         }

         User user = this.currentUserService.requireCurrentUser();
         CatalogAuthStore.FavoritePage favorites = catalogAuthStore.favoritePaintingIds(user.getId(), page, size);
         List<CatalogReadStore.CatalogPainting> paintings = catalogReadStore.findPaintings(favorites.paintingIds());
         return this.catalogPage(
            new CatalogReadStore.CatalogPage(paintings, favorites.page(), favorites.size(), favorites.totalElements()), Set.copyOf(favorites.paintingIds())
         );
      } else {
         User user = this.currentUserService.requireCurrentUser();
         Sort sort = Sort.by(Direction.DESC, new String[]{"createdAt"}).and(Sort.by(Direction.ASC, new String[]{"publicId"}));
         Page<PaintingFavorite> favorites = this.favoriteRepository.findByUserIdAndPaintingStatus(user.getId(), "ACTIVE", PageRequest.of(page, size, sort));
         List<PaintingSummaryResponse> items = favorites.getContent()
            .stream()
            .map(PaintingFavorite::getPainting)
            .map(painting -> this.responseMapper.toSummary(painting, true))
            .toList();
         return PaintingPageResponse.from(favorites, items);
      }
   }

   public Painting requireActivePainting(String paintingId) {
      if (this.catalogReadStoreProvider.getIfAvailable() != null) {
         throw catalogWritesUnsupported();
      }

      String canonicalId = this.requireCanonicalUuid(paintingId);
      return this.paintingRepository.findByPublicIdAndStatus(canonicalId, "ACTIVE").orElseThrow(PaintingQueryService::paintingNotFound);
   }

   private Set<String> favoritedIds(Optional<User> user, List<Painting> paintings) {
      if (!user.isEmpty() && !paintings.isEmpty()) {
         List<Long> paintingIds = paintings.stream().map(BasePublicIdEntity::getId).toList();
         return this.favoriteRepository
            .findFavoritedPaintingPublicIds(user.orElseThrow().getId(), paintingIds)
            .stream()
            .collect(Collectors.toUnmodifiableSet());
      } else {
         return Set.of();
      }
   }

   private Sort sort(String requestedSort, String requestedDirection) {
      String sortName = normalizedSort(requestedSort);
      String property = SORT_FIELDS.get(sortName);
      if (property == null) {
         throw invalidSort("不支持的排序字段");
      }

      String directionName = normalizedDirection(requestedDirection);

      Direction direction = switch (directionName) {
         case "asc" -> Direction.ASC;
         case "desc" -> Direction.DESC;
         default -> throw invalidSort("排序方向必须为 asc 或 desc");
      };
      Sort requested = Sort.by(direction, new String[]{property});
      return "publicId".equals(property) ? requested : requested.and(Sort.by(Direction.ASC, new String[]{"publicId"}));
   }

   private static String normalizedSort(String requestedSort) {
      return requestedSort != null && !requestedSort.isBlank() ? requestedSort.trim() : "source";
   }

   private static String normalizedDirection(String requestedDirection) {
      return requestedDirection != null && !requestedDirection.isBlank() ? requestedDirection.trim().toLowerCase(Locale.ROOT) : "asc";
   }

   private PaintingPageResponse catalogPage(CatalogReadStore.CatalogPage source, Set<String> favoritedIds) {
      List<PaintingSummaryResponse> items = source.items()
         .stream()
         .map(painting -> this.catalogSummary(painting, favoritedIds.contains(painting.publicId())))
         .toList();
      int totalPages = source.totalElements() == 0L ? 0 : Math.toIntExact((source.totalElements() + source.size() - 1L) / source.size());
      return new PaintingPageResponse(
         items,
         source.page(),
         source.size(),
         source.totalElements(),
         totalPages,
         source.page() == 0,
         source.page() + 1 >= totalPages,
         source.page() + 1 < totalPages
      );
   }

   private PaintingSummaryResponse catalogDaily(CatalogReadStore store, Optional<User> user) {
      CatalogReadStore.CatalogPage first = store.list(
         new CatalogReadStore.CatalogQuery(null, null, null, null, null, null, null, null, null, null, null, 0, 1, "source", "asc")
      );
      if (first.totalElements() == 0L) {
         throw paintingNotFound();
      }

      int index = Math.toIntExact(Math.floorMod(LocalDate.now(this.paintingProperties.getDailyZone()).toEpochDay(), first.totalElements()));
      CatalogReadStore.CatalogPage selected = store.list(
         new CatalogReadStore.CatalogQuery(null, null, null, null, null, null, null, null, null, null, null, index, 1, "source", "asc")
      );
      CatalogReadStore.CatalogPainting painting = selected.items().stream().findFirst().orElseThrow(PaintingQueryService::paintingNotFound);
      return this.catalogSummary(painting, this.catalogFavoritedIds(user, List.of(painting)).contains(painting.publicId()));
   }

   private Set<String> catalogFavoritedIds(Optional<User> user, List<CatalogReadStore.CatalogPainting> paintings) {
      CatalogAuthStore catalogAuthStore = (CatalogAuthStore)this.catalogAuthStoreProvider.getIfAvailable();
      return catalogAuthStore != null && !user.isEmpty() && !paintings.isEmpty()
         ? catalogAuthStore.favoritedPaintingIds(user.orElseThrow().getId(), paintings.stream().map(CatalogReadStore.CatalogPainting::publicId).toList())
         : Set.of();
   }

   private PaintingSummaryResponse catalogSummary(CatalogReadStore.CatalogPainting painting, boolean favorited) {
      String dynastyRaw = painting.value("legacy.creation_dynasty_raw");
      return new PaintingSummaryResponse(
         painting.publicId(),
         painting.title(),
         painting.authorName(),
         dynastyRaw,
         this.dynastyNormalizer.normalize(dynastyRaw),
         painting.category(),
         painting.value("legacy.subject"),
         painting.value("legacy.painting_school"),
         painting.value("legacy.style"),
         painting.value("legacy.artistic_conception"),
         painting.imageReady(),
         this.catalogImage(painting),
         favorited
      );
   }

   private PaintingDetailResponse catalogDetail(CatalogReadStore.CatalogPainting painting, boolean favorited) {
      List<PaintingAnnotationResponse> annotations = painting.annotations()
         .stream()
         .filter(annotation -> "public".equals(annotation.visibility()))
         .map(this::catalogAnnotation)
         .toList();
      String dynastyRaw = painting.value("legacy.creation_dynasty_raw");
      // Compatibility fields must not bypass explicit non-public mappings.
      // Legacy mappings remain unchanged; identifiers and source paths for
      // typed catalogues remain private without row-number/path fallbacks.
      String publicSourceSequence = explicitlyNonPublic(painting,
         Set.of("source.record_id", "source.original_sequence"))
         ? null : painting.sourceSequence();
      String publicImageStorageName = explicitlyNonPublic(painting,
         Set.of("source.relative_image", "source.original_image_reference"))
         ? null : painting.imageStorageName();
      return new PaintingDetailResponse(
         painting.publicId(),
         publicSourceSequence,
         publicImageStorageName,
         painting.title(),
         painting.authorName(),
         painting.value("legacy.author_birth_year"),
         painting.value("legacy.author_birth_place"),
         painting.value("legacy.author_school"),
         painting.value("legacy.creation_year"),
         dynastyRaw,
         this.dynastyNormalizer.normalize(dynastyRaw),
         painting.value("legacy.actual_size"),
         painting.value("legacy.collection_institution"),
         painting.category(),
         painting.value("legacy.subject"),
         painting.value("legacy.painting_school"),
         painting.value("legacy.style"),
         painting.value("legacy.color"),
         painting.value("legacy.composition"),
         painting.value("legacy.artistic_conception"),
         painting.value("legacy.brushwork"),
         painting.value("legacy.ink_method"),
         painting.value("legacy.painting_material"),
         painting.value("legacy.pigment"),
         painting.value("legacy.seal"),
         painting.value("legacy.cultural_symbol"),
         painting.value("legacy.generated_text"),
         painting.value("legacy.music_scene_description"),
         painting.value("legacy.collection_platform"),
         painting.imageReady(),
         painting.imageReady(),
         "ACTIVE",
         this.catalogImage(painting),
         favorited,
         annotations
      );
   }

   private static boolean explicitlyNonPublic(CatalogReadStore.CatalogPainting painting, Set<String> fieldKeys) {
      return painting.annotations().stream()
         .anyMatch(annotation -> fieldKeys.contains(annotation.fieldKey())
            && !"public".equals(annotation.visibility()));
   }

   private PaintingImageResponse catalogImage(CatalogReadStore.CatalogPainting painting) {
      if (!painting.imageReady()) {
         return null;
      }

      CatalogReadStore.CatalogMediaAsset image = painting.image();
      String base = "/api/v1/assets/" + image.publicId();
      return new PaintingImageResponse(image.publicId(), image.mimeType(), null, null, null, base + "/content", base + "/download");
   }

   private PaintingAnnotationResponse catalogAnnotation(CatalogReadStore.CatalogAnnotation annotation) {
      return new PaintingAnnotationResponse(
         annotation.fieldKey(),
         annotation.label(),
         annotation.type(),
         annotation.unit(),
         annotation.group(),
         annotation.order(),
         annotation.visibility(),
         annotation.filterability(),
         annotation.originalColumn(),
         annotation.rawValue(),
         annotation.normalizedValue(),
         annotation.valueStatus(),
         annotation.assertionStatus()
      );
   }

   private void validatePage(int page, int size) {
      if (page < 0) {
         throw invalidQuery("page 不能小于 0");
      } else if (size < 1 || size > 100) {
         throw new ApiV1Exception(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PAGE_SIZE, "size 必须在 1 到 100 之间");
      }
   }

   private String normalizeFilter(String value, String field, int maxLength) {
      if (value != null && !value.isBlank()) {
         String normalized = value.trim();
         if (normalized.length() > maxLength) {
            throw invalidQuery(field + " 长度超过允许上限");
         } else {
            return normalized;
         }
      } else {
         return null;
      }
   }

   private String normalizedDynasty(String value) {
      String filtered = this.normalizeFilter(value, "dynasty", 512);
      if (filtered == null) {
         return null;
      }

      String normalized = this.dynastyNormalizer.normalize(filtered);
      return normalized != null && !normalized.isBlank() ? normalized : null;
   }

   private String requireCanonicalUuid(String value) {
      if (value != null && !value.isBlank()) {
         try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value.toLowerCase(Locale.ROOT))) {
               throw invalidPaintingId();
            } else {
               return canonical;
            }
         } catch (IllegalArgumentException exception) {
            throw invalidPaintingId();
         }
      } else {
         throw invalidPaintingId();
      }
   }

   private static ApiV1Exception invalidPaintingId() {
      return new ApiV1Exception(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PAINTING_ID, "画作标识格式无效");
   }

   private static ApiV1Exception paintingNotFound() {
      return new ApiV1Exception(HttpStatus.NOT_FOUND, ApiErrorCode.PAINTING_NOT_FOUND, "画作不存在或当前不可用");
   }

   private static ApiV1Exception invalidSort(String message) {
      return new ApiV1Exception(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_SORT, message);
   }

   private static ApiV1Exception invalidQuery(String message) {
      return new ApiV1Exception(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PAINTING_QUERY, message);
   }

   private static ApiV1Exception catalogWritesUnsupported() {
      return new ApiV1Exception(HttpStatus.CONFLICT, ApiErrorCode.CATALOG_READ_WRITE_UNSUPPORTED, "当前目录集成未启用这项关联业务写入");
   }

   @Autowired
   public PaintingQueryService(
      final PaintingRepository paintingRepository,
      final PaintingFavoriteRepository favoriteRepository,
      final CurrentUserService currentUserService,
      final PaintingResponseMapper responseMapper,
      final PaintingProperties paintingProperties,
      final DynastyNormalizer dynastyNormalizer,
      final ObjectProvider<CatalogReadStore> catalogReadStoreProvider,
      final ObjectProvider<CatalogAuthStore> catalogAuthStoreProvider
   ) {
      this(paintingRepository, favoriteRepository, currentUserService, responseMapper, paintingProperties,
         dynastyNormalizer, catalogReadStoreProvider, catalogAuthStoreProvider, Clock.systemUTC());
   }

   PaintingQueryService(
      final PaintingRepository paintingRepository,
      final PaintingFavoriteRepository favoriteRepository,
      final CurrentUserService currentUserService,
      final PaintingResponseMapper responseMapper,
      final PaintingProperties paintingProperties,
      final DynastyNormalizer dynastyNormalizer,
      final ObjectProvider<CatalogReadStore> catalogReadStoreProvider,
      final ObjectProvider<CatalogAuthStore> catalogAuthStoreProvider,
      final Clock clock
   ) {
      this.paintingRepository = paintingRepository;
      this.favoriteRepository = favoriteRepository;
      this.currentUserService = currentUserService;
      this.responseMapper = responseMapper;
      this.paintingProperties = paintingProperties;
      this.dynastyNormalizer = dynastyNormalizer;
      this.catalogReadStoreProvider = catalogReadStoreProvider;
      this.catalogAuthStoreProvider = catalogAuthStoreProvider;
      this.clock = clock;
   }
}
