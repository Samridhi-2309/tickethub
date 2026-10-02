package com.tickethub.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and verifies JSON Web Tokens.
 *
 * A JWT is three base64url parts joined by dots: header (algorithm),
 * payload (claims), signature. Only the signature depends on the secret —
 * the payload is encoded, NOT encrypted, so anyone holding the token can
 * read its claims. Nothing confidential goes in there.
 *
 * HS256 here: one symmetric secret both signs and verifies, which is fine
 * for a single service. RS256 would be the choice if other services had
 * to verify tokens this one issued — they could hold the public key
 * without being able to mint tokens themselves.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expiryMillis;

    public JwtService(@Value("${tickethub.jwt.secret}") String base64Secret,
                      @Value("${tickethub.jwt.expiry-minutes:60}") long expiryMinutes) {
        byte[] decoded = Base64.getDecoder().decode(base64Secret);
        // HS256 requires at least 256 bits of key material; this throws if short.
        this.key = Keys.hmacShaKeyFor(decoded);
        this.expiryMillis = expiryMinutes * 60_000;
    }

    public String generate(Long userId, String email) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiryMillis))
                .signWith(key)
                .compact();
    }

    /**
     * Verify the signature and expiry, and return the claims.
     *
     * Empty means "not authenticated" — a bad signature, an expired token
     * and a malformed string all collapse to the same answer, because the
     * caller should not behave differently for any of them.
     */
    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long getExpiryMillis() {
        return expiryMillis;
    }
}
