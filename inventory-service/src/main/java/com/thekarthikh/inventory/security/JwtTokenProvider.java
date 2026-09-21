package com.thekarthikh.inventory.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

@Component
public class JwtTokenProvider {
    private final PublicKey publicKey;

    public JwtTokenProvider(@Value("${jwt.public-key:#{null}}") String publicKeyStr) throws Exception {
        if (publicKeyStr == null) {
            throw new IllegalStateException("JWT_PUBLIC_KEY must be configured");
        }
        byte[] encoded = Base64.getDecoder().decode(publicKeyStr);
        this.publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();
    }

    public boolean valid(String token) {
        try {
            Claims claims = parse(token);
            String subject = claims.getSubject();
            String userId = claims.get("userId", String.class);
            String role = claims.get("role", String.class);
            return subject != null && !subject.isBlank()
                    && userId != null && isUuid(userId)
                    && role != null && Set.of("USER", "ADMIN").contains(role);
        } catch (JwtException | IllegalArgumentException ex) {
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
}
