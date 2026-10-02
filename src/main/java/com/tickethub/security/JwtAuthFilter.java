package com.tickethub.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads "Authorization: Bearer <token>", verifies it, and puts the user
 * into the SecurityContext for the rest of the request.
 *
 * Extends OncePerRequestFilter because a request can pass through the
 * servlet container more than once (forwards, error dispatches) and the
 * work should not be repeated.
 *
 * On a bad or missing token this does NOT reject the request — it simply
 * leaves the context empty and lets the filter chain's authorization
 * rules decide. That keeps public endpoints public.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            String token = header.substring(7);

            jwtService.parse(token).ifPresent(claims -> {
                String email = claims.get("email", String.class);
                if (email != null) {
                    try {
                        UserDetails user = userDetailsService.loadUserByUsername(email);
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(
                                        user, null, user.getAuthorities());
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    } catch (UsernameNotFoundException e) {
                        // Token is validly signed but the account is gone.
                        // Leave the context empty; authorization rules decide.
                    }
                }
            });
        }

        filterChain.doFilter(request, response);
    }
}
