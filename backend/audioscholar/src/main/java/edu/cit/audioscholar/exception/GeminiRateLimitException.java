package edu.cit.audioscholar.exception;

import java.io.IOException;
import java.time.Instant;

public class GeminiRateLimitException extends IOException {
	private static final long serialVersionUID = 1L;

	private final Instant retryAt;

	public GeminiRateLimitException(String message, Instant retryAt, Throwable cause) {
		super(message, cause);
		this.retryAt = retryAt;
	}

	public Instant getRetryAt() {
		return retryAt;
	}
}
