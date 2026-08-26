package com.auralink.api.v1.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.auralink.config.properties.GuideProperties;
import com.auralink.entity.Painting;
import com.auralink.entity.PaintingGuide;
import com.auralink.guide.knowledge.KnowledgeContextBuilder;
import com.auralink.guide.knowledge.KnowledgeSelection;
import com.auralink.guide.model.GuideResult;
import com.auralink.guide.model.GuideSections;
import com.auralink.guide.provider.GuideGenerationResult;
import com.auralink.guide.provider.GuideProvider;
import com.auralink.guide.provider.GuideProviderException;
import com.auralink.repository.PaintingGuideRepository;
import com.auralink.repository.PaintingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.config.import=optional:file:/tmp/auralink-round6-api-no-env.properties",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.datasource.hikari.connection-init-sql=PRAGMA foreign_keys=ON",
        "auralink.jwt.secret=round6-guide-api-test-secret-that-is-not-used-elsewhere",
        "auralink.paintings.import-enabled=false",
        "auralink.guide.enabled=true",
        "auralink.guide.internal-token=round6-test-internal-token",
        "auralink.guide.schema-version=1",
        "auralink.guide.user-generation-limit=100",
        "auralink.guide.global-generation-limit=100"
})
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaintingGuideApiIntegrationTest {

    private static final Path ROOT = Path.of(
            "/tmp", "auralink-round6-guide-api-" + UUID.randomUUID());
    private static final Path DATABASE = ROOT.resolve("guide-api.db");
    private static final AtomicInteger IDS = new AtomicInteger();

    @DynamicPropertySource
    static void isolatedRuntime(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("auralink.media-assets.managed-dir", () -> ROOT.resolve("managed").toString());
        registry.add("auralink.paintings.metadata-csv-path", () -> ROOT.resolve("unused.csv").toString());
        registry.add("auralink.paintings.picture-dir", () -> ROOT.resolve("catalog").toString());
        registry.add("auralink.storage.upload-dir", () -> ROOT.resolve("legacy-uploads").toString());
        registry.add("auralink.storage.audio-dir", () -> ROOT.resolve("legacy-audio").toString());
        registry.add("auralink.storage.legacy-frontend-audio-dir",
                () -> ROOT.resolve("legacy-frontend-audio").toString());
        try {
            Files.createDirectories(ROOT);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to create isolated Guide API test root", exception);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private PaintingRepository paintings;
    @Autowired private PaintingGuideRepository guides;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private GuideProperties guideProperties;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private GuideProvider provider;
    @MockBean private KnowledgeContextBuilder knowledgeContextBuilder;

    @BeforeEach
    void prepareProvider() {
        reset(provider, knowledgeContextBuilder);
        guideProperties.setEnabled(true);
        guideProperties.setInternalToken("round6-test-internal-token");
        guideProperties.setUserGenerationLimit(100);
        guideProperties.setGlobalGenerationLimit(100);
        when(knowledgeContextBuilder.build(any())).thenReturn(
                new KnowledgeSelection(List.of(), Map.of("testKnowledge", "c".repeat(64))));
        when(provider.generate(anyString(), any())).thenAnswer(invocation ->
                new GuideGenerationResult(invocation.getArgument(0), validResult()));
    }

    @Test
    void everyGuideRouteRequiresAuthentication() throws Exception {
        Painting painting = savePainting("security");

        mockMvc.perform(get("/api/v1/paintings/{id}/guide", painting.getPublicId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/v1/paintings/{id}/guide", painting.getPublicId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/v1/paintings/{id}/guide/audio", painting.getPublicId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(provider, never()).generate(anyString(), any());
    }

    @Test
    void postGeneratesOnceThenPostAndGetReuseValidatedPersistentCache() throws Exception {
        Painting painting = savePainting("cache");

        String generatedResponse = mockMvc.perform(post("/api/v1/paintings/{id}/guide", painting.getPublicId())
                        .with(user("round6-user").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paintingId").value(painting.getPublicId()))
                .andExpect(jsonPath("$.schemaVersion").value("1"))
                .andExpect(jsonPath("$.summary").value("标准导览摘要"))
                .andExpect(jsonPath("$.sections.artistAndEra").value("画家与时代"))
                .andExpect(jsonPath("$.sections.subjectAndScene")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.highlights.length()").value(2))
                .andExpect(jsonPath("$.knowledgeReferences.length()").value(0))
                .andExpect(jsonPath("$.cacheStatus").value("GENERATED"))
                .andExpect(jsonPath("$.generatedAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString())
                .andExpect(jsonPath("$.sourceHash").doesNotExist())
                .andExpect(jsonPath("$.resultJson").doesNotExist())
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.model").doesNotExist())
                .andExpect(jsonPath("$.prompt").doesNotExist())
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(ROOT.toString()))))
                .andReturn().getResponse().getContentAsString();

        String hitResponse = mockMvc.perform(post("/api/v1/paintings/{id}/guide", painting.getPublicId())
                        .with(user("another-user").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheStatus").value("HIT"))
                .andExpect(jsonPath("$.summary").value("标准导览摘要"))
                .andReturn().getResponse().getContentAsString();
        String getResponse = mockMvc.perform(get("/api/v1/paintings/{id}/guide", painting.getPublicId())
                        .with(user("third-user").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheStatus").value("HIT"))
                .andReturn().getResponse().getContentAsString();

        verify(provider).generate(anyString(), any());
        assertThat(guides.count()).isPositive();
        assertStablePersistedTimestamps(painting, generatedResponse, hitResponse, getResponse);
    }

    @Test
    void getNeverGeneratesAndMissingGuideIsNotFound() throws Exception {
        Painting painting = savePainting("missing");

        mockMvc.perform(get("/api/v1/paintings/{id}/guide", painting.getPublicId())
                        .with(user("round6-user").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GUIDE_NOT_AVAILABLE"));

        verify(provider, never()).generate(anyString(), any());
    }

    @Test
    void cacheMissGenerationIsRateLimitedPerAuthenticatedPrincipal() throws Exception {
        guideProperties.setUserGenerationLimit(1);
        Painting first = savePainting("rate-first");
        Painting second = savePainting("rate-second");

        mockMvc.perform(post("/api/v1/paintings/{id}/guide", first.getPublicId())
                        .with(user("rate-user").roles("USER")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/paintings/{id}/guide", second.getPublicId())
                        .with(user("rate-user").roles("USER")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("GUIDE_RATE_LIMITED"));

        verify(provider, times(1)).generate(anyString(), any());
    }

    @Test
    void disabledTimeoutAndInvalidProviderResultsMapToSafeV1Errors() throws Exception {
        Painting disabled = savePainting("disabled");
        guideProperties.setEnabled(false);
        mockMvc.perform(post("/api/v1/paintings/{id}/guide", disabled.getPublicId())
                        .with(user("round6-user").roles("USER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GUIDE_DISABLED"));

        guideProperties.setEnabled(true);
        Painting timeout = savePainting("timeout");
        when(provider.generate(anyString(), any())).thenThrow(new GuideProviderException(
                GuideProviderException.Failure.TIMEOUT, true, "test-only timeout"));
        mockMvc.perform(post("/api/v1/paintings/{id}/guide", timeout.getPublicId())
                        .with(user("round6-user").roles("USER")))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("GUIDE_PROVIDER_TIMEOUT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("test-only"))));

        Painting invalid = savePainting("invalid-provider");
        doAnswer(invocation ->
                new GuideGenerationResult(invocation.getArgument(0), new GuideResult(
                        "1", "", validResult().sections(), List.of("one", "two"), List.of())))
                .when(provider).generate(anyString(), any());
        mockMvc.perform(post("/api/v1/paintings/{id}/guide", invalid.getPublicId())
                        .with(user("round6-user").roles("USER")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GUIDE_INVALID_RESPONSE"));
    }

    @Test
    void malformedUnknownInactiveAndReservedAudioAreSafe() throws Exception {
        Painting active = savePainting("audio");
        Painting inactive = savePainting("inactive");
        inactive.setStatus("INACTIVE");
        paintings.saveAndFlush(inactive);

        mockMvc.perform(get("/api/v1/paintings/not-a-uuid/guide")
                        .with(user("round6-user").roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAINTING_ID"));
        mockMvc.perform(post("/api/v1/paintings/{id}/guide", UUID.randomUUID())
                        .with(user("round6-user").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAINTING_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/paintings/{id}/guide", inactive.getPublicId())
                        .with(user("round6-user").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAINTING_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/paintings/{id}/guide/audio", active.getPublicId())
                        .with(user("round6-user").roles("USER")))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("GUIDE_TTS_NOT_ENABLED"));

        assertThat(guides.findByPaintingId(active.getId())).isEmpty();
    }

    @Test
    void guideGenerationWritesOnlyPaintingGuidesAndLeavesOfficialAnnotationsUntouched() throws Exception {
        Painting painting = savePainting("no-history");
        Map<String, Integer> before = counts();
        String generatedText = painting.getGeneratedText();
        String musicDescription = painting.getMusicSceneDescription();

        mockMvc.perform(post("/api/v1/paintings/{id}/guide", painting.getPublicId())
                        .with(user("round6-user").roles("USER")))
                .andExpect(status().isOk());

        Map<String, Integer> after = counts();
        assertThat(after.get("painting_guides")).isEqualTo(before.get("painting_guides") + 1);
        for (String table : List.of(
                "users", "generation_logs", "creations", "creation_steps",
                "media_assets", "painting_favorites")) {
            assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
        }
        Painting unchanged = paintings.findById(painting.getId()).orElseThrow();
        assertThat(unchanged.getGeneratedText()).isEqualTo(generatedText);
        assertThat(unchanged.getMusicSceneDescription()).isEqualTo(musicDescription);
    }

    private Painting savePainting(String suffix) {
        int sequence = IDS.incrementAndGet();
        return paintings.saveAndFlush(Painting.builder()
                .sourceKey("painting-dataset:round6-" + suffix + "-" + sequence + ".jpg")
                .imageStorageName("round6-" + suffix + "-" + sequence + ".jpg")
                .title("山水图")
                .authorName("测试画家")
                .creationYear("1650")
                .creationDynastyRaw("清朝")
                .creationDynastyNormalized("清代")
                .subject("山水")
                .composition("高远")
                .artisticConception("清远")
                .brushwork("披麻皴")
                .inkMethod("积墨")
                .generatedText("官方画作注释")
                .musicSceneDescription("幽远笛声")
                .imageAvailable(false)
                .visibleInGallery(false)
                .status("ACTIVE")
                .build());
    }

    private Map<String, Integer> counts() {
        return Map.of(
                "users", count("users"),
                "generation_logs", count("generation_logs"),
                "creations", count("creations"),
                "creation_steps", count("creation_steps"),
                "media_assets", count("media_assets"),
                "painting_favorites", count("painting_favorites"),
                "painting_guides", count("painting_guides"));
    }

    private int count(String table) {
        // Table names are a fixed test whitelist, never request-derived.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertStablePersistedTimestamps(
            Painting painting,
            String generatedResponse,
            String hitResponse,
            String getResponse) throws Exception {
        JsonNode generated = objectMapper.readTree(generatedResponse);
        JsonNode hit = objectMapper.readTree(hitResponse);
        JsonNode get = objectMapper.readTree(getResponse);
        PaintingGuide persisted = guides.findByPaintingId(painting.getId()).orElseThrow();
        Long persistedGeneratedMillis = jdbc.queryForObject(
                "SELECT generated_at FROM painting_guides WHERE painting_id = ?",
                Long.class,
                painting.getId());
        Long persistedUpdatedMillis = jdbc.queryForObject(
                "SELECT updated_at FROM painting_guides WHERE painting_id = ?",
                Long.class,
                painting.getId());

        assertTimestampMatchesPersistedRow(
                generated, hit, get, "generatedAt", persisted.getGeneratedAt(), persistedGeneratedMillis);
        assertTimestampMatchesPersistedRow(
                generated, hit, get, "updatedAt", persisted.getUpdatedAt(), persistedUpdatedMillis);
    }

    private static void assertTimestampMatchesPersistedRow(
            JsonNode generated,
            JsonNode hit,
            JsonNode get,
            String field,
            LocalDateTime persisted,
            long persistedEpochMillis) {
        assertThat(generated.path(field).isTextual()).isTrue();
        assertThat(hit.path(field).isTextual()).isTrue();
        assertThat(get.path(field).isTextual()).isTrue();

        String generatedValue = generated.path(field).textValue();
        assertThat(generatedValue)
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")
                .isEqualTo(hit.path(field).textValue())
                .isEqualTo(get.path(field).textValue());
        Instant responseTimestamp = Instant.parse(generatedValue);
        assertThat(responseTimestamp.toEpochMilli()).isEqualTo(persistedEpochMillis);
        assertThat(responseTimestamp.getNano() % 1_000_000).isZero();
        assertThat(persisted.getNano() % 1_000_000).isZero();
    }

    private static GuideResult validResult() {
        return new GuideResult(
                "1",
                "标准导览摘要",
                new GuideSections("画家与时代", null, "构图说明", "笔墨说明",
                        null, "意境说明", null, "音乐联想"),
                List.of("观察构图层次", "留意笔墨节奏"),
                List.of());
    }
}
