package edu.cit.audioscholar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ConfirmedRabbitPublisherWiringTest {

	@Test
	void springCreatesPublisherWithConfiguredConfirmTimeout() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getBeanFactory().registerSingleton("rabbitTemplate", mock(RabbitTemplate.class));
			TestPropertyValues.of("app.rabbitmq.publisher-confirm-timeout-ms=250").applyTo(context);
			context.register(ConfirmedRabbitPublisher.class);

			assertDoesNotThrow(context::refresh);
			assertThat(context.getBean(ConfirmedRabbitPublisher.class)).isNotNull();
		}
	}
}
