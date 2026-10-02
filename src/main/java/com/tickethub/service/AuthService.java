package com.tickethub.service;

import com.tickethub.domain.User;
import com.tickethub.repository.UserRepository;
import com.tickethub.security.JwtService;
import com.tickethub.web.dto.AuthResponse;
import com.tickethub.web.dto.LoginRequest;
import com.tickethub.web.dto.RegisterRequest;
import com.tickethub.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();

        if (userRepository.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN",
                    "An account already exists for " + email);
        }

        User user = userRepository.save(User.builder()
                .email(email)
                // The raw password is never stored, logged, or returned.
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName().trim())
                .build());

        return toAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();

        // One message for both "no such user" and "wrong password".
        // Distinguishing them tells an attacker which emails are registered.
        User user = userRepository.findByEmail(email)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "INVALID_CREDENTIALS", "Email or password is incorrect"));

        return toAuthResponse(user);
    }

    private AuthResponse toAuthResponse(User user) {
        return new AuthResponse(
                jwtService.generate(user.getId(), user.getEmail()),
                "Bearer",
                jwtService.getExpiryMillis(),
                user.getId(),
                user.getEmail(),
                user.getDisplayName());
    }
}
