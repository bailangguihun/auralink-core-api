package com.auralink.security.jwt;

import com.auralink.config.properties.JwtProperties;
import com.auralink.controller.UserController;
import com.auralink.entity.User;
import com.auralink.repository.GenerationLogRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Actual Core JWT/filter/profile components; no token interpretation in Community. */
class CommunityIdentityBoundaryTest {
   private final JwtProperties settings = new JwtProperties();
   private final JwtTokenProvider tokens;
   CommunityIdentityBoundaryTest() {
      settings.setSecret(Base64.getEncoder().encodeToString(
         "isolated-community-fixture-signing-key-only-2026".getBytes(StandardCharsets.UTF_8)));
      settings.setExpiration(60000);
      tokens = new JwtTokenProvider(settings);
   }
   private User user() {
      return User.builder().id(101L).username("community-fixture")
         .fullName("隔离用户").email("fixture@example.invalid").password("not-used").build();
   }
   private void authenticate(String token, User current) throws Exception {
      var request = new MockHttpServletRequest("GET", "/api/user/profile");
      request.addHeader("Authorization", "Bearer " + token);
      var filter = new JwtAuthenticationFilter(tokens, name -> {
         assertEquals(current.getUsername(), name);
         return current;
      });
      filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
   }
   @AfterEach void clear() { SecurityContextHolder.clearContext(); }
   @Test void existingUsernameTokenResolvesCanonicalProfile() throws Exception {
      var current = user();
      String token = tokens.generateToken(current);
      assertEquals("community-fixture", tokens.extractUsername(token));
      authenticate(token, current);
      var auth = SecurityContextHolder.getContext().getAuthentication();
      assertNotNull(auth);
      var response = new UserController(mock(GenerationLogRepository.class)).getProfile(auth).getBody();
      assertNotNull(response); assertTrue(response.isSuccess());
      assertEquals(101L, response.getData().id());
      assertEquals("community-fixture", response.getData().username());
   }
   @ParameterizedTest
   @ValueSource(strings = {"disabled", "locked", "account-expired", "credentials-expired"})
   void previouslyIssuedTokenCannotReauthorizeRestrictedAccount(String restriction) throws Exception {
      var current = user();
      String token = tokens.generateToken(current);
      switch (restriction) {
         case "disabled" -> current.setEnabled(false);
         case "locked" -> current.setAccountNonLocked(false);
         case "account-expired" -> current.setAccountNonExpired(false);
         case "credentials-expired" -> current.setCredentialsNonExpired(false);
         default -> throw new IllegalArgumentException(restriction);
      }
      assertFalse(tokens.validateToken(token, current));
      authenticate(token, current);
      assertNull(SecurityContextHolder.getContext().getAuthentication());
   }
   @Test void expiredTokenAndChangedUsernameAreRejected() throws Exception {
      var current = user();
      String valid = tokens.generateToken(current);
      current.setUsername("different-user");
      assertFalse(tokens.validateToken(valid, current));
      current.setUsername("community-fixture");
      settings.setExpiration(-1000);
      authenticate(tokens.generateToken(current), current);
      assertNull(SecurityContextHolder.getContext().getAuthentication());
   }
}
