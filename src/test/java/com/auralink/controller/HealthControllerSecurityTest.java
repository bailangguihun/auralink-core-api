package com.auralink.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.auralink.config.properties.ServiceProperties;
import com.auralink.config.properties.StorageProperties;
import com.auralink.repository.UserRepository;

class HealthControllerSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void detailsRetainComponentShapeWithoutInternalUrlsOrAbsolutePaths() {
        UserRepository users = mock(UserRepository.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        ServiceProperties services = new ServiceProperties();
        services.setVmmUrl("http://internal-vmm:5001");
        services.setNonvmmUrl("http://internal-qwen:5002");
        StorageProperties storage = new StorageProperties();
        storage.setUploadDir(tempDir.toString());

        when(users.count()).thenReturn(7L);
        when(restTemplate.getForEntity(eq("http://internal-vmm:5001/health"), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "ok")));
        when(restTemplate.getForEntity(eq("http://internal-qwen:5002/health"), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("status", "ok")));

        HealthController controller = new HealthController(users, restTemplate, services, storage);
        ResponseEntity<Map<String, Object>> response = controller.detailedHealthCheck();

        assertThat(response.getBody()).isNotNull();
        String serialized = response.getBody().toString();
        assertThat(serialized)
                .contains("database", "vmm-service", "nonvmm-service", "file-system")
                .doesNotContain("internal-vmm")
                .doesNotContain("internal-qwen")
                .doesNotContain(tempDir.toAbsolutePath().toString())
                .doesNotContain("upload_dir");
    }

    @Test
    void detailsFailClosedWithoutDisclosingRuntimeErrorsOrMalformedEndpoints() {
        UserRepository users = mock(UserRepository.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        ServiceProperties services = new ServiceProperties();
        services.setVmmUrl("malformed://internal-secret");
        services.setNonvmmUrl("http://internal-qwen:5002/");
        StorageProperties storage = new StorageProperties();
        storage.setUploadDir("\0private-storage-path");

        when(users.count()).thenThrow(new IllegalStateException("private database path"));
        when(restTemplate.getForEntity("malformed://internal-secret/health", Object.class))
                .thenThrow(new IllegalArgumentException("internal endpoint details"));
        when(restTemplate.getForEntity("http://internal-qwen:5002/health", Object.class))
                .thenThrow(new ResourceAccessException("private network details"));

        HealthController controller = new HealthController(users, restTemplate, services, storage);
        ResponseEntity<Map<String, Object>> response = controller.detailedHealthCheck();

        assertThat(response.getBody()).isNotNull();
        String serialized = response.getBody().toString();
        assertThat(serialized)
                .contains("database unavailable", "storage unavailable", "reachable=false")
                .doesNotContain("private database path")
                .doesNotContain("private-storage-path")
                .doesNotContain("internal endpoint details")
                .doesNotContain("private network details")
                .doesNotContain("internal-secret")
                .doesNotContain("internal-qwen");
        verify(restTemplate).getForEntity("http://internal-qwen:5002/health", Object.class);
    }
}
