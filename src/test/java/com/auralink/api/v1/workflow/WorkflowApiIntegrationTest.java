package com.auralink.api.v1.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.auralink.config.properties.WorkflowProperties;
import com.auralink.entity.User;
import com.auralink.entity.UserWorkflow;
import com.auralink.repository.UserRepository;
import com.auralink.repository.UserWorkflowRepository;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.WorkflowTestDefinitions;
import com.auralink.workflow.service.StoredWorkflowDefinition;
import com.auralink.workflow.service.WorkflowResponseMapper;
import com.auralink.workflow.snapshot.WorkflowSnapshotFactory;
import com.auralink.workflow.snapshot.WorkflowSnapshotResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.persistence.EntityManager;

@SpringBootTest(properties = {
        "spring.config.import=optional:file:/tmp/auralink-round7-api-no-env.properties",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.datasource.hikari.connection-init-sql=PRAGMA foreign_keys=ON",
        "auralink.jwt.secret=round7-workflow-api-test-secret-not-used-outside-tests",
        "auralink.paintings.import-enabled=false",
        "auralink.guide.enabled=false",
        "auralink.providers.seedream.api-key=",
        "auralink.providers.qwen.api-key=",
        "auralink.providers.painting-music.api-key=",
        "auralink.providers.guide.api-key=",
        "auralink.providers.video.api-key=",
        "auralink.workflows.enabled=true"
})
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WorkflowApiIntegrationTest {

    private static final Path ROOT = Path.of(
            "/tmp", "auralink-round7-workflow-api-" + UUID.randomUUID());
    private static final Path DATABASE = ROOT.resolve("workflow-api.db");
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Pattern FIXED_MILLIS_UTC = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    private static final List<String> UNRELATED_TABLES = List.of(
            "generation_logs",
            "media_assets",
            "paintings",
            "painting_guides",
            "painting_favorites",
            "creations",
            "creation_steps",
            "creation_favorites");

    @DynamicPropertySource
    static void isolatedRuntime(DynamicPropertyRegistry registry) {
        try {
            Files.createDirectories(ROOT);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to create isolated workflow test root", exception);
        }
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("auralink.media-assets.managed-dir", () -> ROOT.resolve("managed").toString());
        registry.add("auralink.paintings.metadata-csv-path", () -> ROOT.resolve("unused.csv").toString());
        registry.add("auralink.paintings.picture-dir", () -> ROOT.resolve("catalog").toString());
        registry.add("auralink.storage.upload-dir", () -> ROOT.resolve("legacy-uploads").toString());
        registry.add("auralink.storage.audio-dir", () -> ROOT.resolve("legacy-audio").toString());
        registry.add("auralink.storage.legacy-frontend-audio-dir",
                () -> ROOT.resolve("legacy-frontend-audio").toString());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private UserWorkflowRepository workflows;
    @Autowired private WorkflowProperties workflowProperties;
    @Autowired private WorkflowResponseMapper responseMapper;
    @Autowired private WorkflowSnapshotFactory snapshotFactory;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    void isolateTestRows() {
        workflowProperties.setEnabled(true);
        jdbc.update("DELETE FROM user_workflows");
        jdbc.update("DELETE FROM users");
        entityManager.clear();
    }

    @AfterEach
    void restoreFeatureFlag() {
        workflowProperties.setEnabled(true);
    }

    @Test
    void everyWorkflowRouteRequiresAuthentication() throws Exception {
        String definition = json(definition(
                "anonymous", WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING));
        String id = UUID.randomUUID().toString();

        mockMvc.perform(get("/api/v1/workflow/node-types"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/v1/me/workflows/validate")
                        .contentType(MediaType.APPLICATION_JSON).content(definition))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/me/workflows")
                        .contentType(MediaType.APPLICATION_JSON).content(definition))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/workflows"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/workflows/{id}", id))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/me/workflows/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content(definition))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/me/workflows/{id}", id))
                .andExpect(status().isUnauthorized());

        assertThat(workflows.count()).isZero();
    }

    @Test
    void capabilityCatalogIsExactSafeStaticAndReadableWhenFeatureDisabled() throws Exception {
        User owner = saveUser("capability");
        JsonNode enabled = body(mockMvc.perform(get("/api/v1/workflow/node-types")
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowSchemaVersion").value(1))
                .andExpect(jsonPath("$.featureEnabled").value(true))
                .andExpect(jsonPath("$.sourceModalities.length()").value(4))
                .andExpect(jsonPath("$.operations.length()").value(6))
                .andReturn());

        assertThat(textValues(enabled.path("sourceModalities")))
                .containsExactly("TEXT_DESCRIPTION", "POEM", "IMAGE", "PAINTING");
        Map<String, String> expectedProviders = Map.of(
                "TEXT_TO_PAINTING", "seedream-5",
                "POEM_TO_PAINTING", "qwen3vl-seedream5",
                "IMAGE_TO_PAINTING", "seedream-5",
                "PAINTING_TO_MUSIC", "auralink-vmm",
                "PAINTING_TO_POEM", "qwen3-vl-plus",
                "PAINTING_TO_VIDEO", "reserved-video");
        int enabledDefinitions = 0;
        for (JsonNode operation : enabled.path("operations")) {
            String code = operation.path("code").asText();
            assertThat(operation.path("providers")).hasSize(1);
            assertThat(operation.path("providers").get(0).path("code").asText())
                    .isEqualTo(expectedProviders.get(code));
            assertThat(operation.path("executionAvailable").asBoolean()).isFalse();
            assertThat(operation.path("providers").get(0).path("executionAvailable").asBoolean())
                    .isFalse();
            assertThat(operation.path("providers").get(0).path("parameterSchema").path("type").asText())
                    .isEqualTo("object");
            assertThat(operation.path("providers").get(0).path("parameterSchema").path("properties").size())
                    .isZero();
            assertThat(operation.path("providers").get(0).path("parameterSchema")
                    .path("additionalProperties").asBoolean()).isFalse();
            if (operation.path("definitionEnabled").asBoolean()) {
                enabledDefinitions++;
                assertThat(operation.path("availabilityReason").asText())
                        .isEqualTo("PAINTING_TO_MUSIC".equals(code)
                                ? "PAINTING_TO_MUSIC_DEFERRED_NOT_VALIDATED"
                                : "CREATIONS_FEATURE_DISABLED");
            }
        }
        assertThat(enabledDefinitions).isEqualTo(5);
        JsonNode video = operation(enabled, "PAINTING_TO_VIDEO");
        assertThat(video.path("definitionEnabled").asBoolean()).isFalse();
        assertThat(video.path("executionAvailable").asBoolean()).isFalse();
        assertThat(video.path("terminalOutput").asBoolean()).isTrue();
        assertThat(video.path("availabilityReason").asText())
                .isEqualTo("RESERVED_FOR_FUTURE_IMPLEMENTATION");

        String serialized = enabled.toString().toLowerCase(Locale.ROOT);
        for (String forbidden : List.of(
                "apikey", "token", "authorization", "cookie", "password", "secret",
                "baseurl", "endpoint", "localhost", "/tmp/", "filesystem")) {
            assertThat(serialized).as(forbidden).doesNotContain(forbidden);
        }

        workflowProperties.setEnabled(false);
        mockMvc.perform(get("/api/v1/workflow/node-types")
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureEnabled").value(false))
                .andExpect(jsonPath("$.operations.length()").value(6));
    }

    @Test
    void featureDisabledRejectsValidationAndAllCrudWithoutMutation() throws Exception {
        User owner = saveUser("disabled");
        String request = json(definition(
                "disabled", WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING));
        String id = UUID.randomUUID().toString();
        workflowProperties.setEnabled(false);

        mockMvc.perform(post("/api/v1/me/workflows/validate")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WORKFLOWS_DISABLED"));
        mockMvc.perform(post("/api/v1/me/workflows")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WORKFLOWS_DISABLED"));
        mockMvc.perform(get("/api/v1/me/workflows")
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/v1/me/workflows/{id}", id)
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(put("/api/v1/me/workflows/{id}", id)
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(delete("/api/v1/me/workflows/{id}", id)
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isServiceUnavailable());

        assertThat(workflows.count()).isZero();
    }

    @Test
    void validateReturnsCanonicalGraphOrDeterministicViolationsAndNeverPersists() throws Exception {
        User owner = saveUser("validate");
        ObjectNode valid = definition(
                "  Validate me  ", WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING,
                WorkflowOperation.PAINTING_TO_MUSIC);
        reverseArrays(valid);

        JsonNode validResponse = body(mockMvc.perform(post("/api/v1/me/workflows/validate")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(valid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.sourceModality").value("TEXT_DESCRIPTION"))
                .andExpect(jsonPath("$.terminalModality").value("AUDIO"))
                .andExpect(jsonPath("$.nodeCount").value(3))
                .andExpect(jsonPath("$.edgeCount").value(2))
                .andExpect(jsonPath("$.operationSequence[0]").value("TEXT_TO_PAINTING"))
                .andExpect(jsonPath("$.operationSequence[1]").value("PAINTING_TO_MUSIC"))
                .andExpect(jsonPath("$.canonicalGraph.nodes[0].id").value("source"))
                .andExpect(jsonPath("$.canonicalGraph.nodes[1].id").value("step1"))
                .andExpect(jsonPath("$.canonicalGraph.nodes[2].id").value("step2"))
                .andExpect(jsonPath("$.violations.length()").value(0))
                .andReturn());
        assertThat(validResponse.path("canonicalGraph").toString())
                .isEqualTo(canonicalGraphJson(validResponse));
        assertThat(workflows.count()).isZero();

        ObjectNode invalid = definition(
                "invalid", WorkflowModality.PAINTING,
                WorkflowOperation.PAINTING_TO_VIDEO);
        invalid.put("userId", 999);
        invalid.withObject("/graph").withArray("nodes").get(1)
                .withObject("/parameters").put("temperature", 1);
        String first = mockMvc.perform(post("/api/v1/me/workflows/validate")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(invalid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.canonicalGraph").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.violations[*].code", org.hamcrest.Matchers.hasItems(
                        "UNKNOWN_FIELD", "OPERATION_DISABLED", "PARAMETERS_NOT_ALLOWED")))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/v1/me/workflows/validate")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(invalid)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(first).path("violations"))
                .isEqualTo(objectMapper.readTree(second).path("violations"));
        assertThat(workflows.count()).isZero();

        mockMvc.perform(post("/api/v1/me/workflows/validate")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("JsonParseException"))));
    }

    @Test
    void createUsesJwtOwnerCanonicalStorageAndPersistedMillisecondTimestamps() throws Exception {
        User owner = saveUser("create-owner");
        User attemptedOwner = saveUser("create-attempted");
        ObjectNode request = definition(
                "  我的工作流  ", WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING,
                WorkflowOperation.PAINTING_TO_POEM);
        request.put("description", "  可选说明  ");

        MvcResult result = mockMvc.perform(post("/api/v1/me/workflows")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("我的工作流"))
                .andExpect(jsonPath("$.description").value("可选说明"))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.sourceModality").value("TEXT_DESCRIPTION"))
                .andExpect(jsonPath("$.terminalModality").value("POEM"))
                .andExpect(jsonPath("$.nodeCount").value(3))
                .andExpect(jsonPath("$.edgeCount").value(2))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andReturn();
        JsonNode response = body(result);
        String workflowId = response.path("workflowId").asText();
        assertThat(UUID.fromString(workflowId).toString()).isEqualTo(workflowId);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, public_id, user_id, name, description, graph_json, "
                        + "schema_version, status, created_at, updated_at "
                        + "FROM user_workflows WHERE public_id = ?",
                workflowId);
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(owner.getId());
        assertThat(((Number) row.get("user_id")).longValue()).isNotEqualTo(attemptedOwner.getId());
        assertThat(row.get("name")).isEqualTo("我的工作流");
        assertThat(row.get("description")).isEqualTo("可选说明");
        assertThat(((Number) row.get("schema_version")).intValue()).isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("graph_json")).isEqualTo(response.path("graph").toString());
        assertPersistedTimestamp(response.path("createdAt").asText(), row.get("created_at"));
        assertPersistedTimestamp(response.path("updatedAt").asText(), row.get("updated_at"));

        String serialized = result.getResponse().getContentAsString();
        assertThat(serialized).doesNotContain(
                "password", "ownerUser", "internalToken", ROOT.toString());

        ObjectNode attemptedOwnership = request.deepCopy();
        attemptedOwnership.put("userId", attemptedOwner.getId());
        mockMvc.perform(post("/api/v1/me/workflows")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(attemptedOwnership)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORKFLOW_INVALID"))
                .andExpect(jsonPath("$.violations[0].code").value("UNKNOWN_FIELD"));

        ObjectNode unsupported = request.deepCopy();
        unsupported.withObject("/graph").put("schemaVersion", 2);
        mockMvc.perform(post("/api/v1/me/workflows")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(unsupported)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORKFLOW_SCHEMA_UNSUPPORTED"));

        ObjectNode oversized = request.deepCopy();
        oversized.withObject("/graph").put("padding", "x".repeat(70_000));
        mockMvc.perform(post("/api/v1/me/workflows")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(oversized)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORKFLOW_GRAPH_TOO_LARGE"));
        assertThat(workflows.count()).isEqualTo(1);
    }

    @Test
    void listIsOwnerIsolatedStableBoundedAndOmitsGraphs() throws Exception {
        User firstOwner = saveUser("list-first");
        User secondOwner = saveUser("list-second");
        String first = create(firstOwner, definition(
                "first", WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING));
        String second = create(firstOwner, definition(
                "first", WorkflowModality.PAINTING,
                WorkflowOperation.PAINTING_TO_MUSIC));
        String newest = create(firstOwner, definition(
                "newest", WorkflowModality.PAINTING,
                WorkflowOperation.PAINTING_TO_POEM));
        String other = create(secondOwner, definition(
                "other", WorkflowModality.IMAGE,
                WorkflowOperation.IMAGE_TO_PAINTING));

        long tiedMillis = 1_800_000_000_000L;
        jdbc.update("UPDATE user_workflows SET updated_at = ? WHERE public_id IN (?, ?)",
                tiedMillis, first, second);
        jdbc.update("UPDATE user_workflows SET updated_at = ? WHERE public_id = ?",
                tiedMillis + 1_000, newest);
        entityManager.clear();

        JsonNode page = body(mockMvc.perform(get("/api/v1/me/workflows")
                        .with(user(firstOwner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].workflowId").value(newest))
                .andExpect(jsonPath("$.items[0].graph").doesNotExist())
                .andExpect(jsonPath("$.items[0].id").doesNotExist())
                .andExpect(jsonPath("$.items[0].userId").doesNotExist())
                .andExpect(jsonPath("$.items[0].status").doesNotExist())
                .andReturn());
        List<String> tiedIds = new ArrayList<>(List.of(first, second));
        tiedIds.sort(Comparator.naturalOrder());
        assertThat(textValues(page.path("items"), "workflowId"))
                .containsExactly(newest, tiedIds.get(0), tiedIds.get(1));
        assertThat(page.toString()).doesNotContain(other);

        mockMvc.perform(get("/api/v1/me/workflows?page=0&size=2")
                        .with(user(firstOwner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get("/api/v1/me/workflows")
                        .with(user(secondOwner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].workflowId").value(other));
        mockMvc.perform(get("/api/v1/me/workflows?size=101")
                        .with(user(firstOwner.getUsername()).roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
        mockMvc.perform(get("/api/v1/me/workflows?page=-1")
                        .with(user(firstOwner.getUsername()).roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void detailAndReplacementAreOwnerScopedAndInvalidUpdatesAreAtomic() throws Exception {
        User owner = saveUser("replace-owner");
        User other = saveUser("replace-other");
        String workflowId = create(owner, definition(
                "original", WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING));

        mockMvc.perform(get("/api/v1/me/workflows/{id}", workflowId)
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(workflowId));
        assertSafeNotFound(get("/api/v1/me/workflows/{id}", workflowId)
                .with(user(other.getUsername()).roles("USER")));
        assertSafeNotFound(get("/api/v1/me/workflows/{id}", UUID.randomUUID())
                .with(user(owner.getUsername()).roles("USER")));
        assertSafeNotFound(get("/api/v1/me/workflows/not-a-uuid")
                .with(user(owner.getUsername()).roles("USER")));

        Map<String, Object> before = workflowRow(workflowId);
        ObjectNode invalid = definition(
                "invalid", WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING);
        invalid.withObject("/graph").withArray("nodes").get(1)
                .withObject("/parameters").put("style", "forbidden");
        mockMvc.perform(put("/api/v1/me/workflows/{id}", workflowId)
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(invalid)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORKFLOW_INVALID"))
                .andExpect(jsonPath("$.violations[*].code",
                        org.hamcrest.Matchers.hasItem("PARAMETERS_NOT_ALLOWED")));
        assertThat(workflowRow(workflowId)).isEqualTo(before);

        ObjectNode replacement = definition(
                "  replacement  ", WorkflowModality.POEM,
                WorkflowOperation.POEM_TO_PAINTING,
                WorkflowOperation.PAINTING_TO_MUSIC);
        reverseArrays(replacement);
        JsonNode updated = body(mockMvc.perform(put("/api/v1/me/workflows/{id}", workflowId)
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(workflowId))
                .andExpect(jsonPath("$.name").value("replacement"))
                .andExpect(jsonPath("$.sourceModality").value("POEM"))
                .andExpect(jsonPath("$.terminalModality").value("AUDIO"))
                .andExpect(jsonPath("$.graph.nodes[0].id").value("source"))
                .andExpect(jsonPath("$.graph.nodes[1].id").value("step1"))
                .andExpect(jsonPath("$.graph.nodes[2].id").value("step2"))
                .andReturn());
        Map<String, Object> persisted = workflowRow(workflowId);
        assertThat(((Number) persisted.get("user_id")).longValue()).isEqualTo(owner.getId());
        assertThat(persisted.get("public_id")).isEqualTo(workflowId);
        assertThat(persisted.get("graph_json")).isEqualTo(updated.path("graph").toString());
        assertPersistedTimestamp(updated.path("updatedAt").asText(), persisted.get("updated_at"));

        ObjectNode otherReplacement = definition(
                "other", WorkflowModality.IMAGE,
                WorkflowOperation.IMAGE_TO_PAINTING);
        mockMvc.perform(put("/api/v1/me/workflows/{id}", workflowId)
                        .with(user(other.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(otherReplacement)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKFLOW_NOT_FOUND"));
        assertThat(((Number) workflowRow(workflowId).get("user_id")).longValue())
                .isEqualTo(owner.getId());
    }

    @Test
    void deleteAndAllWorkflowDefinitionActivityLeaveHistoryAndExecutionTablesUntouched()
            throws Exception {
        User owner = saveUser("delete-owner");
        User other = saveUser("delete-other");
        Map<String, Integer> before = tableCounts(UNRELATED_TABLES);
        String workflowId = create(owner, definition(
                "delete", WorkflowModality.IMAGE,
                WorkflowOperation.IMAGE_TO_PAINTING,
                WorkflowOperation.PAINTING_TO_MUSIC));
        Object updatedAtBeforeValidation = workflowRow(workflowId).get("updated_at");

        mockMvc.perform(post("/api/v1/me/workflows/validate")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(definition(
                                "validate only", WorkflowModality.PAINTING,
                                WorkflowOperation.PAINTING_TO_POEM))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        assertThat(workflowRow(workflowId).get("updated_at"))
                .isEqualTo(updatedAtBeforeValidation);
        mockMvc.perform(delete("/api/v1/me/workflows/{id}", workflowId)
                        .with(user(other.getUsername()).roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKFLOW_NOT_FOUND"));
        assertThat(workflows.findByPublicId(workflowId)).isPresent();
        mockMvc.perform(delete("/api/v1/me/workflows/{id}", workflowId)
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(workflows.findByPublicId(workflowId)).isEmpty();
        assertThat(tableCounts(UNRELATED_TABLES)).isEqualTo(before);
        assertThat(jdbc.queryForObject("PRAGMA integrity_check", String.class)).isEqualTo("ok");
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }

    @Test
    void snapshotRemainsByteIdenticalAfterWorkflowReplacementAndDeletion() throws Exception {
        User owner = saveUser("snapshot");
        String workflowId = create(owner, definition(
                "snapshot original", WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING,
                WorkflowOperation.PAINTING_TO_POEM));
        UserWorkflow persisted = workflows.findByPublicId(workflowId).orElseThrow();
        StoredWorkflowDefinition parsed = responseMapper.parse(persisted);
        WorkflowSnapshotResult snapshot = snapshotFactory.create(
                persisted.getPublicId(), persisted.getName(), persisted.getSchemaVersion(), parsed.graph());
        String originalJson = snapshot.canonicalJson();

        mockMvc.perform(put("/api/v1/me/workflows/{id}", workflowId)
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(definition(
                                "snapshot replacement", WorkflowModality.PAINTING,
                                WorkflowOperation.PAINTING_TO_MUSIC))))
                .andExpect(status().isOk());
        assertThat(snapshot.canonicalJson()).isEqualTo(originalJson);
        assertThat(snapshot.snapshot().workflowName()).isEqualTo("snapshot original");
        assertThat(snapshot.snapshot().graph().nodes()).hasSize(3);

        mockMvc.perform(delete("/api/v1/me/workflows/{id}", workflowId)
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isNoContent());
        assertThat(snapshot.canonicalJson()).isEqualTo(originalJson);
        assertThat(objectMapper.readTree(originalJson).path("snapshotVersion").asInt()).isEqualTo(1);
        assertThat(objectMapper.readTree(originalJson).path("workflowId").asText())
                .isEqualTo(workflowId);
        assertThat(originalJson).doesNotContain(
                "userId", "owner", "createdAt", "updatedAt", "password", "secret");
        assertThat(workflows.findByPublicId(workflowId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creations", Integer.class)).isZero();
    }

    private User saveUser(String prefix) {
        int id = IDS.incrementAndGet();
        return users.saveAndFlush(User.builder()
                .username(prefix + "-" + id)
                .password("round7-test-password-hash")
                .fullName("Round 7 Test User")
                .email(prefix + "-" + id + "@example.invalid")
                .role("ROLE_USER")
                .build());
    }

    private String create(User owner, ObjectNode request) throws Exception {
        return body(mockMvc.perform(post("/api/v1/me/workflows")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().isCreated())
                .andReturn()).path("workflowId").asText();
    }

    private ObjectNode definition(
            String name,
            WorkflowModality sourceModality,
            WorkflowOperation... operations) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("name", name);
        root.put("description", "Round 7 integration definition");
        ObjectNode graph = root.putObject("graph");
        graph.put("schemaVersion", 1);
        ArrayNode nodes = graph.putArray("nodes");
        nodes.addObject()
                .put("id", "source")
                .put("kind", "SOURCE")
                .put("outputModality", sourceModality.name());
        ArrayNode edges = graph.putArray("edges");
        String previous = "source";
        for (int index = 0; index < operations.length; index++) {
            WorkflowOperation operation = operations[index];
            String nodeId = "step" + (index + 1);
            ObjectNode node = nodes.addObject();
            node.put("id", nodeId);
            node.put("kind", "TRANSFORM");
            node.put("operation", operation.name());
            node.put("providerCode", WorkflowTestDefinitions.provider(operation));
            node.put("inputModality", WorkflowTestDefinitions.input(operation).name());
            node.put("outputModality", WorkflowTestDefinitions.output(operation).name());
            node.putObject("parameters");
            edges.addObject().put("from", previous).put("to", nodeId);
            previous = nodeId;
        }
        return root;
    }

    private void reverseArrays(ObjectNode request) {
        ObjectNode graph = request.withObject("/graph");
        reverse(graph.withArray("nodes"));
        reverse(graph.withArray("edges"));
    }

    private static void reverse(ArrayNode array) {
        List<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        array.removeAll();
        for (int index = values.size() - 1; index >= 0; index--) {
            array.add(values.get(index));
        }
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String json(JsonNode value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static List<String> textValues(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asText()));
        return values;
    }

    private static JsonNode operation(JsonNode capability, String code) {
        for (JsonNode operation : capability.path("operations")) {
            if (code.equals(operation.path("code").asText())) {
                return operation;
            }
        }
        throw new AssertionError("Missing operation " + code);
    }

    private String canonicalGraphJson(JsonNode validationResponse) throws Exception {
        return objectMapper.writeValueAsString(validationResponse.path("canonicalGraph"));
    }

    private Map<String, Object> workflowRow(String workflowId) {
        return new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT public_id, user_id, name, description, graph_json, schema_version, "
                        + "status, created_at, updated_at FROM user_workflows WHERE public_id = ?",
                workflowId));
    }

    private Map<String, Integer> tableCounts(List<String> tables) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : tables) {
            // Table identifiers come only from the fixed test whitelist above.
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
        return counts;
    }

    private static void assertPersistedTimestamp(String publicTimestamp, Object persistedValue) {
        assertThat(publicTimestamp).matches(FIXED_MILLIS_UTC);
        assertThat(persistedValue).isInstanceOf(Number.class);
        assertThat(Instant.parse(publicTimestamp).toEpochMilli())
                .isEqualTo(((Number) persistedValue).longValue());
        assertThat(Instant.parse(publicTimestamp).getNano() % 1_000_000).isZero();
    }

    private void assertSafeNotFound(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKFLOW_NOT_FOUND"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("user_id"))));
    }
}
