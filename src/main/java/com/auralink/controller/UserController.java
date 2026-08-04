package com.auralink.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.auralink.dto.ApiResponse;
import com.auralink.entity.GenerationLog;
import com.auralink.entity.User;
import com.auralink.repository.GenerationLogRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final GenerationLogRepository generationLogRepository;

    @GetMapping("/logs")
    public ResponseEntity<ApiResponse<Page<GenerationLog>>> getLogs(
            Authentication authentication,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "type", required = false) String type) {

        User user = (User) authentication.getPrincipal();
        Pageable pageable = PageRequest.of(page, size);

        Page<GenerationLog> logs;

        if (type != null && !type.isEmpty()) {
            logs = generationLogRepository.findByTaskTypeAndUserOrderByCreatedAtDesc(type, user, pageable);
        } else {
            logs = generationLogRepository.findByUserOrderByCreatedAtDesc(user, pageable);
        }

        return ResponseEntity.ok(ApiResponse.success(logs));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<User>> getProfile(Authentication authentication) {
        User user = (User) authentication.getPrincipal();

        // 移除敏感信息
        user.setPassword(null);

        return ResponseEntity.ok(ApiResponse.success(user));
    }
}
