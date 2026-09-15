package edu.cit.audioscholar.service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import edu.cit.audioscholar.config.RabbitMQConfig;

@Service
public class ConfirmedRabbitPublisher {
	private final RabbitTemplate rabbitTemplate;
	private final Duration confirmTimeout;

	@Autowired
	public ConfirmedRabbitPublisher(RabbitTemplate rabbitTemplate,
			@Value("${app.rabbitmq.publisher-confirm-timeout-ms:10000}") long confirmTimeoutMs) {
		this(rabbitTemplate, Duration.ofMillis(confirmTimeoutMs));
	}

	ConfirmedRabbitPublisher(RabbitTemplate rabbitTemplate, Duration confirmTimeout) {
		this.rabbitTemplate = rabbitTemplate;
		this.confirmTimeout = confirmTimeout;
	}

	public void publishToProcessingExchange(String routingKey, Object message) {
		CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
		rabbitTemplate.convertAndSend(RabbitMQConfig.PROCESSING_EXCHANGE_NAME, routingKey, message, correlation);

		CorrelationData.Confirm confirm;
		try {
			confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new RabbitPublishTimeoutException(
					"RabbitMQ publisher confirm timed out for routing key " + routingKey, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RabbitPublishRejectedException(
					"Interrupted while waiting for RabbitMQ publisher confirm for routing key " + routingKey, e);
		} catch (Exception e) {
			throw new RabbitPublishRejectedException(
					"Could not obtain RabbitMQ publisher confirm for routing key " + routingKey, e);
		}

		ReturnedMessage returned = correlation.getReturned();
		if (returned != null) {
			throw new RabbitPublishRejectedException("RabbitMQ returned unroutable message for routing key "
					+ routingKey + ": replyCode=" + returned.getReplyCode() + ", replyText=" + returned.getReplyText());
		}

		if (!confirm.isAck()) {
			throw new RabbitPublishRejectedException("RabbitMQ rejected message for routing key " + routingKey + ": "
					+ (confirm.getReason() != null ? confirm.getReason() : "no reason provided"));
		}
	}
}
