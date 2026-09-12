package edu.cit.audioscholar.service;

public class RabbitPublishRejectedException extends RuntimeException {
	public RabbitPublishRejectedException(String message) {
		super(message);
	}

	public RabbitPublishRejectedException(String message, Throwable cause) {
		super(message, cause);
	}
}
