package edu.cit.audioscholar.exception;

import java.io.IOException;

public class GeminiQuotaTimeoutException extends IOException {
	private static final long serialVersionUID = 1L;

	public GeminiQuotaTimeoutException(String message) {
		super(message);
	}
}
