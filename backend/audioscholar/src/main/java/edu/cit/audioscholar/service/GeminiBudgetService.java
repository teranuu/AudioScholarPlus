package edu.cit.audioscholar.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;

import edu.cit.audioscholar.exception.GeminiBudgetExceededException;

@Service
public class GeminiBudgetService {
	private final Firestore firestore;
	private final boolean enabled;
	private final long projectRequestsPerMinute;
	private final long projectInputTokensPerMinute;
	private final long projectRequestsPerDay;
	private final long userRequestsPerMinute;
	private final long userInputTokensPerMinute;
	private final String collectionName;

	public GeminiBudgetService(Firestore firestore, @Value("${gemini.guardrails.budget.enabled:true}") boolean enabled,
			@Value("${gemini.guardrails.project-rpm:10}") long projectRequestsPerMinute,
			@Value("${gemini.guardrails.project-input-tpm:200000}") long projectInputTokensPerMinute,
			@Value("${gemini.guardrails.project-rpd:500}") long projectRequestsPerDay,
			@Value("${gemini.guardrails.user-rpm:3}") long userRequestsPerMinute,
			@Value("${gemini.guardrails.user-input-tpm:80000}") long userInputTokensPerMinute,
			@Value("${gemini.guardrails.budget-collection:gemini_usage_budgets}") String collectionName) {
		this.firestore = firestore;
		this.enabled = enabled;
		this.projectRequestsPerMinute = projectRequestsPerMinute;
		this.projectInputTokensPerMinute = projectInputTokensPerMinute;
		this.projectRequestsPerDay = projectRequestsPerDay;
		this.userRequestsPerMinute = userRequestsPerMinute;
		this.userInputTokensPerMinute = userInputTokensPerMinute;
		this.collectionName = collectionName;
	}

	public Reservation reserve(String operation, long inputTokens, String userId, String contextId) {
		if (!enabled) {
			return new Reservation(operation, Math.max(0, inputTokens), Instant.now());
		}
		long safeTokens = Math.max(0, inputTokens);
		Instant now = Instant.now();
		long minuteWindow = now.getEpochSecond() / 60;
		LocalDate utcDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
		Instant nextMinute = Instant.ofEpochSecond((minuteWindow + 1) * 60);
		Instant nextDay = utcDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

		DocumentReference projectMinuteRef = budgetRef("project-minute-" + minuteWindow);
		DocumentReference projectDayRef = budgetRef("project-day-" + utcDate);
		String normalizedUserId = StringUtils.hasText(userId) ? userId : "anonymous";
		DocumentReference userMinuteRef = budgetRef("user-" + normalizedUserId + "-minute-" + minuteWindow);

		try {
			return firestore.runTransaction(transaction -> {
				Bucket projectMinute = bucket(transaction.get(projectMinuteRef).get());
				Bucket projectDay = bucket(transaction.get(projectDayRef).get());
				Bucket userMinute = bucket(transaction.get(userMinuteRef).get());

				assertLimit(projectMinute.requests() + 1, projectRequestsPerMinute, "Gemini project RPM budget",
						nextMinute);
				assertLimit(projectMinute.inputTokens() + safeTokens, projectInputTokensPerMinute,
						"Gemini project input TPM budget", nextMinute);
				assertLimit(projectDay.requests() + 1, projectRequestsPerDay, "Gemini project RPD budget", nextDay);
				assertLimit(userMinute.requests() + 1, userRequestsPerMinute, "Gemini user RPM budget", nextMinute);
				assertLimit(userMinute.inputTokens() + safeTokens, userInputTokensPerMinute,
						"Gemini user input TPM budget", nextMinute);

				transaction.set(projectMinuteRef, bucketMap(projectMinute.requests() + 1,
						projectMinute.inputTokens() + safeTokens, operation, contextId, nextMinute));
				transaction.set(projectDayRef, bucketMap(projectDay.requests() + 1,
						projectDay.inputTokens() + safeTokens, operation, contextId, nextDay));
				transaction.set(userMinuteRef, bucketMap(userMinute.requests() + 1,
						userMinute.inputTokens() + safeTokens, operation, contextId, nextMinute));
				return new Reservation(operation, safeTokens, now);
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GeminiBudgetExceededException("Gemini budget reservation was interrupted", nextMinute);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof GeminiBudgetExceededException budgetExceeded) {
				throw budgetExceeded;
			}
			throw new GeminiBudgetExceededException("Gemini budget could not be reserved safely: "
					+ (cause != null ? cause.getMessage() : e.getMessage()), nextMinute);
		}
	}

	private DocumentReference budgetRef(String id) {
		return firestore.collection(collectionName).document(id);
	}

	private void assertLimit(long requested, long limit, String label, Instant retryAt) {
		if (limit > 0 && requested > limit) {
			throw new GeminiBudgetExceededException(label + " is exhausted", retryAt);
		}
	}

	private Bucket bucket(DocumentSnapshot snapshot) {
		if (snapshot == null || !snapshot.exists()) {
			return new Bucket(0, 0);
		}
		Long requests = snapshot.getLong("requests");
		Long inputTokens = snapshot.getLong("inputTokens");
		return new Bucket(requests != null ? requests : 0, inputTokens != null ? inputTokens : 0);
	}

	private Map<String, Object> bucketMap(long requests, long inputTokens, String operation, String contextId,
			Instant expiresAt) {
		Map<String, Object> data = new HashMap<>();
		data.put("requests", requests);
		data.put("inputTokens", inputTokens);
		data.put("lastOperation", operation);
		if (StringUtils.hasText(contextId)) {
			data.put("lastContextId", contextId);
		}
		data.put("updatedAt", Timestamp.now());
		data.put("expiresAt", Timestamp.of(java.util.Date.from(expiresAt)));
		return data;
	}

	private record Bucket(long requests, long inputTokens) {
	}

	public record Reservation(String operation, long inputTokens, Instant reservedAt) {
	}
}
