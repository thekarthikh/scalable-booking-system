package com.thekarthikh.inventory.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    @Test
    void signedTokenWithMalformedClaimsIsRejected() throws Exception {
        var keyPair = Jwts.SIG.RS256.keyPair().build();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        JwtTokenProvider provider = new JwtTokenProvider(publicKey);

        String malformed = Jwts.builder()
                .subject("alice")
                .claim("userId", UUID.randomUUID().toString())
                .claim("role", 42)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertThat(provider.valid(malformed)).isFalse();
    }
}
