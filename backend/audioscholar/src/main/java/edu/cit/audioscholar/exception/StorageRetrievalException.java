package edu.cit.audioscholar.exception;

public class StorageRetrievalException extends RuntimeException {
	private final String failureCode;
	private final int statusCode;
	private final boolean retryable;

	public StorageRetrievalException(String message, String failureCode, int statusCode, boolean retryable,
			Throwable cause) {
		super(message, cause);
		this.failureCode = failureCode;
		this.statusCode = statusCode;
		this.retryable = retryable;
	}

	public String getFailureCode() {
		return failureCode;
	}

	public int getStatusCode() {
		return statusCode;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
