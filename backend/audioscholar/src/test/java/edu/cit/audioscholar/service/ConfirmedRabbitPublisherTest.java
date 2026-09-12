package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import edu.cit.audioscholar.config.RabbitMQConfig;

class ConfirmedRabbitPublisherTest {

	@Test
	void publishToProcessingExchange_ackSucceeds() {
		RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
		completeConfirm(rabbitTemplate, new CorrelationData.Confirm(true, null), false);
		ConfirmedRabbitPublisher publisher = new ConfirmedRabbitPublisher(rabbitTemplate, Duration.ofMillis(100));

		assertDoesNotThrow(
				() -> publisher.publishToProcessingExchange(RabbitMQConfig.TRANSCRIPTION_ROUTING_KEY, new Object()));
	}

	@Test
	void publishToProcessingExchange_nackThrowsRejected() {
		RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
		completeConfirm(rabbitTemplate, new CorrelationData.Confirm(false, "exchange unavailable"), false);
		ConfirmedRabbitPublisher publisher = new ConfirmedRabbitPublisher(rabbitTemplate, Duration.ofMillis(100));

		assertThrows(RabbitPublishRejectedException.class,
				() -> publisher.publishToProcessingExchange(RabbitMQConfig.TRANSCRIPTION_ROUTING_KEY, new Object()));
	}

	@Test
	void publishToProcessingExchange_returnedMessageThrowsRejected() {
		RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
		completeConfirm(rabbitTemplate, new CorrelationData.Confirm(true, null), true);
		ConfirmedRabbitPublisher publisher = new ConfirmedRabbitPublisher(rabbitTemplate, Duration.ofMillis(100));

		assertThrows(RabbitPublishRejectedException.class,
				() -> publisher.publishToProcessingExchange(RabbitMQConfig.TRANSCRIPTION_ROUTING_KEY, new Object()));
	}

	@Test
	void publishToProcessingExchange_confirmTimeoutThrowsUncertainTimeout() {
		RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
		ConfirmedRabbitPublisher publisher = new ConfirmedRabbitPublisher(rabbitTemplate, Duration.ofMillis(1));

		assertThrows(RabbitPublishTimeoutException.class,
				() -> publisher.publishToProcessingExchange(RabbitMQConfig.TRANSCRIPTION_ROUTING_KEY, new Object()));
	}

	private void completeConfirm(RabbitTemplate rabbitTemplate, CorrelationData.Confirm confirm, boolean returned) {
		doAnswer(invocation -> {
			CorrelationData correlation = invocation.getArgument(3);
			if (returned) {
				Message message = new Message(new byte[0], new MessageProperties());
				correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE",
						RabbitMQConfig.PROCESSING_EXCHANGE_NAME, RabbitMQConfig.TRANSCRIPTION_ROUTING_KEY));
			}
			correlation.getFuture().complete(confirm);
			return null;
		}).when(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.PROCESSING_EXCHANGE_NAME), any(String.class),
				any(Object.class), any(CorrelationData.class));
	}
}
