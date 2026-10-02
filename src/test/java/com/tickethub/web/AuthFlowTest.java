package com.tickethub.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.support.IntegrationTestBase;
import com.tickethub.web.dto.AuthResponse;
import com.tickethub.web.dto.LoginRequest;
import com.tickethub.web.dto.RegisterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class AuthFlowTest extends IntegrationTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    @Test
    @DisplayName("register then use the token on a protected endpoint")
    void registerAndAccessProtectedEndpoint() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@test.dev";

        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(email, "password123", "Test User"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        AuthResponse auth = objectMapper.readValue(body, AuthResponse.class);

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + auth.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    @DisplayName("the password hash is never returned to the client")
    void passwordIsNeverReturned() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@test.dev";

        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(email, "password123", "Test User"))))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("password123");
        assertThat(body).doesNotContain("$2");   // no BCrypt hash prefix
    }

    @Test
    @DisplayName("protected endpoints reject a missing token")
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/bookings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("protected endpoints reject a tampered token")
    void rejectsTamperedToken() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer not.a.real.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("wrong password gives the same error as unknown email")
    void wrongPasswordIsIndistinguishable() throws Exception {
        String unknown = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest("nobody@test.dev", "password123"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest("demo@tickethub.dev", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Same code and message either way: telling them apart would
        // reveal which emails are registered.
        assertThat(unknown).contains("INVALID_CREDENTIALS");
        assertThat(wrongPassword).contains("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("the seeded demo account can log in")
    void seededUserCanLogIn() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest("demo@tickethub.dev", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value("demo@tickethub.dev"));
    }

    @Test
    @DisplayName("browsing events needs no token")
    void eventsArePublic() throws Exception {
        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk());
    }
}
