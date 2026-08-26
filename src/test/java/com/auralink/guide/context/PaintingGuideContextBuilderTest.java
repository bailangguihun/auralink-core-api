package com.auralink.guide.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;

class PaintingGuideContextBuilderTest {

    private final PaintingGuideContextBuilder builder = new PaintingGuideContextBuilder();

    @Test
    void mapsOnlyGuideRelevantOfficialFieldsAndFiltersSourcePlaceholders() {
        MediaAsset image = new MediaAsset();
        image.setStorageKey("catalog/private-server-name.jpg");

        Painting painting = new Painting();
        painting.setId(901L);
        painting.setPublicId("1b020a2c-b0d1-4070-a262-b46182245f6b");
        painting.setSourceSequence("duplicate-csv-sequence");
        painting.setSourceKey("painting-dataset:sample.jpg");
        painting.setImageStorageName("sample.jpg");
        painting.setCollectionPlatform("internal collection source");
        painting.setImageAsset(image);
        painting.setTitle("  东坡夜游赤壁图  ");
        painting.setCreationYear("现代");
        painting.setCreationDynastyRaw("近现代");
        painting.setCreationDynastyNormalized("近现代");
        painting.setActualSize("68 × 45 cm");
        painting.setCollectionInstitution("中国美术馆");
        painting.setAuthorName("李可染");
        painting.setAuthorBirthYear("1907");
        painting.setAuthorBirthPlace("江苏徐州");
        painting.setAuthorSchool("现代山水画");
        painting.setCategory("国画");
        painting.setSubject("赤壁夜游");
        painting.setPaintingSchool("山水画");
        painting.setStyle("写意");
        painting.setColor("水墨");
        painting.setComposition("横向展开");
        painting.setArtisticConception("清逸怀古");
        painting.setBrushwork("凝练");
        painting.setInkMethod("积墨");
        painting.setPaintingMaterial("纸本");
        painting.setPigment("墨");
        painting.setSeal("  ");
        painting.setCulturalSymbol("苏轼、赤壁");
        painting.setGeneratedText(" 0 ");
        painting.setMusicSceneDescription(" 古琴与箫的舒缓意境 ");

        PaintingGuideContext context = builder.build(painting);

        assertThat(context.paintingId()).isEqualTo(painting.getPublicId());
        assertThat(context.basic().title()).isEqualTo("东坡夜游赤壁图");
        assertThat(context.basic().collectionInstitution()).isEqualTo("中国美术馆");
        assertThat(context.artist()).isEqualTo(new PaintingGuideContext.Artist(
                "李可染", "1907", "江苏徐州", "现代山水画"));
        assertThat(context.art().subject()).isEqualTo("赤壁夜游");
        assertThat(context.art().seal()).isNull();
        assertThat(context.officialAnnotations().generatedText()).isNull();
        assertThat(context.officialAnnotations().musicSceneDescription())
                .isEqualTo("古琴与箫的舒缓意境");
        assertThat(context.knowledge()).isEmpty();

        // The source row and internal resource metadata remain untouched and unrepresented.
        assertThat(painting.getGeneratedText()).isEqualTo(" 0 ");
        assertThat(context.toString())
                .doesNotContain("901", "duplicate-csv-sequence", "painting-dataset", "storageKey");
    }

    @Test
    void withKnowledgeCopiesInputAndDoesNotChangeTheBaseContext() {
        Painting painting = new Painting();
        painting.setPublicId("bdbe1911-3515-43d3-bb2f-84d37283f148");
        painting.setTitle("墨竹");
        PaintingGuideContext base = builder.build(painting);
        java.util.ArrayList<com.auralink.guide.knowledge.KnowledgeItem> selected =
                new java.util.ArrayList<>();
        selected.add(new com.auralink.guide.knowledge.KnowledgeItem(
                "竹枝词", "POETRY_GRAPH_NODE", "竹枝词", "竹意相关资料"));

        PaintingGuideContext enriched = base.withKnowledge(selected);
        selected.clear();

        assertThat(base.knowledge()).isEmpty();
        assertThat(enriched.knowledge()).hasSize(1);
        assertThat(enriched.knowledge()).isUnmodifiable();
    }
}
