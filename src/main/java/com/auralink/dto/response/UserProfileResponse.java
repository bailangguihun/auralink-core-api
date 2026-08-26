package com.auralink.dto.response;

import java.time.LocalDateTime;

import com.auralink.entity.User;

/**
 * Sanitized representation of the currently authenticated user.
 *
 * <p>This DTO deliberately excludes password hashes and the internal
 * {@code UserDetails} account-state fields.</p>
 */
public record UserProfileResponse(
        Long id,
        String username,
        String fullName,
        String email,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getEmail(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
