package edu.cit.audioscholar.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Extracts quota dimension, model, limit, and retry time from Gemini errors.
 */
@Component
public class GeminiRateLimitParser {
	private static final Pattern RETRY_SECONDS = Pattern
			.compile("(?i)(?:please\\s+)?retry(?:\\s+in|Delay[\\\"']?\\s*[:=])?\\s*[\\\"']?([0-9]+(?:\\.[0-9]+)?)s");
	private static final Pattern DURATION_SECONDS = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)s$");
	private static final ZoneId PROVIDER_QUOTA_ZONE = ZoneId.of("America/Los_Angeles");

	private final ObjectMapper objectMapper;

	public GeminiRateLimitParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public RateLimitInfo parse(RestClientResponseException exception, String attemptedModel) {
		Instant now = Instant.now();
		String body = exception.getResponseBodyAsString();
		String model = attemptedModel;
		String quotaId = null;
		String quotaMetric = null;
		Long quotaValue = null;
		Duration providerDelay = retryAfter(exception.getResponseHeaders(), now);

		try {
			JsonNode error = objectMapper.readTree(body).path("error");
			Iterator<JsonNode> details = error.path("details").elements();
			while (details.hasNext()) {
				JsonNode detail = details.next();
				String type = detail.path("@type").asText("");
				if (type.endsWith("RetryInfo")) {
					Duration parsed = duration(detail.path("retryDelay").asText(""));
					if (parsed != null)
						providerDelay = parsed;
				}
				if (type.endsWith("QuotaFailure")) {
					for (JsonNode violation : detail.path("violations")) {
						quotaId = firstText(violation, "quotaId", quotaId);
						quotaMetric = firstText(violation, "quotaMetric", quotaMetric);
						String providerModel = violation.path("quotaDimensions").path("model").asText(null);
						if (providerModel != null && !providerModel.isBlank())
							model = providerModel;
						if (violation.hasNonNull("quotaValue"))
							quotaValue = parseLong(violation.path("quotaValue").asText());
					}
				}
			}
		} catch (Exception ignored) {
			// A valid 429 may omit structured details. The message/header fallbacks below
			// still produce a bounded retry time.
		}

		String classificationText = String.join(" ", nullToEmpty(quotaId), nullToEmpty(quotaMetric), nullToEmpty(body))
				.toLowerCase(Locale.ROOT);
		GeminiQuotaDimension dimension = classify(classificationText);
		if (providerDelay == null)
			providerDelay = retryDelayFromMessage(body);
		Instant retryAt = dimension == GeminiQuotaDimension.RPD
				? nextProviderDay(now)
				: now.plus(providerDelay == null ? Duration.ofMinutes(1) : providerDelay);
		return new RateLimitInfo(dimension, normalizeModel(model), retryAt, quotaValue, quotaId, quotaMetric);
	}

	private GeminiQuotaDimension classify(String text) {
		if (text.contains("perday") || text.contains("per_day") || text.contains("requests per day")
				|| text.contains("daily quota") || text.contains("rpd"))
			return GeminiQuotaDimension.RPD;
		if (text.contains("tokensperminute") || text.contains("tokens_per_minute") || text.contains("tpm")
				|| text.contains("token") && (text.contains("perminute") || text.contains("per_minute")))
			return GeminiQuotaDimension.TPM;
		if (text.contains("perminute") || text.contains("per_minute") || text.contains("requests per minute")
				|| text.contains("rpm") || text.contains("too many requests"))
			return GeminiQuotaDimension.RPM;
		if (text.contains("spend") || text.contains("billing") || text.contains("credit"))
			return GeminiQuotaDimension.SPEND;
		return GeminiQuotaDimension.UNKNOWN;
	}

	private Duration retryAfter(HttpHeaders headers, Instant now) {
		if (headers == null)
			return null;
		String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (value == null || value.isBlank())
			return null;
		try {
			return Duration.ofSeconds(Math.max(1, Long.parseLong(value.trim())));
		} catch (NumberFormatException ignored) {
			try {
				Instant retryAt = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
				return Duration.ofMillis(Math.max(1_000, Duration.between(now, retryAt).toMillis()));
			} catch (DateTimeParseException invalidDate) {
				return null;
			}
		}
	}

	private Duration retryDelayFromMessage(String body) {
		Matcher matcher = RETRY_SECONDS.matcher(nullToEmpty(body));
		return matcher.find() ? seconds(matcher.group(1)) : null;
	}

	private Duration duration(String value) {
		Matcher matcher = DURATION_SECONDS.matcher(value.trim());
		return matcher.matches() ? seconds(matcher.group(1)) : null;
	}

	private Duration seconds(String value) {
		try {
			return Duration.ofMillis(Math.max(1_000, (long) Math.ceil(Double.parseDouble(value) * 1_000d)));
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private Instant nextProviderDay(Instant now) {
		ZonedDateTime providerNow = now.atZone(PROVIDER_QUOTA_ZONE);
		return providerNow.toLocalDate().plusDays(1).atStartOfDay(PROVIDER_QUOTA_ZONE).toInstant();
	}

	private String normalizeModel(String model) {
		return model == null || model.isBlank() ? "unknown" : model.trim();
	}

	private String firstText(JsonNode node, String field, String fallback) {
		String value = node.path(field).asText(null);
		return value == null || value.isBlank() ? fallback : value;
	}

	private Long parseLong(String value) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	public record RateLimitInfo(GeminiQuotaDimension dimension, String model, Instant retryAt, Long quotaLimit,
			String quotaId, String quotaMetric) {
	}
}
