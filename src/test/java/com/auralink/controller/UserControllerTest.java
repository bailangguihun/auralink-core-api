package com.auralink.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import com.auralink.dto.ApiResponse;
import com.auralink.dto.response.GenerationLogResponse;
import com.auralink.dto.response.UserProfileResponse;
import com.auralink.entity.GenerationLog;
import com.auralink.entity.User;
import com.auralink.repository.GenerationLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private GenerationLogRepository generationLogRepository;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private UserController userController;

    private ObjectMapper objectMapper;
    private User user;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        user = User.builder()
                .id(7L)
                .username("legacy-user")
                .password("stored-password-hash")
                .fullName("Legacy User")
                .email("legacy@example.test")
                .enabled(true)
                .accountNonExpired(true)
                .accountNonLocked(true)
                .credentialsNonExpired(true)
                .role("ROLE_USER")
                .createdAt(LocalDateTime.of(2025, 7, 18, 8, 30))
                .updatedAt(LocalDateTime.of(2025, 7, 19, 9, 45))
                .build();
        when(authentication.getPrincipal()).thenReturn(user);
    }

    @Test
    void profileUsesSanitizedDtoWithoutMutatingAuthenticatedPrincipal() throws Exception {
        var response = userController.getProfile(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<UserProfileResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isTrue();

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(body));
        JsonNode data = root.path("data");

        assertThat(data.path("id").asLong()).isEqualTo(7L);
        assertThat(data.path("username").asText()).isEqualTo("legacy-user");
        assertThat(data.path("fullName").asText()).isEqualTo("Legacy User");
        assertThat(data.path("email").asText()).isEqualTo("legacy@example.test");
        assertThat(data.has("createdAt")).isTrue();
        assertThat(data.has("updatedAt")).isTrue();
        assertNoSecurityFields(root);

        assertThat(user.getPassword()).isEqualTo("stored-password-hash");
    }

    @Test
    void logsPreservePageMetadataAndAllLegacyLogFieldsWithoutEntitySecrets() throws Exception {
        GenerationLog log = completeGenerationLog(user);
        PageRequest pageRequest = PageRequest.of(1, 10);
        Page<GenerationLog> logs = new PageImpl<>(List.of(log), pageRequest, 21);
        when(generationLogRepository.findByUserOrderByCreatedAtDesc(user, pageRequest)).thenReturn(logs);

        var response = userController.getLogs(authentication, 1, 10, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<Page<GenerationLogResponse>> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isTrue();

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(body));
        JsonNode page = root.path("data");
        JsonNode item = page.path("content").path(0);

        assertThat(page.path("number").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(10);
        assertThat(page.path("totalElements").asLong()).isEqualTo(21L);
        assertThat(page.path("totalPages").asInt()).isEqualTo(3);
        assertThat(page.path("numberOfElements").asInt()).isEqualTo(1);

        assertThat(item.path("id").asLong()).isEqualTo(118L);
        assertThat(item.path("taskType").asText()).isEqualTo("IMAGE_TO_MUSIC");
        assertThat(item.path("type").asText()).isEqualTo("IMAGE_TO_MUSIC");
        assertThat(item.path("apiSource").asText()).isEqualTo("VMM");
        assertThat(item.path("apiProvider").asText()).isEqualTo("Legacy VMM");
        assertThat(item.path("inputData").asText()).isEqualTo("{\"prompt\":\"mountains\"}");
        assertThat(item.path("outputData").asText()).isEqualTo("{\"format\":\"wav\"}");
        assertThat(item.path("imageUrl").asText()).isEqualTo("uploads/input.jpg");
        assertThat(item.path("resultUrl").asText()).isEqualTo("audio/output.wav");
        assertThat(item.path("description").asText()).isEqualTo("Generated music");
        assertThat(item.path("modelSize").asText()).isEqualTo("medium");
        assertThat(item.path("useFastGenerate").asBoolean()).isTrue();
        assertThat(item.path("duration").asInt()).isEqualTo(30);
        assertThat(item.path("processingTimeMs").asLong()).isEqualTo(1250L);
        assertThat(item.path("success").asBoolean()).isTrue();
        assertThat(item.path("errorMessage").asText()).isEqualTo("");
        assertThat(item.path("metadata").asText()).isEqualTo("{\"source\":\"legacy\"}");
        assertThat(item.has("createdAt")).isTrue();

        JsonNode userSummary = item.path("user");
        assertThat(userSummary.path("id").asLong()).isEqualTo(7L);
        assertThat(userSummary.path("username").asText()).isEqualTo("legacy-user");
        assertThat(userSummary.path("fullName").asText()).isEqualTo("Legacy User");
        assertNoSecurityFields(root);

        verify(generationLogRepository).findByUserOrderByCreatedAtDesc(user, pageRequest);
    }

    @Test
    void logsRetainLegacyTaskTypeFilterBehavior() {
        PageRequest pageRequest = PageRequest.of(0, 5);
        when(generationLogRepository.findByTaskTypeAndUserOrderByCreatedAtDesc(
                "IMAGE_TO_MUSIC", user, pageRequest)).thenReturn(Page.empty(pageRequest));

        var response = userController.getLogs(authentication, 0, 5, "IMAGE_TO_MUSIC");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getContent()).isEmpty();
        verify(generationLogRepository).findByTaskTypeAndUserOrderByCreatedAtDesc(
                "IMAGE_TO_MUSIC", user, pageRequest);
    }

    private static GenerationLog completeGenerationLog(User owner) {
        return GenerationLog.builder()
                .id(118L)
                .user(owner)
                .taskType("IMAGE_TO_MUSIC")
                .apiSource("VMM")
                .apiProvider("Legacy VMM")
                .inputData("{\"prompt\":\"mountains\"}")
                .outputData("{\"format\":\"wav\"}")
                .imageUrl("uploads/input.jpg")
                .resultUrl("audio/output.wav")
                .description("Generated music")
                .modelSize("medium")
                .useFastGenerate(true)
                .duration(30)
                .processingTimeMs(1250L)
                .success(true)
                .errorMessage("")
                .metadata("{\"source\":\"legacy\"}")
                .createdAt(LocalDateTime.of(2025, 7, 20, 10, 15))
                .build();
    }

    private static void assertNoSecurityFields(JsonNode json) {
        String serialized = json.toString();
        assertThat(serialized)
                .doesNotContain("\"password\"")
                .doesNotContain("\"authorities\"")
                .doesNotContain("\"enabled\"")
                .doesNotContain("\"accountNonExpired\"")
                .doesNotContain("\"accountNonLocked\"")
                .doesNotContain("\"credentialsNonExpired\"")
                .doesNotContain("\"role\"");
    }
}
