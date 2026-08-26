package com.auralink.dto.response;

import com.auralink.entity.User;

/**
 * Minimal user identity retained in legacy generation-log responses.
 */
public record UserSummaryResponse(
        Long id,
        String username,
        String fullName) {

    public static UserSummaryResponse from(User user) {
        if (user == null) {
            return null;
        }

        return new UserSummaryResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName());
    }
}
