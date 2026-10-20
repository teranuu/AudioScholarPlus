package edu.cit.audioscholar.exception;

public class MultiSourceProcessingException extends RuntimeException {
	private final String failureCode;
	private final boolean retryable;

	public MultiSourceProcessingException(String message, String failureCode, boolean retryable) {
		super(message);
		this.failureCode = failureCode;
		this.retryable = retryable;
	}

	public String getFailureCode() {
		return failureCode;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
