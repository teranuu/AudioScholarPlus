package edu.cit.audioscholar.exception;

import java.time.Instant;

public class DeferredProcessingException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final String processingStage;
	private final Instant retryAt;

	public DeferredProcessingException(String processingStage, Instant retryAt, String message) {
		super(message);
		this.processingStage = processingStage;
		this.retryAt = retryAt;
	}

	public String getProcessingStage() {
		return processingStage;
	}

	public Instant getRetryAt() {
		return retryAt;
	}
}
