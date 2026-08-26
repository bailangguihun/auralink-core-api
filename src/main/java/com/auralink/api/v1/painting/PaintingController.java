package com.auralink.api.v1.painting;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.auralink.service.painting.PaintingFavoriteService;
import com.auralink.service.painting.PaintingQueryService;

import lombok.RequiredArgsConstructor;

/** Auralink 2.0 official painting catalog API. */
@RestController("paintingV1Controller")
@RequestMapping("/api/v1/paintings")
@RequiredArgsConstructor
public class PaintingController {

    private final PaintingQueryService paintingQueryService;
    private final PaintingFavoriteService favoriteService;

    @GetMapping
    public PaintingPageResponse listPaintings(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String dynasty,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String paintingSchool,
            @RequestParam(required = false) String style,
            @RequestParam(required = false) String artisticConception,
            @RequestParam(required = false) String paintingMaterial,
            @RequestParam(required = false) String collectionInstitution,
            @RequestParam(required = false) String collectionPlatform,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size,
            @RequestParam(defaultValue = "source") String sort,
            @RequestParam(defaultValue = "asc") String direction) {
        return paintingQueryService.listPaintings(
                keyword,
                dynasty,
                category,
                author,
                subject,
                paintingSchool,
                style,
                artisticConception,
                paintingMaterial,
                collectionInstitution,
                collectionPlatform,
                page,
                size,
                sort,
                direction);
    }

    @GetMapping("/daily")
    public PaintingSummaryResponse getDailyPainting() {
        return paintingQueryService.getDailyPainting();
    }

    @GetMapping("/{paintingId}")
    public PaintingDetailResponse getPainting(@PathVariable String paintingId) {
        return paintingQueryService.getPainting(paintingId);
    }

    @PutMapping("/{paintingId}/favorite")
    public ResponseEntity<Void> favorite(@PathVariable String paintingId) {
        favoriteService.favorite(paintingId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{paintingId}/favorite")
    public ResponseEntity<Void> unfavorite(@PathVariable String paintingId) {
        favoriteService.unfavorite(paintingId);
        return ResponseEntity.noContent().build();
    }
}
