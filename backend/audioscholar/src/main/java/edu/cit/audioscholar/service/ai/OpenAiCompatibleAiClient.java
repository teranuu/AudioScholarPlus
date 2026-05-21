package edu.cit.audioscholar.service.ai;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "ai.openai-compatible.enabled", havingValue = "true")
public class OpenAiCompatibleAiClient implements AiClient {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final Pattern BODY_WAIT_PATTERN = Pattern.compile(
			"(?i)(?:retry[-\\s_]*after|try\\s+again\\s+in|wait)\\D{0,80}(\\d+(?:\\.\\d+)?)\\s*(ms|millisecond(?:s)?|s|sec(?:ond)?(?:s)?|m|min(?:ute)?(?:s)?|h|hour(?:s)?)?");
	private static final Pattern SIMPLE_DURATION_PATTERN = Pattern.compile(
			"(?i)^\\s*(\\d+(?:\\.\\d+)?)\\s*(ms|millisecond(?:s)?|s|sec(?:ond)?(?:s)?|m|min(?:ute)?(?:s)?|h|hour(?:s)?)?\\s*$");

	private final RestTemplate restTemplate;
	private final String apiKey;
	private final String baseUrl;
	private final String chatModel;
	private final int maxRetryAttempts;
	private final long initialBackoffMs;
	private final long maxBackoffMs;
	private final Sleeper sleeper;

	@Autowired
	public OpenAiCompatibleAiClient(RestTemplate restTemplate, @Value("${ai.openai-compatible.api-key:}") String apiKey,
			@Value("${ai.openai-compatible.base-url:https://opencode.ai/zen/v1}") String baseUrl,
			@Value("${ai.openai-compatible.chat-model:big-pickle}") String chatModel,
			@Value("${ai.openai-compatible.retry.max-attempts:3}") int maxRetryAttempts,
			@Value("${ai.openai-compatible.retry.initial-backoff-ms:1000}") long initialBackoffMs,
			@Value("${ai.openai-compatible.retry.max-backoff-ms:60000}") long maxBackoffMs) {
		this(restTemplate, apiKey, baseUrl, chatModel, maxRetryAttempts, initialBackoffMs, maxBackoffMs,
				millis -> Thread.sleep(millis));
	}

	OpenAiCompatibleAiClient(RestTemplate restTemplate, String apiKey, String baseUrl, String chatModel, int maxRetryAttempts,
			long initialBackoffMs, long maxBackoffMs, Sleeper sleeper) {
		this.restTemplate = restTemplate;
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.chatModel = chatModel;
		this.maxRetryAttempts = Math.max(1, maxRetryAttempts);
		this.initialBackoffMs = Math.max(0L, initialBackoffMs);
		this.maxBackoffMs = Math.max(0L, maxBackoffMs);
		this.sleeper = sleeper;
	}

	@Override
	public String transcribeAudio(Path audioFilePath, String fileName) {
		throw new UnsupportedOperationException(
				"OpenAI-compatible transcription is provider-specific; Gemini remains the demo transcription provider.");
	}

	@Override
	public String summarizeTranscript(String transcriptText, String presentationContext, String metadataId) {
		String prompt = "Return only JSON with summaryText, keyPoints, topics, and glossary. Transcript:\n" + transcriptText;
		if (StringUtils.hasText(presentationContext)) {
			prompt += "\n\nPowerPoint context:\n" + presentationContext;
		}

		HttpHeaders headers = new HttpHeaders();
		if (StringUtils.hasText(apiKey)) {
			headers.setBearerAuth(apiKey);
		}
		headers.setContentType(MediaType.APPLICATION_JSON);
		Map<String, Object> body = Map.of("model", chatModel, "temperature", 0.3, "messages",
				List.of(Map.of("role", "user", "content", prompt)));

		ResponseEntity<JsonNode> response = exchangeWithRetry(new HttpEntity<>(body, headers));
		JsonNode content = response.getBody().path("choices").path(0).path("message").path("content");
		return content.asText();
	}

	private ResponseEntity<JsonNode> exchangeWithRetry(HttpEntity<Map<String, Object>> request) {
		int attempt = 1;
		while (true) {
			try {
				return restTemplate.exchange(baseUrl + "/chat/completions", HttpMethod.POST, request, JsonNode.class);
			} catch (HttpStatusCodeException ex) {
				if (!ex.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS) || attempt >= maxRetryAttempts) {
					throw ex;
				}
				sleepBeforeRetry(resolveRetryDelayMs(ex, attempt));
				attempt++;
			}
		}
	}

	private long resolveRetryDelayMs(HttpStatusCodeException ex, int attempt) {
		return parseRetryAfterHeader(ex.getResponseHeaders())
				.or(() -> parseRetryDelayFromBody(ex.getResponseBodyAsString()))
				.map(this::capDelay)
				.orElseGet(() -> boundedExponentialBackoffMs(attempt));
	}

	private Optional<Long> parseRetryAfterHeader(HttpHeaders headers) {
		if (headers == null) {
			return Optional.empty();
		}
		String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (!StringUtils.hasText(retryAfter)) {
			return Optional.empty();
		}
		String value = retryAfter.trim();
		try {
			return Optional.of(Duration.ofSeconds(Long.parseLong(value)).toMillis());
		} catch (NumberFormatException ignored) {
			try {
				long millis = Duration.between(ZonedDateTime.now(),
						ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)).toMillis();
				return Optional.of(Math.max(0L, millis));
			} catch (DateTimeParseException ignoredDate) {
				return Optional.empty();
			}
		}
	}

	private Optional<Long> parseRetryDelayFromBody(String responseBody) {
		if (!StringUtils.hasText(responseBody)) {
			return Optional.empty();
		}
		Optional<Long> jsonDelay = parseJsonRetryDelay(responseBody);
		if (jsonDelay.isPresent()) {
			return jsonDelay;
		}
		Matcher matcher = BODY_WAIT_PATTERN.matcher(responseBody);
		if (matcher.find()) {
			return parseDuration(matcher.group(1), matcher.group(2));
		}
		return Optional.empty();
	}

	private Optional<Long> parseJsonRetryDelay(String responseBody) {
		try {
			JsonNode root = OBJECT_MAPPER.readTree(responseBody);
			return findRetryDelay(root);
		} catch (Exception ignored) {
			return Optional.empty();
		}
	}

	private Optional<Long> findRetryDelay(JsonNode node) {
		if (node == null || node.isNull()) {
			return Optional.empty();
		}
		if (node.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				Optional<Long> directValue = retryDelayFromField(field.getKey(), field.getValue());
				if (directValue.isPresent()) {
					return directValue;
				}
				Optional<Long> nestedValue = findRetryDelay(field.getValue());
				if (nestedValue.isPresent()) {
					return nestedValue;
				}
			}
		} else if (node.isArray()) {
			for (JsonNode child : node) {
				Optional<Long> nestedValue = findRetryDelay(child);
				if (nestedValue.isPresent()) {
					return nestedValue;
				}
			}
		} else if (node.isTextual()) {
			return parseRetryDelayFromBody(node.asText());
		}
		return Optional.empty();
	}

	private Optional<Long> retryDelayFromField(String fieldName, JsonNode value) {
		String normalizedField = fieldName.toLowerCase().replace("_", "").replace("-", "");
		if (!normalizedField.contains("retryafter") && !normalizedField.contains("wait")
				&& !normalizedField.contains("delay")) {
			return Optional.empty();
		}
		if (value.isNumber()) {
			long amount = value.asLong();
			if (normalizedField.contains("ms") || normalizedField.contains("milli")) {
				return Optional.of(Math.max(0L, amount));
			}
			return Optional.of(Duration.ofSeconds(Math.max(0L, amount)).toMillis());
		}
		if (value.isTextual()) {
			return parseDuration(value.asText(), null);
		}
		return Optional.empty();
	}

	private Optional<Long> parseDuration(String amountText, String unitText) {
		try {
			String normalizedAmount = amountText.trim();
			String normalizedUnit = unitText;
			if (normalizedUnit == null) {
				Matcher matcher = SIMPLE_DURATION_PATTERN.matcher(normalizedAmount);
				if (matcher.matches()) {
					normalizedAmount = matcher.group(1);
					normalizedUnit = matcher.group(2);
				}
			}
			double amount = Double.parseDouble(normalizedAmount);
			String unit = normalizedUnit == null ? "s" : normalizedUnit.toLowerCase();
			double millis;
			if (unit.startsWith("ms") || unit.startsWith("millisecond")) {
				millis = amount;
			} else if (unit.startsWith("m")) {
				millis = amount * 60_000D;
			} else if (unit.startsWith("h")) {
				millis = amount * 3_600_000D;
			} else {
				millis = amount * 1_000D;
			}
			return Optional.of(Math.max(0L, (long) millis));
		} catch (NumberFormatException ex) {
			return Optional.empty();
		}
	}

	private long boundedExponentialBackoffMs(int attempt) {
		long delay = initialBackoffMs;
		for (int i = 1; i < attempt && delay < maxBackoffMs; i++) {
			delay = Math.min(maxBackoffMs, delay * 2L);
		}
		return capDelay(delay);
	}

	private long capDelay(long delayMs) {
		if (maxBackoffMs == 0L) {
			return 0L;
		}
		return Math.min(Math.max(0L, delayMs), maxBackoffMs);
	}

	private void sleepBeforeRetry(long delayMs) {
		try {
			sleeper.sleep(delayMs);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting to retry OpenAI-compatible request", ex);
		}
	}

	@FunctionalInterface
	interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}
}
