package com.sevenkingdoms.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    @Value("${app.jwt.secret:sevenkingdoms-secret-key-minimum-32-chars-long}")
    private String secret;

    @Value("${app.jwt.expiration-ms:604800000}") // 7 giorni
    private long expirationMs;

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Genera un JWT per l'utente */
    public String generateToken(AppUser user) {
        return Jwts.builder()
                .subject(user.getId())
                .claim("nickname", user.getNickname())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getKey())
                .compact();
    }

    /** Estrae l'id utente dal token, null se invalido */
    public String extractUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    /** Verifica che il token sia valido */
    public boolean isValid(String token) {
        return extractUserId(token) != null;
    }
}