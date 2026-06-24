package edu.cit.audioscholar.actuator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.google.cloud.firestore.Firestore;

/**
 * Smoke test verifying that /actuator/health returns UP when RabbitMQ is
 * disabled. This test loads the full Spring context with the "test" profile
 * (which sets app.rabbitmq.enabled=false) and directly queries the
 * HealthEndpoint bean rather than making an HTTP call, avoiding the need
 * for a full web server.
 */
@SpringBootTest
@ActiveProfiles("test")
class HealthCheckWithoutRabbitTest {

    @MockitoBean
    private Firestore firestore;

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Test
    void actuatorHealthReturnsUpWhenRabbitDisabled() {
        assertNotNull(healthEndpoint, "HealthEndpoint should be autowired");

        var health = healthEndpoint.health();

        assertNotNull(health, "Health result should not be null");
        assertEquals(Status.UP, health.getStatus(),
                "Health status should be UP when RabbitMQ is disabled");
    }
}
