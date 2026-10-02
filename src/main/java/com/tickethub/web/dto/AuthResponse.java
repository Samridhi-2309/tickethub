package com.tickethub.web.dto;

public record AuthResponse(
        String token,
        String tokenType,
        long expiresInMs,
        Long userId,
        String email,
        String displayName
) {}
