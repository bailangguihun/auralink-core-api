package com.auralink.guide.context;

import java.util.List;

import com.auralink.guide.knowledge.KnowledgeItem;

public record PaintingGuideContext(
        String paintingId,
        Basic basic,
        Artist artist,
        Art art,
        OfficialAnnotations officialAnnotations,
        List<KnowledgeItem> knowledge
) {

    public PaintingGuideContext {
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
    }

    public PaintingGuideContext withKnowledge(List<KnowledgeItem> selectedKnowledge) {
        return new PaintingGuideContext(
                paintingId,
                basic,
                artist,
                art,
                officialAnnotations,
                selectedKnowledge
        );
    }

    public record Basic(
            String title,
            String creationYear,
            String creationDynastyRaw,
            String creationDynastyNormalized,
            String actualSize,
            String collectionInstitution
    ) {
    }

    public record Artist(
            String name,
            String birthYear,
            String birthPlace,
            String school
    ) {
    }

    public record Art(
            String category,
            String subject,
            String paintingSchool,
            String style,
            String color,
            String composition,
            String artisticConception,
            String brushwork,
            String inkMethod,
            String paintingMaterial,
            String pigment,
            String seal,
            String culturalSymbol
    ) {
    }

    public record OfficialAnnotations(
            String generatedText,
            String musicSceneDescription
    ) {
    }
}
