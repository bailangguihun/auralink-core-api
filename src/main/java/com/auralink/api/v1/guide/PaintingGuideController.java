package com.auralink.api.v1.guide;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.auralink.guide.service.PaintingGuideService;

import lombok.RequiredArgsConstructor;

/** Authenticated, provider-independent API for one standard Guide per Painting. */
@RestController
@RequestMapping("/api/v1/paintings/{paintingId}/guide")
@RequiredArgsConstructor
public class PaintingGuideController {

    private final PaintingGuideService guideService;

    @GetMapping
    public PaintingGuideResponse getGuide(@PathVariable String paintingId) {
        return PaintingGuideResponse.from(guideService.getCurrentGuide(paintingId));
    }

    @PostMapping
    public PaintingGuideResponse ensureGuide(
            @PathVariable String paintingId,
            Authentication authentication) {
        String requester = authentication == null ? null : authentication.getName();
        return PaintingGuideResponse.from(guideService.ensureGuide(paintingId, requester));
    }

    @PostMapping("/audio")
    public ResponseEntity<Void> reservedAudio(@PathVariable String paintingId) {
        guideService.requirePaintingForReservedAudio(paintingId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build(); // service currently always throws
    }
}
