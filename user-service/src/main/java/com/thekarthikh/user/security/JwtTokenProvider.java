package com.thekarthikh.user.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
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

@Slf4j
@Component
public class JwtTokenProvider {

    private final PrivateKey privateKey;
    private final PublicKey  publicKey;
    private final long       expirationMs;

    public JwtTokenProvider(
            @Value("${jwt.private-key:#{null}}") String privateKeyStr,
            @Value("${jwt.public-key:#{null}}") String publicKeyStr,
            @Value("${jwt.expiration-ms:86400000}") long expirationMs) throws Exception {
        
        // In a real prod environment, these come from ENV or Secrets Manager
        // For local development, we generate a key pair if none are provided
        if (privateKeyStr == null || publicKeyStr == null) {
            log.warn("JWT RSA keys not provided in config. Generating temporary transient keys...");
            KeyPair kp = Jwts.SIG.RS256.keyPair().build();
            this.privateKey = kp.getPrivate();
            this.publicKey  = kp.getPublic();
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
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Invalid JWT token: {}", ex.getMessage());
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
}

    public long getExpirationMs() {
        return expirationMs;
    }
}
