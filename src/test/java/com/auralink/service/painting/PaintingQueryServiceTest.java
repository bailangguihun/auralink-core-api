package com.auralink.service.painting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.catalog.DynastyNormalizer;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.entity.Painting;
import com.auralink.repository.PaintingFavoriteRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.service.CurrentUserService;

@ExtendWith(MockitoExtension.class)
class PaintingQueryServiceTest {

    @Mock private PaintingRepository paintingRepository;
    @Mock private PaintingFavoriteRepository favoriteRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private PaintingResponseMapper responseMapper;
    @Mock private DynastyNormalizer dynastyNormalizer;

    private PaintingQueryService service;
    private PaintingProperties paintingProperties;

    @BeforeEach
    void setUp() {
        paintingProperties = new PaintingProperties();
        service = new PaintingQueryService(
                paintingRepository,
                favoriteRepository,
                currentUserService,
                responseMapper,
                paintingProperties,
                dynastyNormalizer);
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizedDynastyAndPublicSourceSortReachTheDatabaseQuery() {
        when(dynastyNormalizer.normalize("清朝")).thenReturn("清代");
        when(currentUserService.findCurrentUser()).thenReturn(Optional.empty());
        when(paintingRepository.findAll(
                any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        callList("清朝", 24, "source", "asc");

        verify(dynastyNormalizer).normalize("清朝");
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(paintingRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(24);
        assertThat(pageable.getValue().getSort().getOrderFor("sourceKey")).isNotNull();
        assertThat(pageable.getValue().getSort().getOrderFor("publicId")).isNotNull();
    }

    @Test
    void rejectsOversizedPagesWithStableV1Code() {
        assertThatThrownBy(() -> callList(null, 101, "source", "asc"))
                .isInstanceOfSatisfying(ApiV1Exception.class, exception -> {
                    assertThat(exception.getStatus().value()).isEqualTo(400);
                    assertThat(exception.getCode()).isEqualTo(ApiErrorCode.INVALID_PAGE_SIZE);
                });
    }

    @Test
    void rejectsInternalOrUnknownSortNames() {
        assertThatThrownBy(() -> callList(null, 24, "sourceKey", "asc"))
                .isInstanceOfSatisfying(ApiV1Exception.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ApiErrorCode.INVALID_SORT));
        assertThatThrownBy(() -> callList(null, 24, "createdAt", "asc"))
                .isInstanceOfSatisfying(ApiV1Exception.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ApiErrorCode.INVALID_SORT));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dailySelectionUsesTheConfiguredProductTimeZone() {
        ZoneId zone = ZoneId.of("Pacific/Kiritimati");
        paintingProperties.setDailyZone(zone);
        Painting painting = Painting.builder()
                .id(1L)
                .publicId("00000000-0000-0000-0000-000000000001")
                .sourceKey("painting-dataset:test.jpg")
                .imageStorageName("test")
                .status("ACTIVE")
                .build();
        when(paintingRepository.count(any(Specification.class))).thenReturn(7L);
        when(paintingRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(
                        List.of(painting), invocation.getArgument(1), 7));
        when(currentUserService.findCurrentUser()).thenReturn(Optional.empty());

        service.getDailyPainting();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(paintingRepository).findAll(any(Specification.class), pageable.capture());
        int expectedPage = Math.toIntExact(Math.floorMod(
                LocalDate.now(zone).toEpochDay(), 7L));
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(expectedPage);
    }

    private void callList(String dynasty, int size, String sort, String direction) {
        service.listPaintings(
                null,
                dynasty,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                size,
                sort,
                direction);
    }
}
