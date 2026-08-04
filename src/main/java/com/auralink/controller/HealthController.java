package com.auralink.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.auralink.config.AppConfig.ServiceConfig;
import com.auralink.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping({"/", "/api"})
@RequiredArgsConstructor
public class HealthController {

    private final UserRepository userRepository;
    private final RestTemplate restTemplate;
    private final ServiceConfig serviceConfig;

    /**
     * 基础健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("message", "Auralink后端服务运行中");
        return ResponseEntity.ok(status);
    }

    /**
     * 详细系统检查
     */
    @GetMapping("/health/details")
    public ResponseEntity<Map<String, Object>> detailedHealthCheck() {
        Map<String, Object> status = new HashMap<>();
        Map<String, Object> components = new HashMap<>();
        boolean isSystemHealthy = true;

        // 检查数据库连接
        try {
            long userCount = userRepository.count();
            components.put("database", Map.of(
                    "status", "UP",
                    "details", "数据库连接正常，用户数: " + userCount
            ));
        } catch (Exception e) {
            isSystemHealthy = false;
            components.put("database", Map.of(
                    "status", "DOWN",
                    "error", e.getMessage()
            ));
            log.error("数据库健康检查失败: {}", e.getMessage());
        }

        // 检查VMM服务
        try {
            restTemplate.getForEntity(serviceConfig.getVmmUrl() + "/health", Object.class);
            components.put("vmm-service", Map.of(
                    "status", "UP",
                    "url", serviceConfig.getVmmUrl()
            ));
        } catch (RestClientException e) {
            components.put("vmm-service", Map.of(
                    "status", "DOWN",
                    "url", serviceConfig.getVmmUrl(),
                    "error", e.getMessage()
            ));
            log.warn("VMM服务健康检查失败: {}", e.getMessage());
        }

        // 检查非VMM服务
        try {
            restTemplate.getForEntity(serviceConfig.getNonvmmUrl() + "/health", Object.class);
            components.put("nonvmm-service", Map.of(
                    "status", "UP",
                    "url", serviceConfig.getNonvmmUrl()
            ));
        } catch (RestClientException e) {
            components.put("nonvmm-service", Map.of(
                    "status", "DOWN",
                    "url", serviceConfig.getNonvmmUrl(),
                    "error", e.getMessage()
            ));
            log.warn("非VMM服务健康检查失败: {}", e.getMessage());
        }

        // 添加文件系统信息
        try {
            java.io.File uploadDir = new java.io.File("./temp_uploads");
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }
            components.put("file-system", Map.of(
                    "status", "UP",
                    "upload_dir", uploadDir.getAbsolutePath(),
                    "writable", uploadDir.canWrite()
            ));
        } catch (Exception e) {
            isSystemHealthy = false;
            components.put("file-system", Map.of(
                    "status", "WARNING",
                    "error", e.getMessage()
            ));
        }

        status.put("status", isSystemHealthy ? "UP" : "DOWN");
        status.put("components", components);

        return ResponseEntity.ok(status);
    }
}
