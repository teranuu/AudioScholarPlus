package edu.cit.audioscholar.service;

public class RabbitPublishTimeoutException extends RuntimeException {
	public RabbitPublishTimeoutException(String message, Throwable cause) {
		super(message, cause);
	}
}
