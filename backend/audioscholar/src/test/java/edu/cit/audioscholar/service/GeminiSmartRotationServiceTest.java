package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

class GeminiSmartRotationServiceTest {

	@Test
	void rotatesWhenModelIsUnavailable() {
		GeminiSmartRotationService service = new GeminiSmartRotationService("missing-model, gemini-2.5-flash", 0, 0,
				2.0);
		AtomicInteger attempts = new AtomicInteger();

		String result = service.executeWithRotation(model -> {
			if (attempts.incrementAndGet() == 1) {
				throw unavailableModel();
			}
			return model;
		}, 1);

		assertEquals("gemini-2.5-flash", result);
		assertEquals(2, attempts.get());
	}

	@Test
	void stillRotatesForRateLimitAndServiceUnavailable() {
		GeminiSmartRotationService service = new GeminiSmartRotationService(
				"rate-limited, overloaded, gemini-2.5-flash", 0, 0, 2.0);
		AtomicInteger attempts = new AtomicInteger();

		String result = service.executeWithRotation(model -> {
			int attempt = attempts.incrementAndGet();
			if (attempt == 1) {
				throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
						HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
			}
			if (attempt == 2) {
				throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
						HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
			}
			return model;
		}, 1);

		assertEquals("gemini-2.5-flash", result);
		assertEquals(3, attempts.get());
	}

	@Test
	void doesNotRotateForAuthenticationFailures() {
		GeminiSmartRotationService service = new GeminiSmartRotationService("gemini-2.5-flash, fallback", 0, 0, 2.0);
		AtomicInteger attempts = new AtomicInteger();

		assertThrows(HttpClientErrorException.class, () -> service.executeWithRotation(model -> {
			attempts.incrementAndGet();
			throw HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
					new byte[0], StandardCharsets.UTF_8);
		}, 1));

		assertEquals(1, attempts.get());
	}

	private HttpClientErrorException unavailableModel() {
		String body = """
				{
				  "error": {
				    "code": 404,
				    "message": "This model models/gemini-2.5-pro is no longer available to new users.",
				    "status": "NOT_FOUND"
				  }
				}
				""";
		return HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
				body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}
}
