package com.tickethub.web;

import com.tickethub.security.AppUserDetails;
import com.tickethub.service.AuthService;
import com.tickethub.web.dto.AuthResponse;
import com.tickethub.web.dto.LoginRequest;
import com.tickethub.web.dto.RegisterRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** Lets the front-end check whether a stored token is still good. */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AppUserDetails principal) {
        return Map.of(
                "userId", principal.getUserId(),
                "email", principal.getEmail());
    }
}
