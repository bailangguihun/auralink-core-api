package com.auralink.guide.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Evidence-bounded sections of the standard Painting guide. */
@JsonPropertyOrder({
        "artistAndEra",
        "subjectAndScene",
        "composition",
        "brushworkAndInk",
        "colorAndMaterial",
        "artisticConception",
        "culturalMeaning",
        "musicAssociation"
})
public record GuideSections(
        String artistAndEra,
        String subjectAndScene,
        String composition,
        String brushworkAndInk,
        String colorAndMaterial,
        String artisticConception,
        String culturalMeaning,
        String musicAssociation) {
}
