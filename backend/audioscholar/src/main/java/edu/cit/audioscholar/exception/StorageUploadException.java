package edu.cit.audioscholar.exception;

public class StorageUploadException extends RuntimeException {
	private final int statusCode;
	private final boolean retryable;

	public StorageUploadException(String message, int statusCode, boolean retryable, Throwable cause) {
		super(message, cause);
		this.statusCode = statusCode;
		this.retryable = retryable;
	}

	public int getStatusCode() {
		return statusCode;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
