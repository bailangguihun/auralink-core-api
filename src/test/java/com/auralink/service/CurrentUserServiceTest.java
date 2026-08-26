package com.auralink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.entity.User;
import com.auralink.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CurrentUserService currentUserService;

    @Test
    void resolvesPersistedUserFromAuthenticationNameInsteadOfPrincipalType() {
        User persisted = User.builder().id(7L).username("owner").build();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "an-arbitrary-principal-object", null, List.of());
        authentication.setDetails("not-an-owner-id");
        // Authentication#getName is derived from the principal here.
        when(userRepository.findByUsername("an-arbitrary-principal-object"))
                .thenReturn(Optional.of(persisted));

        assertThat(currentUserService.requireCurrentUser(authentication)).isSameAs(persisted);
    }

    @Test
    void anonymousAuthenticationDoesNotResolveAUser() {
        var anonymous = new AnonymousAuthenticationToken(
                "round4-test", "anonymousUser", List.of(() -> "ROLE_ANONYMOUS"));

        assertThat(currentUserService.findCurrentUser(anonymous)).isEmpty();
    }

    @Test
    void staleAuthenticatedIdentityIsRejectedWithoutTrustingPrincipalFields() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "missing-user", null, List.of());
        when(userRepository.findByUsername("missing-user")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> currentUserService.requireCurrentUser(authentication))
                .isInstanceOfSatisfying(ApiV1Exception.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ApiErrorCode.UNAUTHORIZED);
                    assertThat(exception.getStatus().value()).isEqualTo(401);
                });
    }
}
