package com.auralink.guide.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.auralink.guide.knowledge.KnowledgeItem;
import com.fasterxml.jackson.databind.ObjectMapper;

class GuideResultCodecTest {

    private static final KnowledgeItem KNOWLEDGE = new KnowledgeItem(
            "poetry:1", "POETRY", "山居秋暝", "明月松间照，清泉石上流。");

    private GuideResultCodec codec;

    @BeforeEach
    void setUp() {
        codec = new GuideResultCodec(new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void validatesNormalizesAndCanonicallyRoundTripsStructuredResult() {
        GuideResult input = new GuideResult(
                "1",
                "  这是一段有依据的导览摘要。  ",
                new GuideSections("  画家与时代  ", null, " ", null, null, null, null, null),
                List.of("  观察构图层次  ", "留意水墨变化"),
                List.of(new GuideKnowledgeReference("poetry:1", "POETRY", "山居秋暝")));

        String canonical = codec.encodeCanonical(input, "1", List.of(KNOWLEDGE));
        GuideResult decoded = codec.decode(canonical, "1", List.of(KNOWLEDGE));

        assertThat(canonical).isEqualTo(
                "{\"schemaVersion\":\"1\",\"summary\":\"这是一段有依据的导览摘要。\"," +
                "\"sections\":{\"artistAndEra\":\"画家与时代\",\"subjectAndScene\":null," +
                "\"composition\":null,\"brushworkAndInk\":null,\"colorAndMaterial\":null," +
                "\"artisticConception\":null,\"culturalMeaning\":null,\"musicAssociation\":null}," +
                "\"highlights\":[\"观察构图层次\",\"留意水墨变化\"]," +
                "\"knowledgeReferences\":[{\"sourceId\":\"poetry:1\"," +
                "\"sourceType\":\"POETRY\",\"title\":\"山居秋暝\"}]}");
        assertThat(decoded).isEqualTo(codec.validate(input, "1", List.of(KNOWLEDGE)));
    }

    @Test
    void rejectsUnknownFieldsAndTrailingJson() {
        String valid = codec.encodeCanonical(validResult(), "1", List.of(KNOWLEDGE));

        assertThatThrownBy(() -> codec.decode(
                valid.replaceFirst("\\{", "{\"provider\":\"forbidden\","),
                "1",
                List.of(KNOWLEDGE)))
                .isInstanceOf(GuideResultValidationException.class)
                .hasMessageContaining("malformed");
        assertThatThrownBy(() -> codec.decode(valid + " {}", "1", List.of(KNOWLEDGE)))
                .isInstanceOf(GuideResultValidationException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void rejectsMissingSummarySectionsAndWrongSchema() {
        assertInvalid(new GuideResult("1", " ", validSections(), validHighlights(), List.of()), "summary");
        assertInvalid(new GuideResult("1", "摘要", null, validHighlights(), List.of()), "sections");
        assertThatThrownBy(() -> codec.validate(validResult(), "2", List.of(KNOWLEDGE)))
                .isInstanceOf(GuideResultValidationException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void enforcesHighlightCountUniquenessAndBounds() {
        assertInvalid(new GuideResult("1", "摘要", validSections(), List.of("仅一个"), List.of()),
                "between 2 and 5");
        assertInvalid(new GuideResult(
                "1", "摘要", validSections(), List.of("重复", " 重复 "), List.of()),
                "duplicates");
        assertInvalid(new GuideResult(
                "1",
                "摘要",
                validSections(),
                List.of("甲".repeat(GuideResultCodec.MAX_HIGHLIGHT_CHARACTERS + 1), "乙"),
                List.of()),
                "character limit");
    }

    @Test
    void rejectsCodeFencesHtmlAndOversizedResult() {
        assertInvalid(new GuideResult(
                "1", "```json", validSections(), validHighlights(), List.of()), "code fences");
        assertInvalid(new GuideResult(
                "1", "<script>bad</script>", validSections(), validHighlights(), List.of()), "HTML");
        assertInvalid(new GuideResult(
                "1",
                "摘要",
                new GuideSections("甲".repeat(GuideResultCodec.MAX_SECTION_CHARACTERS + 1),
                        null, null, null, null, null, null, null),
                validHighlights(),
                List.of()),
                "character limit");
    }

    @Test
    void requiresChineseSummarySectionsAndHighlights() {
        assertInvalid(new GuideResult(
                "1", "English only", validSections(), validHighlights(), List.of()), "Chinese text");
        assertInvalid(new GuideResult(
                "1",
                "中文摘要",
                new GuideSections("English section", null, null, null, null, null, null, null),
                validHighlights(),
                List.of()),
                "Chinese text");
        assertInvalid(new GuideResult(
                "1", "中文摘要", validSections(), List.of("English", "中文亮点"), List.of()),
                "Chinese text");
    }

    @Test
    void rejectsUnsupportedMismatchedAndDuplicateKnowledgeReferences() {
        assertInvalid(new GuideResult(
                "1",
                "摘要",
                validSections(),
                validHighlights(),
                List.of(new GuideKnowledgeReference("poetry:other", "POETRY", "未知"))),
                "unsupported");
        assertInvalid(new GuideResult(
                "1",
                "摘要",
                validSections(),
                validHighlights(),
                List.of(new GuideKnowledgeReference("poetry:1", "GRAPH", "山居秋暝"))),
                "unsupported");
        assertInvalid(new GuideResult(
                "1",
                "摘要",
                validSections(),
                validHighlights(),
                List.of(
                        new GuideKnowledgeReference("poetry:1", "POETRY", "山居秋暝"),
                        new GuideKnowledgeReference("poetry:1", "POETRY", "山居秋暝"))),
                "repeat");
    }

    @Test
    void rejectsJsonOverHardUtf8ByteCapBeforeParsing() {
        String oversized = "{\"summary\":\"" + "画".repeat(GuideResultCodec.MAX_RESULT_BYTES) + "\"}";

        assertThatThrownBy(() -> codec.decode(oversized, "1", List.of()))
                .isInstanceOf(GuideResultValidationException.class)
                .hasMessageContaining("byte limit");
    }

    private void assertInvalid(GuideResult result, String message) {
        assertThatThrownBy(() -> codec.validate(result, "1", List.of(KNOWLEDGE)))
                .isInstanceOf(GuideResultValidationException.class)
                .hasMessageContaining(message);
    }

    private GuideResult validResult() {
        return new GuideResult(
                "1",
                "这是一段有依据的导览摘要。",
                validSections(),
                validHighlights(),
                List.of(new GuideKnowledgeReference("poetry:1", "POETRY", "山居秋暝")));
    }

    private GuideSections validSections() {
        return new GuideSections("画家与时代", null, "构图", null, null, null, null, null);
    }

    private List<String> validHighlights() {
        return List.of("观察构图层次", "留意水墨变化");
    }
}
