package com.auralink.api.v1.painting;

import com.auralink.service.painting.PaintingFavoriteService;
import com.auralink.service.painting.PaintingQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("paintingV1Controller")
@RequestMapping("/api/v1/paintings")
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
      @RequestParam(defaultValue = "asc") String direction,
      @RequestParam(required = false) String orderDate
   ) {
      return this.paintingQueryService
         .listPaintings(
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
            direction,
            orderDate
         );
   }

   @GetMapping("/daily")
   public PaintingSummaryResponse getDailyPainting() {
      return this.paintingQueryService.getDailyPainting();
   }

   @GetMapping("/{paintingId}")
   public PaintingDetailResponse getPainting(@PathVariable String paintingId) {
      return this.paintingQueryService.getPainting(paintingId);
   }

   @PutMapping("/{paintingId}/favorite")
   public ResponseEntity<Void> favorite(@PathVariable String paintingId) {
      this.favoriteService.favorite(paintingId);
      return ResponseEntity.noContent().build();
   }

   @DeleteMapping("/{paintingId}/favorite")
   public ResponseEntity<Void> unfavorite(@PathVariable String paintingId) {
      this.favoriteService.unfavorite(paintingId);
      return ResponseEntity.noContent().build();
   }

   public PaintingController(final PaintingQueryService paintingQueryService, final PaintingFavoriteService favoriteService) {
      this.paintingQueryService = paintingQueryService;
      this.favoriteService = favoriteService;
   }
}
