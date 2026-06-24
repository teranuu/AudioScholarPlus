package edu.cit.audioscholar.exception;

import java.time.Instant;

public class KeysExhaustedException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	private final Instant retryAt;

	public KeysExhaustedException(String message) {
		this(message, Instant.now());
	}

	public KeysExhaustedException(String message, Instant retryAt) {
		super(message);
		this.retryAt = retryAt;
	}

	public Instant getRetryAt() {
		return retryAt;
	}
}
