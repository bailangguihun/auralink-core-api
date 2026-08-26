package com.auralink.guide.context;

import org.springframework.stereotype.Component;

import com.auralink.entity.Painting;

@Component
public class PaintingGuideContextBuilder {

    public PaintingGuideContext build(Painting painting) {
        if (painting == null) {
            throw new IllegalArgumentException("Painting is required");
        }

        return new PaintingGuideContext(
                clean(painting.getPublicId()),
                new PaintingGuideContext.Basic(
                        clean(painting.getTitle()),
                        clean(painting.getCreationYear()),
                        clean(painting.getCreationDynastyRaw()),
                        clean(painting.getCreationDynastyNormalized()),
                        clean(painting.getActualSize()),
                        clean(painting.getCollectionInstitution())
                ),
                new PaintingGuideContext.Artist(
                        clean(painting.getAuthorName()),
                        clean(painting.getAuthorBirthYear()),
                        clean(painting.getAuthorBirthPlace()),
                        clean(painting.getAuthorSchool())
                ),
                new PaintingGuideContext.Art(
                        clean(painting.getCategory()),
                        clean(painting.getSubject()),
                        clean(painting.getPaintingSchool()),
                        clean(painting.getStyle()),
                        clean(painting.getColor()),
                        clean(painting.getComposition()),
                        clean(painting.getArtisticConception()),
                        clean(painting.getBrushwork()),
                        clean(painting.getInkMethod()),
                        clean(painting.getPaintingMaterial()),
                        clean(painting.getPigment()),
                        clean(painting.getSeal()),
                        clean(painting.getCulturalSymbol())
                ),
                new PaintingGuideContext.OfficialAnnotations(
                        clean(painting.getGeneratedText()),
                        clean(painting.getMusicSceneDescription())
                ),
                java.util.List.of()
        );
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip();
        if (cleaned.isEmpty() || "0".equals(cleaned)) {
            return null;
        }
        return cleaned;
    }
}
