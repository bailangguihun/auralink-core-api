package com.auralink.service;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.entity.User;
import com.auralink.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Resolves API ownership from Spring Security and the authoritative legacy
 * {@code users} row. Request payloads are never an ownership authority.
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    /** Returns the currently authenticated persisted user, when one exists. */
    public Optional<User> findCurrentUser() {
        return findCurrentUser(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * Variant used by tests and callers that already have the request's
     * authentication object.
     */
    public Optional<User> findCurrentUser(Authentication authentication) {
        if (!isAuthenticatedUser(authentication)) {
            return Optional.empty();
        }

        String username = authentication.getName();
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByUsername(username);
    }

    /** Requires a persisted current user for an ownership-changing operation. */
    public User requireCurrentUser() {
        return findCurrentUser().orElseThrow(CurrentUserService::unauthorized);
    }

    public User requireCurrentUser(Authentication authentication) {
        return findCurrentUser(authentication).orElseThrow(CurrentUserService::unauthorized);
    }

    private boolean isAuthenticatedUser(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static ApiV1Exception unauthorized() {
        return new ApiV1Exception(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.UNAUTHORIZED,
                "需要身份验证");
    }
}
