package edu.cit.audioscholar.exception;

public class NonRetryableTaskException extends RuntimeException {
	public NonRetryableTaskException(String message, Throwable cause) {
		super(message, cause);
	}
}
