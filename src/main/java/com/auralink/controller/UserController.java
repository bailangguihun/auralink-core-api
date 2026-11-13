package com.auralink.controller;

import com.auralink.dto.ApiResponse;
import com.auralink.dto.response.GenerationLogResponse;
import com.auralink.dto.response.UserProfileResponse;
import java.util.List;
import com.auralink.entity.GenerationLog;
import com.auralink.entity.User;
import com.auralink.repository.GenerationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
public class UserController {
   private static final Logger log = LoggerFactory.getLogger(UserController.class);
   private final GenerationLogRepository generationLogRepository;

   @GetMapping("/logs")
   public ResponseEntity<ApiResponse<Page<GenerationLogResponse>>> getLogs(
      Authentication authentication,
      @RequestParam(value = "page", defaultValue = "0") int page,
      @RequestParam(value = "size", defaultValue = "10") int size,
      @RequestParam(value = "type", required = false) String type
   ) {
      User user = (User)authentication.getPrincipal();
      Pageable pageable = PageRequest.of(page, size);
      Page<GenerationLog> logs;
      if (type != null && !type.isEmpty()) {
         logs = this.generationLogRepository.findByTaskTypeAndUserOrderByCreatedAtDesc(type, user, pageable);
      } else {
         logs = this.generationLogRepository.findByUserOrderByCreatedAtDesc(user, pageable);
      }

      Page<GenerationLogResponse> response = logs.map(GenerationLogResponse::from);
      return ResponseEntity.ok(ApiResponse.success(response));
   }

   @GetMapping("/profile")
   public ResponseEntity<ApiResponse<UserProfileResponse>> getProfile(Authentication authentication) {
      User user = (User)authentication.getPrincipal();
      return ResponseEntity.ok(ApiResponse.success(UserProfileResponse.from(user)));
   }

   public record CommunityIdentity(Long id, String username, String fullName, String email, List<String> authorities) {}

   @GetMapping("/community-identity")
   public ResponseEntity<ApiResponse<CommunityIdentity>> communityIdentity(Authentication authentication) {
      User user = (User) authentication.getPrincipal();
      return ResponseEntity.ok(ApiResponse.success(new CommunityIdentity(user.getId(), user.getUsername(),
         user.getFullName(), user.getEmail(), authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList())));
   }

   public UserController(final GenerationLogRepository generationLogRepository) {
      this.generationLogRepository = generationLogRepository;
   }
}
