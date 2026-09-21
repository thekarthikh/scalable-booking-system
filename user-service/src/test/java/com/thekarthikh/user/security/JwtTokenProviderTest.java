package com.thekarthikh.user.security;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Jwts;

import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    @Test
    void generatedDevelopmentKeyCanIssueAndValidateToken() throws Exception {
        JwtTokenProvider provider = new JwtTokenProvider(null, null, 60_000, true);

        String token = provider.generateToken("alice", "USER", "3f0f3db1-7d6e-4e4d-9d4b-5aa0e8af8c33");

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUsernameFromToken(token)).isEqualTo("alice");
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        JwtTokenProvider provider = new JwtTokenProvider(null, null, 60_000, true);
        String token = provider.generateToken("alice", "USER", "3f0f3db1-7d6e-4e4d-9d4b-5aa0e8af8c33");

        assertThat(provider.validateToken(token + "tampered")).isFalse();
    }

    @Test
    void signedTokenMissingRequiredIdentityClaimsIsRejected() throws Exception {
        var keyPair = Jwts.SIG.RS256.keyPair().build();
        String privateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        JwtTokenProvider provider = new JwtTokenProvider(privateKey, publicKey, 60_000, false);

        String malformed = Jwts.builder()
                .subject("alice")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertThat(provider.validateToken(malformed)).isFalse();
    }

    @Test
    void missingProductionKeysFailFast() {
        assertThatThrownBy(() -> new JwtTokenProvider(null, null, 60_000, false))
                .isInstanceOf(IllegalStateException.class);
    }
}
