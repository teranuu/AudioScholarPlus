package edu.cit.audioscholar.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;

import edu.cit.audioscholar.exception.GeminiBudgetExceededException;

@Service
public class GeminiBudgetService {
	private final Firestore firestore;
	private final boolean enabled;
	private final long projectRpm;
	private final long projectInputTpm;
	private final long projectRpd;
	private final long userRpm;
	private final long userInputTpm;
	private final String collectionName;

	public GeminiBudgetService(Firestore firestore, @Value("${gemini.guardrails.budget.enabled:true}") boolean enabled,
			@Value("${gemini.guardrails.project-rpm:10}") long projectRpm,
			@Value("${gemini.guardrails.project-input-tpm:200000}") long projectInputTpm,
			@Value("${gemini.guardrails.project-rpd:500}") long projectRpd,
			@Value("${gemini.guardrails.user-rpm:0}") long userRpm,
			@Value("${gemini.guardrails.user-input-tpm:0}") long userInputTpm,
			@Value("${gemini.guardrails.budget-collection:gemini_usage_budgets}") String collectionName) {
		this.firestore = firestore;
		this.enabled = enabled;
		this.projectRpm = projectRpm;
		this.projectInputTpm = projectInputTpm;
		this.projectRpd = projectRpd;
		this.userRpm = userRpm;
		this.userInputTpm = userInputTpm;
		this.collectionName = collectionName;
	}

	public Reservation reserve(String operation, long inputTokens, String userId, String contextId) {
		if (!enabled) {
			return new Reservation(operation, inputTokens, Instant.now());
		}

		Instant now = Instant.now();
		Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
		Instant day = now.truncatedTo(ChronoUnit.DAYS);
		String safeUserId = StringUtils.hasText(userId) ? userId : null;
		DocumentReference projectMinuteRef = firestore.collection(collectionName)
				.document("project-minute-" + minute.toString());
		DocumentReference projectDayRef = firestore.collection(collectionName).document("project-day-" + day);
		DocumentReference userMinuteRef = safeUserId == null
				? null
				: firestore.collection(collectionName).document("user-" + safeUserId + "-minute-" + minute.toString());

		try {
			return firestore.runTransaction(transaction -> {
				Usage projectMinute = Usage.from(transaction.get(projectMinuteRef).get().getData());
				Usage projectDay = Usage.from(transaction.get(projectDayRef).get().getData());
				Usage userMinute = userMinuteRef == null
						? Usage.empty()
						: Usage.from(transaction.get(userMinuteRef).get().getData());

				Instant retryAt = minute.plus(1, ChronoUnit.MINUTES);
				checkLimit(projectMinute.requests + 1, projectRpm, retryAt, "Gemini project RPM budget exceeded");
				checkLimit(projectMinute.inputTokens + inputTokens, projectInputTpm, retryAt,
						"Gemini project input TPM budget exceeded");
				checkLimit(projectDay.requests + 1, projectRpd, day.plus(1, ChronoUnit.DAYS),
						"Gemini project RPD budget exceeded");
				if (userMinuteRef != null) {
					checkLimit(userMinute.requests + 1, userRpm, retryAt, "Gemini user RPM budget exceeded");
					checkLimit(userMinute.inputTokens + inputTokens, userInputTpm, retryAt,
							"Gemini user input TPM budget exceeded");
				}

				transaction.set(projectMinuteRef,
						projectMinute.add(1, inputTokens).toMap("minute", minute, operation, contextId));
				transaction.set(projectDayRef, projectDay.add(1, inputTokens).toMap("day", day, operation, contextId));
				if (userMinuteRef != null) {
					transaction.set(userMinuteRef,
							userMinute.add(1, inputTokens).toMap("minute", minute, operation, contextId));
				}
				return new Reservation(operation, inputTokens, now);
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while reserving Gemini budget", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof GeminiBudgetExceededException exceeded) {
				throw exceeded;
			}
			throw new IllegalStateException("Could not reserve Gemini budget", cause);
		}
	}

	private void checkLimit(long nextValue, long limit, Instant retryAt, String message) {
		if (limit > 0 && nextValue > limit) {
			throw new GeminiBudgetExceededException(message, retryAt);
		}
	}

	public record Reservation(String operation, long inputTokens, Instant reservedAt) {
	}

	private record Usage(long requests, long inputTokens) {
		static Usage empty() {
			return new Usage(0, 0);
		}

		static Usage from(Map<String, Object> data) {
			if (data == null) {
				return empty();
			}
			return new Usage(number(data.get("requests")), number(data.get("inputTokens")));
		}

		Usage add(long requestCount, long tokens) {
			return new Usage(requests + requestCount, inputTokens + tokens);
		}

		Map<String, Object> toMap(String window, Instant windowStart, String operation, String contextId) {
			Map<String, Object> map = new HashMap<>();
			map.put("window", window);
			map.put("windowStart", Timestamp.ofTimeSecondsAndNanos(windowStart.getEpochSecond(), 0));
			map.put("requests", requests);
			map.put("inputTokens", inputTokens);
			map.put("lastOperation", operation);
			map.put("lastContextId", contextId);
			map.put("updatedAt", Timestamp.now());
			return map;
		}

		private static long number(Object value) {
			return value instanceof Number number ? number.longValue() : 0;
		}
	}
}
