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
