package com.auralink.security.jwt;

import com.auralink.config.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {
   private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);
   private final JwtProperties jwtConfig;

   public String generateToken(UserDetails userDetails) {
      return this.generateToken(new HashMap<>(), userDetails);
   }

   public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
      return Jwts.builder()
         .setClaims(extraClaims)
         .setSubject(userDetails.getUsername())
         .setIssuedAt(new Date(System.currentTimeMillis()))
         .setExpiration(new Date(System.currentTimeMillis() + this.jwtConfig.getExpiration()))
         .signWith(this.getSigningKey(), SignatureAlgorithm.HS256)
         .compact();
   }

   public boolean validateToken(String token, UserDetails userDetails) {
      String username = this.extractUsername(token);
      return username.equals(userDetails.getUsername()) && !this.isTokenExpired(token)
         && userDetails.isEnabled() && userDetails.isAccountNonLocked()
         && userDetails.isAccountNonExpired() && userDetails.isCredentialsNonExpired();
   }

   public boolean isTokenValid(String token) {
      try {
         Jwts.parserBuilder().setSigningKey(this.getSigningKey()).build().parseClaimsJws(token);
         return true;
      } catch (SignatureException | MalformedJwtException | ExpiredJwtException | UnsupportedJwtException | IllegalArgumentException e) {
         log.error("JWT验证错误", e);
         return false;
      }
   }

   public String extractUsername(String token) {
      return this.extractClaim(token, Claims::getSubject);
   }

   public Date extractExpiration(String token) {
      return this.extractClaim(token, Claims::getExpiration);
   }

   public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
      Claims claims = this.extractAllClaims(token);
      return claimsResolver.apply(claims);
   }

   private Claims extractAllClaims(String token) {
      return (Claims)Jwts.parserBuilder().setSigningKey(this.getSigningKey()).build().parseClaimsJws(token).getBody();
   }

   private boolean isTokenExpired(String token) {
      return this.extractExpiration(token).before(new Date());
   }

   private Key getSigningKey() {
      byte[] keyBytes = (byte[])Decoders.BASE64.decode(this.jwtConfig.getSecret());
      return Keys.hmacShaKeyFor(keyBytes);
   }

   public JwtTokenProvider(final JwtProperties jwtConfig) {
      this.jwtConfig = jwtConfig;
   }
}
