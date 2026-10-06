package edu.cit.audioscholar.service;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import edu.cit.audioscholar.config.RabbitMQConfig;
import edu.cit.audioscholar.dto.MultiSourceJobMessage;

@Service
public class MultiSourceJobListener {
	private final MultiSourceJobService jobs;
	public MultiSourceJobListener(MultiSourceJobService jobs) {
		this.jobs = jobs;
	}
	@RabbitListener(queues = RabbitMQConfig.MULTI_SOURCE_QUEUE_NAME, containerFactory = "defaultContainerFactory")
	public void process(MultiSourceJobMessage message) throws Exception {
		jobs.processQueuedJob(message.jobId());
	}
}
