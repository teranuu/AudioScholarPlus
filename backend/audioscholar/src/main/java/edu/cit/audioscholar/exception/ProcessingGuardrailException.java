package edu.cit.audioscholar.exception;

public class ProcessingGuardrailException extends NonRetryableTaskException {
	public ProcessingGuardrailException(String message) {
		super(message);
	}

	public ProcessingGuardrailException(String message, Throwable cause) {
		super(message, cause);
	}
}
