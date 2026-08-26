package com.auralink.api.v1.painting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.auralink.service.painting.PaintingQueryService;

import lombok.RequiredArgsConstructor;

/** Authenticated current-user view of favorite official paintings. */
@RestController
@RequestMapping("/api/v1/me/favorites/paintings")
@RequiredArgsConstructor
public class MyPaintingFavoriteController {

    private final PaintingQueryService paintingQueryService;

    @GetMapping
    public PaintingPageResponse listFavorites(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        return paintingQueryService.listCurrentUserFavorites(page, size);
    }
}
