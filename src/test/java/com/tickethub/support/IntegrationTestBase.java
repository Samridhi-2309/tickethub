package com.tickethub.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that need the real thing.
 *
 * Postgres and Redis run in containers rather than H2 and an embedded
 * fake, because the behaviour under test IS the database's: SELECT ...
 * FOR UPDATE semantics, a partial unique index, and Redis's single-
 * threaded script execution. H2 implements none of those the same way,
 * so a concurrency test against H2 would prove nothing about production.
 *
 * The containers are static, so one pair is started for the whole suite
 * and reused across test classes rather than restarted per class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    static {
        // Docker Engine 29 raised the minimum supported Docker API version
        // from 1.24 to 1.44. The docker-java client bundled with
        // Testcontainers 1.20.x still negotiates 1.32, so the daemon
        // rejects it with HTTP 400 and Testcontainers reports "could not
        // find a valid Docker environment" even though Docker is running.
        // Pinning the version here fixes it without a dependency upgrade.
        // Set before any container is constructed, or it is ignored.
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.44");
        }
    }

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }
}