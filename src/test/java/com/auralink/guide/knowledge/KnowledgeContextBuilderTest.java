package com.auralink.guide.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import com.auralink.config.properties.GuideProperties;
import com.auralink.guide.context.PaintingGuideContext;
import com.fasterxml.jackson.databind.ObjectMapper;

class KnowledgeContextBuilderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void loadsActualGraphShapeAndBuildsStableSpecificReferences() throws IOException {
        Fixture fixture = fixture();
        KnowledgeContextBuilder builder = fixture.builder();
        PaintingGuideContext context = context(
                "东坡夜游赤壁图", "李可染", "近现代", "苏轼夜游赤壁", "苏轼、赤壁");

        KnowledgeSelection first = builder.build(context);
        KnowledgeSelection second = builder.build(context);

        assertThat(first).isEqualTo(second);
        assertThat(first.items()).extracting(KnowledgeItem::sourceId)
                .containsExactly("赤壁", "苏轼");
        assertThat(first.items()).allSatisfy(item -> {
            assertThat(item.sourceType()).isEqualTo(KnowledgeContextBuilder.SOURCE_TYPE);
            assertThat(item.content()).doesNotContain("<SEP>");
        });
        assertThat(first.fingerprints())
                .containsOnlyKeys("poetryGraphSha256", "poetryStatsSha256")
                .allSatisfy((key, value) -> assertThat(value).matches("[0-9a-f]{64}"));
    }

    @Test
    void appliesItemAndWholeSelectionCharacterLimits() throws IOException {
        Fixture fixture = fixture();
        fixture.properties().setMaxKnowledgeItems(1);
        fixture.properties().setMaxKnowledgeChars(45);

        KnowledgeSelection selection = fixture.builder().build(context(
                "东坡夜游赤壁图", "苏轼", "宋代", "赤壁", "赤壁"));

        assertThat(selection.items()).hasSize(1);
        int characters = selection.items().stream()
                .mapToInt(item -> codePoints(item.sourceId()) + codePoints(item.sourceType())
                        + codePoints(item.title()) + codePoints(item.content()))
                .sum();
        assertThat(characters).isLessThanOrEqualTo(45);
    }

    @Test
    void excludesBroadStopTermsAndReturnsEmptySelectionForUnrelatedPainting() throws IOException {
        Fixture fixture = fixture();

        KnowledgeSelection selection = fixture.builder().build(context(
                "当代抽象习作", "未知作者", "当代", "作品表现自然艺术", "中国文化"));

        assertThat(selection.items()).isEmpty();
        assertThat(selection.fingerprints()).hasSize(2);
    }

    @Test
    void supportsOnlyContainedRegularKnowledgeFiles() throws IOException {
        Fixture fixture = fixture();
        Path outside = tempDirectory.resolve("outside.json");
        Files.writeString(outside, "{}", java.nio.charset.StandardCharsets.UTF_8);
        fixture.properties().setPoetryGraphPath("../outside.json");

        assertThatThrownBy(() -> fixture.builder().build(context(
                "赤壁", "苏轼", "宋代", "赤壁", "赤壁")))
                .isInstanceOf(StaticKnowledgeLoader.KnowledgeLoadingException.class)
                .hasMessageContaining("approved data directory")
                .hasMessageNotContaining(tempDirectory.toString());
    }

    @Test
    void rejectsOversizedKnowledgeBeforeJsonParsing() throws IOException {
        Fixture fixture = fixture();
        Files.write(
                fixture.root().resolve("poetry-graph.json"),
                new byte[(int) StaticKnowledgeLoader.MAX_GRAPH_BYTES + 1]);

        assertThatThrownBy(() -> fixture.builder().build(context(
                "赤壁", "苏轼", "宋代", "赤壁", "赤壁")))
                .isInstanceOf(StaticKnowledgeLoader.KnowledgeLoadingException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void parsesTheRealInheritedKnowledgeFilesReadOnly() throws IOException {
        Path working = Path.of("").toAbsolutePath().normalize();
        Path project = "backend".equals(String.valueOf(working.getFileName()))
                ? working.getParent()
                : working;
        Path realRoot = project.resolve("frontend/public/data");
        Assumptions.assumeTrue(
                Files.isRegularFile(realRoot.resolve("poetry-graph.json"))
                        && Files.isRegularFile(realRoot.resolve("poetry-stats.json")),
                "inherited external knowledge resources are not provisioned");
        GuideProperties properties = new GuideProperties();
        properties.setPoetryGraphPath(realRoot.resolve("poetry-graph.json").toString());
        properties.setPoetryStatsPath(realRoot.resolve("poetry-stats.json").toString());
        StaticKnowledgeLoader loader = new StaticKnowledgeLoader(
                properties, new ObjectMapper(), working, realRoot);

        StaticKnowledgeLoader.LoadedKnowledge loaded = loader.load();

        assertThat(loaded.nodes()).hasSize(711);
        assertThat(loaded.fingerprints())
                .containsEntry("poetryGraphSha256",
                        "899511b6b7d02e6ba515986db651c67b3702497d2031bdbb75029c34491da2c0")
                .containsEntry("poetryStatsSha256",
                        "27e91284d28c05c9b7dde9fcb4181c713761d3c2eebf84816b45c9f7d9af7501");
    }

    private Fixture fixture() throws IOException {
        Path root = Files.createDirectories(tempDirectory.resolve("frontend/public/data"));
        Files.writeString(root.resolve("poetry-graph.json"), graphJson());
        Files.writeString(root.resolve("poetry-stats.json"), statsJson());
        GuideProperties properties = new GuideProperties();
        properties.setPoetryGraphPath("poetry-graph.json");
        properties.setPoetryStatsPath("poetry-stats.json");
        properties.setMaxKnowledgeItems(5);
        properties.setMaxKnowledgeChars(8_000);
        return new Fixture(properties, root);
    }

    private String graphJson() {
        return """
                {
                  "nodes": [
                    {"id":"赤壁","name":"赤壁","category":"地理","size":20,
                     "description":"赤壁与苏轼前后赤壁赋相关。<SEP>兼具历史与文学意义。",
                     "tooltip":"赤壁与苏轼前后赤壁赋相关。<SEP>兼具历史与文学意义。"},
                    {"id":"苏轼","name":"苏轼","category":"人物/诗人","size":20,
                     "description":"苏轼是宋代文学家。","tooltip":"苏轼是宋代文学家。"},
                    {"id":"作品","name":"作品","category":"朝代/类别","size":20,
                     "description":"过于宽泛的共同词。","tooltip":"过于宽泛的共同词。"},
                    {"id":"越人歌","name":"越人歌","category":"事件/诗词","size":20,
                     "description":"春秋时期的越语歌谣。","tooltip":"春秋时期的越语歌谣。"}
                  ],
                  "links": [
                    {"source":"赤壁","target":"苏轼","value":9,"label":"文学关联",
                     "summary":"文学关联","detail":"苏轼创作前后赤壁赋。"}
                  ]
                }
                """;
    }

    private String statsJson() {
        return """
                {
                  "overview":{"entities":4,"relations":1,"poems":1,"poets":1},
                  "entityTypeDistribution":[["地理",1],["人物/诗人",1],["朝代/类别",1],["事件/诗词",1]],
                  "relationTypeDistribution":[["文学关联",1]]
                }
                """;
    }

    private PaintingGuideContext context(
            String title,
            String author,
            String dynasty,
            String subject,
            String culturalSymbol
    ) {
        return new PaintingGuideContext(
                "9da3014d-7a8d-4842-8334-39ad8102f9dd",
                new PaintingGuideContext.Basic(title, null, dynasty, dynasty, null, null),
                new PaintingGuideContext.Artist(author, null, null, null),
                new PaintingGuideContext.Art(
                        null, subject, null, null, null, null, null,
                        null, null, null, null, null, culturalSymbol),
                new PaintingGuideContext.OfficialAnnotations(null, null),
                java.util.List.of()
        );
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private record Fixture(GuideProperties properties, Path root) {
        KnowledgeContextBuilder builder() {
            StaticKnowledgeLoader loader = new StaticKnowledgeLoader(
                    properties, new ObjectMapper(), root, root);
            return new KnowledgeContextBuilder(properties, loader);
        }
    }
}
