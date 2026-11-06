package com.andresps.flights.support;

import java.io.IOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * One Redis container for the whole test JVM, with the image docker-compose uses ({@value #IMAGE}); the search cache's
 * counterpart of {@link TestPostgres}. Spring ITs {@code @Import(TestRedis.class)} and share it as their
 * {@code @ServiceConnection}; each test empties it ({@link #flushAll()}).
 *
 * <p>{@link #pause()} freezes the container (commands time out, like a hung or blackholed Redis) and
 * {@link #unpause()} resumes it on the same port, so a test can observe the fallback and the recovery (spec 005 AC-21).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestRedis {

    public static final String IMAGE = "redis:8";

    private static final GenericContainer<?> CONTAINER = startContainer();

    private static GenericContainer<?> startContainer() {
        GenericContainer<?> container = new SharedContainer();
        container.start();
        return container;
    }

    /** Never stopped by a closing Spring context; see {@link TestPostgres}. */
    private static final class SharedContainer extends GenericContainer<SharedContainer> {

        SharedContainer() {
            super(IMAGE);
            withExposedPorts(6379);
        }

        @Override
        public void stop() {
            // shared for the whole JVM
        }
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return CONTAINER;
    }

    public static void flushAll() {
        exec("FLUSHALL");
    }

    /** Number of keys currently stored. */
    public static long keys() {
        return Long.parseLong(exec("DBSIZE").trim());
    }

    public static void pause() {
        DockerClientFactory.instance()
                .client()
                .pauseContainerCmd(CONTAINER.getContainerId())
                .exec();
    }

    /** Resumes a paused container; does nothing if it is running. */
    public static void unpause() {
        Boolean paused = DockerClientFactory.instance()
                .client()
                .inspectContainerCmd(CONTAINER.getContainerId())
                .exec()
                .getState()
                .getPaused();
        if (!Boolean.TRUE.equals(paused)) {
            return;
        }
        DockerClientFactory.instance()
                .client()
                .unpauseContainerCmd(CONTAINER.getContainerId())
                .exec();
    }

    private static String exec(String command) {
        try {
            return CONTAINER.execInContainer("redis-cli", command).getStdout();
        } catch (IOException e) {
            throw new IllegalStateException("redis-cli " + command + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running redis-cli " + command, e);
        }
    }
}
