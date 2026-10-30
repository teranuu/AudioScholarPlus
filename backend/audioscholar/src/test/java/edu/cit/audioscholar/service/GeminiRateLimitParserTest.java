package edu.cit.audioscholar.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import com.fasterxml.jackson.databind.ObjectMapper;

class GeminiRateLimitParserTest {
	private final GeminiRateLimitParser parser = new GeminiRateLimitParser(new ObjectMapper());

	@Test
	void dailyQuotaUsesProviderModelAndPacificMidnightInsteadOfMisleadingShortDelay() {
		String body = """
				{"error":{"code":429,"message":"quota exhausted","details":[
				{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{
				"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"20",
				"quotaDimensions":{"model":"gemini-2.5-flash"}}]},
				{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"45s"}]}}
				""";
		GeminiRateLimitParser.RateLimitInfo info = parser.parse(error(body, null), "fallback-model");

		assertThat(info.dimension()).isEqualTo(GeminiQuotaDimension.RPD);
		assertThat(info.model()).isEqualTo("gemini-2.5-flash");
		assertThat(info.quotaLimit()).isEqualTo(20L);
		assertThat(info.retryAt().atZone(ZoneId.of("America/Los_Angeles")).toLocalTime().toString()).isEqualTo("00:00");
	}

	@Test
	void minuteQuotaHonorsRetryAfter() {
		Instant before = Instant.now();
		GeminiRateLimitParser.RateLimitInfo info = parser
				.parse(error("{\"error\":{\"message\":\"requests per minute exceeded\"}}", "37"), "gemini-flash");

		assertThat(info.dimension()).isEqualTo(GeminiQuotaDimension.RPM);
		assertThat(Duration.between(before, info.retryAt()).toSeconds()).isBetween(36L, 38L);
	}

	private HttpClientErrorException error(String body, String retryAfter) {
		HttpHeaders headers = new HttpHeaders();
		if (retryAfter != null)
			headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
		return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers,
				body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}
}
