package edu.cit.audioscholar.config;

import java.util.List;

/** Shared CORS contract for Spring MVC and Spring Security. */
final class CorsPolicy {
	static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH");
	static final List<String> ALLOWED_HEADERS = List.of("Authorization", "Cache-Control", "Content-Type",
			"X-Requested-With", "Accept", "X-CSRF-TOKEN", "X-AudioScholar-Client", "X-Idempotency-Key");
	static final List<String> EXPOSED_HEADERS = List.of("Authorization");

	private CorsPolicy() {
	}
}
