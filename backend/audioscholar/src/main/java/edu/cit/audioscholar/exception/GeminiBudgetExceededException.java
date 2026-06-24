package edu.cit.audioscholar.exception;

import java.time.Instant;

public class GeminiBudgetExceededException extends RuntimeException {
	private final Instant retryAt;

	public GeminiBudgetExceededException(String message, Instant retryAt) {
		super(message);
		this.retryAt = retryAt;
	}

	public Instant getRetryAt() {
		return retryAt;
	}
}
