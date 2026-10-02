package com.tickethub.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    /**
     * Declaring the bearer scheme is what puts an "Authorize" button in
     * Swagger UI. Without it every /api/bookings call from the docs page
     * returns 401 and the page is useless for trying the API.
     */
    @Bean
    public OpenAPI tickethubOpenApi() {
        final String scheme = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("TicketHub API")
                        .version("1.0")
                        .description("""
                                Event ticket booking backend.

                                Seats are claimed with a short-lived hold, then confirmed into a
                                booking. Double booking is prevented by an atomic Redis claim, a
                                pessimistic row lock at confirm time, and a partial unique index
                                in Postgres as the final backstop.

                                Log in at /api/auth/login (demo@tickethub.dev / password123),
                                then paste the token into Authorize.
                                """))
                .addSecurityItem(new SecurityRequirement().addList(scheme))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
