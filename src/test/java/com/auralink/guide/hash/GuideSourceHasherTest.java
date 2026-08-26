package com.auralink.guide.hash;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.auralink.guide.context.PaintingGuideContext;
import com.auralink.guide.knowledge.KnowledgeItem;
import com.auralink.guide.knowledge.KnowledgeSelection;
import com.fasterxml.jackson.databind.ObjectMapper;

class GuideSourceHasherTest {

    private final GuideSourceHasher hasher = new GuideSourceHasher(new ObjectMapper());

    @Test
    void sameCanonicalSourceAlwaysHasSameSha256() {
        PaintingGuideContext context = context("官方赏析", "音乐意境");
        KnowledgeSelection first = selection("赤壁与苏轼相关", Map.of(
                "poetryStatsSha256", "stats",
                "poetryGraphSha256", "graph"));
        KnowledgeSelection reordered = selection("赤壁与苏轼相关", Map.of(
                "poetryGraphSha256", "graph",
                "poetryStatsSha256", "stats"));

        String firstHash = hasher.hash("1", context, first);
        String secondHash = hasher.hash("1", context, reordered);

        assertThat(firstHash).isEqualTo(secondHash).matches("[0-9a-f]{64}");
    }

    @Test
    void annotationKnowledgeFingerprintAndSchemaChangesInvalidateHash() {
        PaintingGuideContext original = context("官方赏析", "音乐意境");
        KnowledgeSelection originalKnowledge = selection(
                "赤壁与苏轼相关", Map.of("poetryGraphSha256", "graph-a"));
        String originalHash = hasher.hash("1", original, originalKnowledge);

        assertThat(hasher.hash("1", context("官方赏析已更新", "音乐意境"), originalKnowledge))
                .isNotEqualTo(originalHash);
        assertThat(hasher.hash("1", original,
                selection("赤壁资料已更新", Map.of("poetryGraphSha256", "graph-a"))))
                .isNotEqualTo(originalHash);
        assertThat(hasher.hash("1", original,
                selection("赤壁与苏轼相关", Map.of("poetryGraphSha256", "graph-b"))))
                .isNotEqualTo(originalHash);
        assertThat(hasher.hash("2", original, originalKnowledge)).isNotEqualTo(originalHash);
    }

    @Test
    void incomingContextKnowledgeIsReplacedByAuthoritativeSelection() {
        PaintingGuideContext base = context("官方赏析", "音乐意境");
        KnowledgeSelection selected = selection(
                "经过选择的知识", Map.of("poetryGraphSha256", "graph"));
        PaintingGuideContext callerPopulated = base.withKnowledge(List.of(new KnowledgeItem(
                "caller", "UNTRUSTED", "不应参与", "调用者内容")));

        assertThat(hasher.hash("1", base, selected))
                .isEqualTo(hasher.hash("1", callerPopulated, selected));
    }

    private PaintingGuideContext context(String generatedText, String musicDescription) {
        return new PaintingGuideContext(
                "9da3014d-7a8d-4842-8334-39ad8102f9dd",
                new PaintingGuideContext.Basic("东坡夜游赤壁图", "现代", "近现代", "近现代", null, null),
                new PaintingGuideContext.Artist("李可染", "1907", "江苏徐州", null),
                new PaintingGuideContext.Art(
                        "国画", "苏轼夜游赤壁", "山水画", "写意", "水墨", "横卷",
                        "清逸怀古", "凝练", "积墨", "纸本", "墨", null, "苏轼、赤壁"),
                new PaintingGuideContext.OfficialAnnotations(generatedText, musicDescription),
                List.of()
        );
    }

    private KnowledgeSelection selection(String content, Map<String, String> fingerprints) {
        return new KnowledgeSelection(List.of(new KnowledgeItem(
                "赤壁", "POETRY_GRAPH_NODE", "赤壁", content)), fingerprints);
    }
}
