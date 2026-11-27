package com.auralink.security;

import com.auralink.api.v1.error.ApiV1AuthenticationEntryPoint;
import com.auralink.security.jwt.JwtAuthenticationEntryPoint;
import com.auralink.security.jwt.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer.AuthorizedUrl;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
   private final JwtAuthenticationFilter jwtAuthenticationFilter;
   private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
   private final ApiV1AuthenticationEntryPoint apiV1AuthenticationEntryPoint;
   private final CorsConfigurationSource corsConfigurationSource;

   @Bean
   public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      http.csrf(csrf -> csrf.disable())
         .cors(cors -> cors.configurationSource(this.corsConfigurationSource))
         .exceptionHandling(
            exc -> exc.defaultAuthenticationEntryPointFor(this.apiV1AuthenticationEntryPoint, new AntPathRequestMatcher("/api/v1/**"))
               .defaultAuthenticationEntryPointFor(this.jwtAuthenticationEntryPoint, AnyRequestMatcher.INSTANCE)
         )
         .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
         .authorizeHttpRequests(
            // Internal error rendering retains the original business status.
            // Direct REQUEST dispatches, including /error, retain normal auth.
            authz -> ((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)((AuthorizedUrl)authz.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll().requestMatchers(
                                                                                                      new RequestMatcher[]{CorsUtils::isPreFlightRequest}
                                                                                                   ))
                                                                                                   .permitAll()
                                                                                                   .requestMatchers(HttpMethod.OPTIONS, new String[]{"/**"}))
                                                                                                .permitAll()
                                                                                                .requestMatchers(new String[]{"/api/auth/**"}))
                                                                                             .permitAll()
                                                                                             .requestMatchers(new String[]{"/api/health", "/api/health/**"}))
                                                                                          .permitAll()
                                                                                          .requestMatchers(new String[]{"/health", "/health/**"}))
                                                                                       .permitAll()
                                                                                       .requestMatchers(new String[]{"/models"}))
                                                                                    .permitAll()
                                                                                    .requestMatchers(new String[]{"/api/models"}))
                                                                                 .permitAll()
                                                                                 .requestMatchers(new String[]{"/api/audios/**"}))
                                                                              .permitAll()
                                                                              .requestMatchers(new String[]{"/api/files/**"}))
                                                                           .permitAll()
                                                                           .requestMatchers(new String[]{"/api/paintings/**"}))
                                                                        .permitAll()
                                                                        .requestMatchers(new String[]{"/api/upload-session/**"}))
                                                                     .permitAll()
                                                                     .requestMatchers(
                                                                        HttpMethod.GET,
                                                                        new String[]{
                                                                           "/api/v1/assets/{assetId}",
                                                                           "/api/v1/assets/{assetId}/content",
                                                                           "/api/v1/assets/{assetId}/download",
                                                                           "/api/v1/assets/public-catalog-thumbnails/v1/{assetId}/{sourceHash}.jpg",
                                                                           "/api/v1/assets/public-catalog-thumbnails/v2/{assetId}/{sourceHash}.jpg",
                                                                           "/api/v1/assets/public-catalog-thumbnails/v2/480/{assetId}/{sourceHash}.jpg"
                                                                        }
                                                                     ))
                                                                  .permitAll()
                                                                  .requestMatchers(
                                                                     HttpMethod.HEAD,
                                                                     new String[]{
                                                                        "/api/v1/assets/{assetId}",
                                                                        "/api/v1/assets/{assetId}/content",
                                                                        "/api/v1/assets/{assetId}/download",
                                                                        "/api/v1/assets/public-catalog-thumbnails/v1/{assetId}/{sourceHash}.jpg",
                                                                           "/api/v1/assets/public-catalog-thumbnails/v2/{assetId}/{sourceHash}.jpg",
                                                                           "/api/v1/assets/public-catalog-thumbnails/v2/480/{assetId}/{sourceHash}.jpg"
                                                                     }
                                                                  ))
                                                               .permitAll()
                                                               .requestMatchers(
                                                                  new String[]{"/api/v1/workflow/**", "/api/v1/me/workflows", "/api/v1/me/workflows/**"}
                                                               ))
                                                            .authenticated()
                                                            .requestMatchers(HttpMethod.GET, new String[]{"/api/v1/paintings", "/api/v1/paintings/daily"}))
                                                         .permitAll()
                                                         .requestMatchers(new String[]{"/api/upload-image"}))
                                                      .authenticated()
                                                      .requestMatchers(new String[]{"/api/describe-image"}))
                                                   .authenticated()
                                                   .requestMatchers(new String[]{"/api/generate-music"}))
                                                .authenticated()
                                                .requestMatchers(new String[]{"/api/cleanup"}))
                                             .authenticated()
                                             .requestMatchers(new String[]{"/describe_image"}))
                                          .authenticated()
                                          .requestMatchers(new String[]{"/generate"}))
                                       .authenticated()
                                       .requestMatchers(HttpMethod.POST, new String[]{"/api/v1/assets/uploads"}))
                                    .authenticated()
                                    .requestMatchers(
                                       HttpMethod.GET,
                                       new String[]{
                                          "/api/v1/paintings/{paintingId}",
                                          "/api/v1/paintings/{paintingId}/guide",
                                          "/api/v1/paintings/{paintingId}/critic",
                                          "/api/v1/me/favorites/paintings"
                                       }
                                    ))
                                 .authenticated()
                                 .requestMatchers(
                                    HttpMethod.POST,
                                    new String[]{
                                       "/api/v1/paintings/{paintingId}/guide",
                                       "/api/v1/paintings/{paintingId}/guide/audio",
                                       "/api/v1/paintings/{paintingId}/critic"
                                    }
                                 ))
                              .authenticated()
                              .requestMatchers(HttpMethod.GET, new String[]{"/api/v1/critic/capability"}))
                           .authenticated()
                           .requestMatchers(HttpMethod.GET, new String[]{"/api/v1/critic-tasks/{critiqueId}"}))
                        .authenticated()
                        .requestMatchers(HttpMethod.PUT, new String[]{"/api/v1/paintings/{paintingId}/favorite"}))
                     .authenticated()
                     .requestMatchers(HttpMethod.DELETE, new String[]{"/api/v1/paintings/{paintingId}/favorite"}))
                  .authenticated()
                  .anyRequest())
               .authenticated()
         );
      http.addFilterBefore(this.jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
      return (SecurityFilterChain)http.build();
   }

   @Bean
   public PasswordEncoder passwordEncoder() {
      return new BCryptPasswordEncoder();
   }

   @Bean
   public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
      return config.getAuthenticationManager();
   }

   public SecurityConfig(
      final JwtAuthenticationFilter jwtAuthenticationFilter,
      final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
      final ApiV1AuthenticationEntryPoint apiV1AuthenticationEntryPoint,
      final CorsConfigurationSource corsConfigurationSource
   ) {
      this.jwtAuthenticationFilter = jwtAuthenticationFilter;
      this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
      this.apiV1AuthenticationEntryPoint = apiV1AuthenticationEntryPoint;
      this.corsConfigurationSource = corsConfigurationSource;
   }
}
