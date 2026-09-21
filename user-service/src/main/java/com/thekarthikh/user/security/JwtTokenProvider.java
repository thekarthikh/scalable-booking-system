package com.thekarthikh.user.security;

import io.jsonwebtoken.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {

    private final PrivateKey privateKey;
    private final PublicKey  publicKey;
    private final long       expirationMs;

    public JwtTokenProvider(
            @Value("${jwt.private-key:#{null}}") String privateKeyStr,
            @Value("${jwt.public-key:#{null}}") String publicKeyStr,
            @Value("${jwt.expiration-ms:86400000}") long expirationMs,
            @Value("${jwt.allow-generated-keys:false}") boolean allowGeneratedKeys) throws Exception {
        
        // In a real prod environment, these come from ENV or Secrets Manager
        // For local development, we generate a key pair if none are provided
        if (privateKeyStr == null && publicKeyStr == null && allowGeneratedKeys) {
            log.warn("JWT RSA keys not provided in config. Generating temporary transient keys...");
            KeyPair kp = Jwts.SIG.RS256.keyPair().build();
            this.privateKey = kp.getPrivate();
            this.publicKey  = kp.getPublic();
        } else if (privateKeyStr == null || publicKeyStr == null) {
            throw new IllegalStateException("Both JWT_PRIVATE_KEY and JWT_PUBLIC_KEY must be configured");
        } else {
            this.privateKey = loadPrivateKey(privateKeyStr);
            this.publicKey  = loadPublicKey(publicKeyStr);
        }
        this.expirationMs = expirationMs;
    }

    public String generateToken(String username, String role, String userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .claim("userId", userId)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Claims claims = parseClaims(token);
            String subject = claims.getSubject();
            String role = claims.get("role", String.class);
            String userId = claims.get("userId", String.class);
            return subject != null && !subject.isBlank()
                    && role != null && Set.of("USER", "ADMIN").contains(role)
                    && userId != null && isUuid(userId);
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Invalid JWT token type={}", ex.getClass().getSimpleName());
            return false;
        }
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private PrivateKey loadPrivateKey(String key) throws Exception {
        byte[] encoded = Base64.getDecoder().decode(key);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private PublicKey loadPublicKey(String key) throws Exception {
        byte[] encoded = Base64.getDecoder().decode(key);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    }

    public long getExpirationMs() {
        return expirationMs;
    }
}
