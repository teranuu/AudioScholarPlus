package edu.cit.audioscholar.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

class RabbitMQConfigTest {

	@Test
	void shortAndQuotaMultiSourceRetriesUseSeparateDeadLetterQueues() {
		RabbitMQConfig config = new RabbitMQConfig();

		Queue shortRetry = config.multiSourceRetryQueue();
		Queue quotaRetry = config.multiSourceQuotaRetryQueue();

		assertThat(shortRetry.getName()).isEqualTo(RabbitMQConfig.MULTI_SOURCE_RETRY_QUEUE_NAME);
		assertThat(quotaRetry.getName()).isEqualTo(RabbitMQConfig.MULTI_SOURCE_QUOTA_RETRY_QUEUE_NAME);
		assertThat(shortRetry.getName()).isNotEqualTo(quotaRetry.getName());
		assertThat(shortRetry.getArguments())
				.containsEntry("x-dead-letter-exchange", RabbitMQConfig.PROCESSING_EXCHANGE_NAME)
				.containsEntry("x-dead-letter-routing-key", RabbitMQConfig.MULTI_SOURCE_ROUTING_KEY)
				.containsEntry("x-message-ttl", RabbitMQConfig.MULTI_SOURCE_RETRY_TTL_MS);
		assertThat(quotaRetry.getArguments())
				.containsEntry("x-dead-letter-exchange", RabbitMQConfig.PROCESSING_EXCHANGE_NAME)
				.containsEntry("x-dead-letter-routing-key", RabbitMQConfig.MULTI_SOURCE_ROUTING_KEY)
				.containsEntry("x-message-ttl", RabbitMQConfig.MULTI_SOURCE_QUOTA_RETRY_TTL_MS);
	}
}
