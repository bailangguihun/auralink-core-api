package com.auralink.api.v1.creation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.config.properties.WorkflowProperties;
import com.auralink.creation.CreationExecutionCapabilityService;
import com.auralink.entity.User;
import com.auralink.repository.UserRepository;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.WorkflowTestDefinitions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

/** API-only coverage for ROUND 9B.1 admission; provider execution is mocked out. */
@SpringBootTest(properties = {
        "spring.config.import=optional:file:/tmp/auralink-round9b1-api-no-env.properties",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.datasource.hikari.connection-init-sql=PRAGMA foreign_keys=ON",
        "auralink.jwt.secret=round9b1-creation-api-test-secret-not-used-outside-tests",
        "auralink.paintings.import-enabled=false",
        "auralink.guide.enabled=false",
        "auralink.workflows.enabled=true",
        "auralink.creations.enabled=true",
        "auralink.creations.dispatch-delay=3600000",
        "auralink.creation-providers.enabled=false"
})
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CreationApiIntegrationTest {

    private static final Path ROOT = Path.of("/tmp", "auralink-round9b1-api-" + UUID.randomUUID());
    private static final Path DATABASE = ROOT.resolve("creation-api.db");
    private static final AtomicInteger IDS = new AtomicInteger();

    @DynamicPropertySource
    static void isolatedRuntime(DynamicPropertyRegistry registry) {
        try {
            Files.createDirectories(ROOT);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to create isolated Creation API root", exception);
        }
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("auralink.media-assets.managed-dir", () -> ROOT.resolve("managed").toString());
        registry.add("auralink.paintings.metadata-csv-path", () -> ROOT.resolve("unused.csv").toString());
        registry.add("auralink.paintings.picture-dir", () -> ROOT.resolve("catalog").toString());
        registry.add("auralink.storage.upload-dir", () -> ROOT.resolve("uploads").toString());
        registry.add("auralink.storage.audio-dir", () -> ROOT.resolve("audio").toString());
        registry.add("auralink.storage.legacy-frontend-audio-dir", () -> ROOT.resolve("legacy-audio").toString());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private WorkflowProperties workflowProperties;
    @Autowired private CreationExecutionProperties creationProperties;
    @Autowired private EntityManager entityManager;
    @MockBean private CreationExecutionCapabilityService capabilityAdmission;

    @BeforeEach
    void reset() {
        creationProperties.setEnabled(true);
        workflowProperties.setEnabled(true);
        jdbc.update("DELETE FROM creation_favorites");
        jdbc.update("DELETE FROM creation_steps");
        jdbc.update("DELETE FROM creations");
        jdbc.update("DELETE FROM user_workflows");
        jdbc.update("DELETE FROM users");
        entityManager.clear();
        clearInvocations(capabilityAdmission);
    }

    @AfterEach
    void disableCreationFeatureAfterTest() {
        creationProperties.setEnabled(false);
    }

    @Test
    void routesRequireAuthentication() throws Exception {
        String id = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/creations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/creations/{id}", id)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/creations")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/creations/{id}/retry", id)
                        .header("Idempotency-Key", "retry-unauthenticated-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRetryVersion\":0}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledFeatureRejectsSubmissionBeforeAnyCreationRowIsWritten() throws Exception {
        User owner = saveUser("disabled");
        String workflowId = createWorkflow(owner, WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING);
        creationProperties.setEnabled(false);

        mockMvc.perform(post("/api/v1/creations")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textSubmission(workflowId, "山水")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CREATIONS_DISABLED"));
        assertThat(count("creations")).isZero();
        assertThat(count("creation_steps")).isZero();
    }

    @Test
    void acceptsTextAdmissionPersistsOnlyQueuedPendingDataAndHidesPrivateInputs() throws Exception {
        User owner = saveUser("owner");
        User other = saveUser("other");
        String workflowId = createWorkflow(owner, WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING, WorkflowOperation.PAINTING_TO_POEM);
        int logsBefore = count("generation_logs");
        int assetsBefore = count("media_assets");
        int paintingsBefore = count("paintings");

        JsonNode accepted = body(mockMvc.perform(post("/api/v1/creations")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textSubmission(workflowId, "春江花月夜")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn());
        String creationId = accepted.path("creationId").asText();
        assertThat(UUID.fromString(creationId).toString()).isEqualTo(creationId);
        assertThat(count("creations")).isEqualTo(1);
        assertThat(count("creation_steps")).isEqualTo(2);
        assertThat(count("creation_execution_attempts")).isEqualTo(1);
        assertThat(count("generation_logs")).isEqualTo(logsBefore);
        assertThat(count("media_assets")).isEqualTo(assetsBefore);
        assertThat(count("paintings")).isEqualTo(paintingsBefore);

        Map<String, Object> creation = jdbc.queryForMap("SELECT status, source_text, workflow_snapshot, "
                + "error_code, error_message, claim_token, lease_expires_at FROM creations WHERE public_id=?", creationId);
        assertThat(creation.get("status")).isEqualTo("QUEUED");
        assertThat(creation.get("source_text")).isEqualTo("春江花月夜");
        assertThat(creation.get("workflow_snapshot").toString())
                .doesNotContain("春江花月夜")
                .doesNotContain("assetId")
                .doesNotContain("paintingId")
                .doesNotContain("storageKey");
        assertThat(creation.get("error_code")).isNull();
        assertThat(creation.get("error_message")).isNull();
        assertThat(creation.get("claim_token")).isNull();
        assertThat(creation.get("lease_expires_at")).isNull();
        List<Map<String, Object>> steps = jdbc.queryForList("SELECT step_index, status, attempt_count, "
                + "provider_dispatch_state, provider_request_key, output_json FROM creation_steps "
                + "ORDER BY step_index");
        assertThat(steps).hasSize(2);
        for (int index = 0; index < steps.size(); index++) {
            assertThat(((Number) steps.get(index).get("step_index")).intValue()).isEqualTo(index);
            assertThat(steps.get(index).get("status")).isEqualTo("PENDING");
            assertThat(((Number) steps.get(index).get("attempt_count")).intValue()).isZero();
            assertThat(steps.get(index).get("provider_dispatch_state")).isEqualTo("NOT_SENT");
            assertThat(steps.get(index).get("provider_request_key")).isNull();
            assertThat(steps.get(index).get("output_json")).isNull();
        }
        Map<String, Object> initialAttempt = jdbc.queryForMap(
                "SELECT attempt_number, retry_idempotency_key_digest, finished_at FROM creation_execution_attempts "
                        + "WHERE creation_id=(SELECT id FROM creations WHERE public_id=?)", creationId);
        assertThat(((Number) initialAttempt.get("attempt_number")).intValue()).isEqualTo(1);
        assertThat(initialAttempt.get("retry_idempotency_key_digest")).isNull();
        assertThat(initialAttempt.get("finished_at")).isNull();
        verify(capabilityAdmission).requireExecutionAvailable(any());

        String response = mockMvc.perform(get("/api/v1/creations/{id}", creationId)
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creationId").value(creationId))
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[0].status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        for (String forbidden : List.of("春江花月夜", "workflowSnapshot", "parametersJson", "inputJson",
                "providerRequestKey", "claimToken", "leaseExpiresAt", "storageKey", "ownerId", "userId")) {
            assertThat(response).as(forbidden).doesNotContain(forbidden);
        }
        mockMvc.perform(get("/api/v1/creations/{id}", creationId)
                        .with(user(other.getUsername()).roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREATION_NOT_FOUND"));
    }

    @Test
    void rejectsUnknownIdentityFieldsAndListsOnlyTheCurrentOwnersStableCreations() throws Exception {
        User owner = saveUser("list-owner");
        User other = saveUser("list-other");
        String workflowId = createWorkflow(owner, WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING);
        String otherWorkflow = createWorkflow(other, WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING);
        mockMvc.perform(post("/api/v1/creations")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowId\":\"" + workflowId + "\",\"userId\":99,\"source\":{"
                                + "\"modality\":\"TEXT_DESCRIPTION\",\"text\":\"x\"}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CREATION_SOURCE_INVALID"));
        assertThat(count("creations")).isZero();

        String first = submit(owner, workflowId, "first");
        String second = submit(owner, workflowId, "second");
        String foreign = submit(other, otherWorkflow, "foreign");
        long tiedMillis = 1_800_000_000_000L;
        jdbc.update("UPDATE creations SET created_at=? WHERE public_id IN (?, ?)", tiedMillis, first, second);
        entityManager.clear();
        List<String> expectedTied = List.of(first, second).stream().sorted(Comparator.reverseOrder()).toList();

        JsonNode page = body(mockMvc.perform(get("/api/v1/me/creations?size=2")
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn());
        assertThat(page.path("items").size()).isEqualTo(2);
        assertThat(List.of(page.path("items").get(0).path("creationId").asText(),
                page.path("items").get(1).path("creationId").asText())).isEqualTo(expectedTied);
        assertThat(page.toString()).doesNotContain(foreign);
        mockMvc.perform(get("/api/v1/me/creations?size=101")
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownerSafeRetryIsIdempotentAndCreatesOnlyOneNewExecutionAttempt() throws Exception {
        User owner = saveUser("retry-owner");
        String workflowId = createWorkflow(owner, WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING);
        when(capabilityAdmission.availability(any(), any())).thenReturn(
                new CreationExecutionCapabilityService.ExecutionAvailability(
                        true, "READY_FOR_CONTROLLED_EXECUTION"));
        String creationId = submit(owner, workflowId, "可安全重试");
        jdbc.update("UPDATE creations SET status='FAILED', finished_at=CURRENT_TIMESTAMP, "
                + "error_code='CREATION_INPUT_INVALID' WHERE public_id=?", creationId);
        jdbc.update("UPDATE creation_execution_attempts SET finished_at=CURRENT_TIMESTAMP, "
                + "resolution_code='FAILED' WHERE creation_id=(SELECT id FROM creations WHERE public_id=?)", creationId);
        entityManager.clear();

        String key = "retry-api-key-0000000000000001";
        mockMvc.perform(post("/api/v1/creations/{id}/retry", creationId)
                        .with(user(owner.getUsername()).roles("USER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRetryVersion\":0}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.creationId").value(creationId))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.retryVersion").value(1))
                .andExpect(jsonPath("$.executionAttemptNumber").value(2))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.claimToken").doesNotExist())
                .andExpect(jsonPath("$.providerRequestKey").doesNotExist());

        mockMvc.perform(post("/api/v1/creations/{id}/retry", creationId)
                        .with(user(owner.getUsername()).roles("USER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRetryVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        mockMvc.perform(post("/api/v1/creations/{id}/retry", creationId)
                        .with(user(owner.getUsername()).roles("USER"))
                        .header("Idempotency-Key", "retry-api-different-key-000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRetryVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREATION_RETRY_VERSION_CONFLICT"));

        mockMvc.perform(post("/api/v1/creations/{id}/retry", creationId)
                        .with(user(owner.getUsername()).roles("USER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRetryVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREATION_RETRY_IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM creation_execution_attempts "
                + "WHERE creation_id=(SELECT id FROM creations WHERE public_id=?)", Integer.class, creationId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT retry_version FROM creations WHERE public_id=?", Integer.class,
                creationId)).isEqualTo(1);
    }

    @Test
    void retryKeepsCrossUserLookupIndistinguishableFromMissingCreation() throws Exception {
        User owner = saveUser("retry-private-owner");
        User other = saveUser("retry-private-other");
        String workflowId = createWorkflow(owner, WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING);
        String creationId = submit(owner, workflowId, "私有重试");

        mockMvc.perform(post("/api/v1/creations/{id}/retry", creationId)
                        .with(user(other.getUsername()).roles("USER"))
                        .header("Idempotency-Key", "retry-cross-user-key-00000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRetryVersion\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CREATION_NOT_FOUND"));
    }

    private User saveUser(String prefix) {
        int value = IDS.incrementAndGet();
        return users.saveAndFlush(User.builder()
                .username(prefix + "-" + value)
                .password("round9b1-test-password-hash")
                .fullName("Round 9B.1 Test User")
                .email(prefix + "-" + value + "@example.invalid")
                .role("ROLE_USER")
                .build());
    }

    private String createWorkflow(User owner, WorkflowModality source, WorkflowOperation... operations) throws Exception {
        return body(mockMvc.perform(post("/api/v1/me/workflows")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(WorkflowTestDefinitions.definition(source, operations))))
                .andExpect(status().isCreated())
                .andReturn()).path("workflowId").asText();
    }

    private String submit(User owner, String workflowId, String text) throws Exception {
        return body(mockMvc.perform(post("/api/v1/creations")
                        .with(user(owner.getUsername()).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textSubmission(workflowId, text)))
                .andExpect(status().isAccepted())
                .andReturn()).path("creationId").asText();
    }

    private static String textSubmission(String workflowId, String text) {
        return "{\"workflowId\":\"" + workflowId + "\",\"source\":{\"modality\":"
                + "\"TEXT_DESCRIPTION\",\"text\":\"" + text + "\"}}";
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
