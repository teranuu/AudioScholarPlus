package edu.cit.audioscholar.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import com.google.cloud.firestore.SetOptions;

import edu.cit.audioscholar.exception.GeminiBudgetExceededException;
import edu.cit.audioscholar.exception.GeminiStageRequestLimitException;

@Service
public class GeminiBudgetService {
	private final Firestore firestore;
	private final boolean enabled;
	private final long projectRpm;
	private final long projectInputTpm;
	private final long projectRpd;
	private final long userRpm;
	private final long userInputTpm;
	private final long multiSourceStageMaxRequests;
	private final String collectionName;
	private final ZoneId quotaZone;

	public GeminiBudgetService(Firestore firestore, @Value("${gemini.guardrails.budget.enabled:true}") boolean enabled,
			@Value("${gemini.guardrails.project-rpm:4}") long projectRpm,
			@Value("${gemini.guardrails.project-input-tpm:200000}") long projectInputTpm,
			@Value("${gemini.guardrails.project-rpd:18}") long projectRpd,
			@Value("${gemini.guardrails.user-rpm:0}") long userRpm,
			@Value("${gemini.guardrails.user-input-tpm:0}") long userInputTpm,
			@Value("${gemini.guardrails.multi-source-stage-max-requests:2}") long multiSourceStageMaxRequests,
			@Value("${gemini.guardrails.budget-collection:gemini_usage_budgets}") String collectionName,
			@Value("${gemini.guardrails.quota-zone:America/Los_Angeles}") String quotaZone) {
		this.firestore = firestore;
		this.enabled = enabled;
		this.projectRpm = projectRpm;
		this.projectInputTpm = projectInputTpm;
		this.projectRpd = projectRpd;
		this.userRpm = userRpm;
		this.userInputTpm = userInputTpm;
		this.multiSourceStageMaxRequests = multiSourceStageMaxRequests;
		this.collectionName = collectionName;
		this.quotaZone = ZoneId.of(quotaZone);
	}

	/**
	 * Reserves a globally paced request slot. The slot is stored in Firestore so
	 * multiple backend instances still maintain one project-wide request cadence.
	 */
	public Instant reservePacingSlot(String operation, String contextId) {
		Instant now = Instant.now();
		if (!enabled || projectRpm <= 0) {
			return now;
		}
		long spacingMillis = Math.max(1L, (long) Math.ceil(60_000d / projectRpm));
		DocumentReference pacingRef = firestore.collection(collectionName).document("project-request-pacing");
		try {
			return firestore.runTransaction(transaction -> {
				Map<String, Object> data = transaction.get(pacingRef).get().getData();
				Instant observedNow = Instant.now();
				Instant nextAllowedAt = instant(data == null ? null : data.get("nextAllowedAt"));
				Instant scheduledAt = nextAllowedAt != null && nextAllowedAt.isAfter(observedNow)
						? nextAllowedAt
						: observedNow;
				Map<String, Object> update = new HashMap<>();
				update.put("nextAllowedAt",
						Timestamp.ofTimeSecondsAndNanos(scheduledAt.plusMillis(spacingMillis).getEpochSecond(),
								scheduledAt.plusMillis(spacingMillis).getNano()));
				update.put("lastScheduledAt",
						Timestamp.ofTimeSecondsAndNanos(scheduledAt.getEpochSecond(), scheduledAt.getNano()));
				update.put("lastOperation", operation);
				update.put("lastContextId", contextId);
				update.put("rpm", projectRpm);
				update.put("updatedAt", Timestamp.now());
				transaction.set(pacingRef, update);
				return scheduledAt;
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while reserving Gemini pacing slot", e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("Could not reserve Gemini pacing slot", e.getCause());
		}
	}

	/**
	 * Performs admission control without consuming requests. Actual consumption is
	 * still recorded by {@link #reserve(String, long, String, String)}.
	 */
	public DailyCapacity requireDailyCapacity(String operation, long requiredRequests, String contextId) {
		if (!enabled || projectRpd <= 0) {
			return new DailyCapacity(0, 0, Long.MAX_VALUE, Long.MAX_VALUE, nextQuotaDay(Instant.now()));
		}
		if (requiredRequests < 1) {
			throw new IllegalArgumentException("requiredRequests must be positive");
		}
		if (!StringUtils.hasText(contextId)) {
			throw new IllegalArgumentException("contextId is required for daily admission");
		}
		Instant now = Instant.now();
		DocumentReference dayRef = projectDayRef(now);
		DocumentReference admissionRef = admissionRef(contextId);
		String quotaDay = quotaDay(now);
		try {
			return firestore.runTransaction(transaction -> {
				Map<String, Object> dayData = transaction.get(dayRef).get().getData();
				Map<String, Object> admissionData = transaction.get(admissionRef).get().getData();
				Usage usage = Usage.from(dayData);
				long admitted = number(dayData == null ? null : dayData.get("admittedRequests"));
				Admission existing = Admission.from(admissionData);
				if (existing.activeFor(quotaDay)) {
					long remaining = Math.max(0, projectRpd - usage.requests - admitted);
					return new DailyCapacity(usage.requests, admitted, remaining, projectRpd, nextQuotaDay(now));
				}
				long remainingBeforeAdmission = Math.max(0, projectRpd - usage.requests - admitted);
				if (requiredRequests > remainingBeforeAdmission) {
					throw new GeminiBudgetExceededException("Gemini daily admission rejected for " + operation
							+ ": required=" + requiredRequests + ", remaining=" + remainingBeforeAdmission,
							nextQuotaDay(now));
				}
				Map<String, Object> dayUpdate = new HashMap<>();
				dayUpdate.put("admittedRequests", admitted + requiredRequests);
				dayUpdate.put("lastAdmissionOperation", operation);
				dayUpdate.put("lastAdmissionContextId", contextId);
				dayUpdate.put("updatedAt", Timestamp.now());
				transaction.set(dayRef, dayUpdate, SetOptions.merge());
				Map<String, Object> admission = new HashMap<>();
				admission.put("contextId", contextId);
				admission.put("quotaDay", quotaDay);
				admission.put("initialReservedRequests", requiredRequests);
				admission.put("reservedRequests", requiredRequests);
				admission.put("remainingRequests", requiredRequests);
				admission.put("consumedRequests", 0L);
				admission.put("actualRequests", 0L);
				admission.put("retryRequests", 0L);
				admission.put("topUpRequests", 0L);
				admission.put("operationRequests", Map.of());
				admission.put("operation", operation);
				admission.put("status", "ACTIVE");
				admission.put("createdAt", Timestamp.now());
				admission.put("updatedAt", Timestamp.now());
				transaction.set(admissionRef, admission);
				long admittedAfter = admitted + requiredRequests;
				return new DailyCapacity(usage.requests, admittedAfter,
						Math.max(0, projectRpd - usage.requests - admittedAfter), projectRpd, nextQuotaDay(now));
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while checking Gemini daily capacity", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof GeminiBudgetExceededException exceeded) {
				throw exceeded;
			}
			throw new IllegalStateException("Could not check Gemini daily capacity", cause);
		}
	}

	/** Releases any unused requests held for a completed or failed admitted job. */
	public void releaseDailyAdmission(String contextId) {
		if (!enabled || projectRpd <= 0 || !StringUtils.hasText(contextId)) {
			return;
		}
		DocumentReference admissionRef = admissionRef(contextId);
		try {
			firestore.runTransaction(transaction -> {
				Map<String, Object> admissionData = transaction.get(admissionRef).get().getData();
				Admission admission = Admission.from(admissionData);
				if (!admission.active()) {
					return null;
				}
				DocumentReference dayRef = firestore.collection(collectionName)
						.document("project-day-" + admission.quotaDay);
				Map<String, Object> dayData = transaction.get(dayRef).get().getData();
				long admitted = number(dayData == null ? null : dayData.get("admittedRequests"));
				Map<String, Object> dayUpdate = new HashMap<>();
				dayUpdate.put("admittedRequests", Math.max(0, admitted - admission.remainingRequests));
				dayUpdate.put("updatedAt", Timestamp.now());
				transaction.set(dayRef, dayUpdate, SetOptions.merge());
				Map<String, Object> admissionUpdate = new HashMap<>();
				admissionUpdate.put("remainingRequests", 0L);
				admissionUpdate.put("status", "RELEASED");
				admissionUpdate.put("releasedAt", Timestamp.now());
				admissionUpdate.put("updatedAt", Timestamp.now());
				transaction.set(admissionRef, admissionUpdate, SetOptions.merge());
				return null;
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while releasing Gemini daily admission", e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("Could not release Gemini daily admission", e.getCause());
		}
	}

	public Reservation reserve(String operation, long inputTokens, String userId, String contextId) {
		if (!enabled) {
			return new Reservation(operation, inputTokens, Instant.now(), false);
		}

		Instant now = Instant.now();
		Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
		String safeUserId = StringUtils.hasText(userId) ? userId : null;
		DocumentReference projectMinuteRef = firestore.collection(collectionName)
				.document("project-minute-" + minute.toString());
		DocumentReference projectDayRef = projectDayRef(now);
		DocumentReference userMinuteRef = safeUserId == null
				? null
				: firestore.collection(collectionName).document("user-" + safeUserId + "-minute-" + minute.toString());
		DocumentReference admissionRef = StringUtils.hasText(contextId) ? admissionRef(contextId) : null;
		String quotaDay = quotaDay(now);

		try {
			return firestore.runTransaction(transaction -> {
				Usage projectMinute = Usage.from(transaction.get(projectMinuteRef).get().getData());
				Map<String, Object> projectDayData = transaction.get(projectDayRef).get().getData();
				Usage projectDay = Usage.from(projectDayData);
				Usage userMinute = userMinuteRef == null
						? Usage.empty()
						: Usage.from(transaction.get(userMinuteRef).get().getData());
				Map<String, Object> admissionData = admissionRef == null
						? null
						: transaction.get(admissionRef).get().getData();
				Admission admission = Admission.from(admissionData);
				long admitted = number(projectDayData == null ? null : projectDayData.get("admittedRequests"));
				boolean sameDayAdmission = admission.activeFor(quotaDay);
				boolean consumesAdmission = sameDayAdmission && admission.remainingRequests > 0;
				boolean requiresTopUp = admission.active() && !consumesAdmission;
				long stageRequests = sameDayAdmission ? admission.operationRequests.getOrDefault(operation, 0L) : 0L;
				boolean retryRequest = sameDayAdmission && isBoundedMultiSourceStage(operation) && stageRequests > 0;
				if (sameDayAdmission && isBoundedMultiSourceStage(operation) && multiSourceStageMaxRequests > 0
						&& stageRequests >= multiSourceStageMaxRequests) {
					throw new GeminiStageRequestLimitException(operation, multiSourceStageMaxRequests);
				}

				Instant retryAt = minute.plus(1, ChronoUnit.MINUTES);
				checkLimit(projectMinute.requests + 1, projectRpm, retryAt, "Gemini project RPM budget exceeded");
				checkLimit(projectMinute.inputTokens + inputTokens, projectInputTpm, retryAt,
						"Gemini project input TPM budget exceeded");
				long nextDailyCommitment = projectDay.requests + 1 + admitted - (consumesAdmission ? 1 : 0);
				checkLimit(nextDailyCommitment, projectRpd, nextQuotaDay(now), "Gemini project RPD budget exceeded");
				if (userMinuteRef != null) {
					checkLimit(userMinute.requests + 1, userRpm, retryAt, "Gemini user RPM budget exceeded");
					checkLimit(userMinute.inputTokens + inputTokens, userInputTpm, retryAt,
							"Gemini user input TPM budget exceeded");
				}

				transaction.set(projectMinuteRef,
						projectMinute.add(1, inputTokens).toMap("minute", minute, operation, contextId));
				Map<String, Object> projectDayUpdate = projectDay.add(1, inputTokens).toMap("day", quotaDayStart(now),
						operation, contextId);
				projectDayUpdate.put("admittedRequests", consumesAdmission ? Math.max(0, admitted - 1) : admitted);
				transaction.set(projectDayRef, projectDayUpdate);
				if (userMinuteRef != null) {
					transaction.set(userMinuteRef,
							userMinute.add(1, inputTokens).toMap("minute", minute, operation, contextId));
				}
				if (admission.active()) {
					Map<String, Object> admissionUpdate = new HashMap<>();
					Map<String, Long> operationRequests = sameDayAdmission
							? new HashMap<>(admission.operationRequests)
							: new HashMap<>();
					operationRequests.put(operation, stageRequests + 1);
					admissionUpdate.put("quotaDay", quotaDay);
					admissionUpdate.put("initialReservedRequests",
							sameDayAdmission ? admission.initialReservedRequests : 0L);
					admissionUpdate.put("reservedRequests",
							sameDayAdmission ? admission.reservedRequests + (requiresTopUp ? 1 : 0) : 1L);
					admissionUpdate.put("remainingRequests", consumesAdmission ? admission.remainingRequests - 1 : 0L);
					admissionUpdate.put("consumedRequests", sameDayAdmission ? admission.consumedRequests + 1 : 1L);
					admissionUpdate.put("actualRequests", sameDayAdmission ? admission.actualRequests + 1 : 1L);
					admissionUpdate.put("retryRequests",
							sameDayAdmission ? admission.retryRequests + (retryRequest ? 1 : 0) : 0L);
					admissionUpdate.put("topUpRequests",
							sameDayAdmission ? admission.topUpRequests + (requiresTopUp ? 1 : 0) : 1L);
					admissionUpdate.put("lastAttemptOperation", operation);
					admissionUpdate.put("operationRequests", operationRequests);
					admissionUpdate.put("status", "ACTIVE");
					if (!sameDayAdmission)
						admissionUpdate.put("createdAt", Timestamp.now());
					admissionUpdate.put("updatedAt", Timestamp.now());
					transaction.set(admissionRef, admissionUpdate, SetOptions.merge());
				}
				return new Reservation(operation, inputTokens, now, retryRequest, requiresTopUp);
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while reserving Gemini budget", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof GeminiBudgetExceededException exceeded) {
				throw exceeded;
			}
			if (cause instanceof GeminiStageRequestLimitException stageLimit) {
				throw stageLimit;
			}
			throw new IllegalStateException("Could not reserve Gemini budget", cause);
		}
	}

	private void checkLimit(long nextValue, long limit, Instant retryAt, String message) {
		if (limit > 0 && nextValue > limit) {
			throw new GeminiBudgetExceededException(message, retryAt);
		}
	}

	private boolean isBoundedMultiSourceStage(String operation) {
		return "transcript-summary.generateContent".equals(operation)
				|| "multi-source.key-point-batch.generateContent".equals(operation)
				|| "multi-source.semantic-pair-batch.generateContent".equals(operation);
	}

	public record Reservation(String operation, long inputTokens, Instant reservedAt, boolean retryRequest,
			boolean topUpRequest) {
		public Reservation(String operation, long inputTokens, Instant reservedAt, boolean retryRequest) {
			this(operation, inputTokens, reservedAt, retryRequest, false);
		}
	}

	public record DailyCapacity(long usedRequests, long admittedRequests, long remainingRequests, long limit,
			Instant resetsAt) {
	}

	private DocumentReference projectDayRef(Instant instant) {
		return firestore.collection(collectionName).document("project-day-" + quotaDay(instant));
	}

	private DocumentReference admissionRef(String contextId) {
		return firestore.collection(collectionName)
				.document("project-admission-" + ValidationEventService.digest(contextId));
	}

	private String quotaDay(Instant instant) {
		return instant.atZone(quotaZone).toLocalDate().toString();
	}

	private Instant quotaDayStart(Instant instant) {
		return instant.atZone(quotaZone).toLocalDate().atStartOfDay(quotaZone).toInstant();
	}

	private Instant nextQuotaDay(Instant instant) {
		ZonedDateTime current = instant.atZone(quotaZone);
		return current.toLocalDate().plusDays(1).atStartOfDay(quotaZone).toInstant();
	}

	private static Instant instant(Object value) {
		if (value instanceof Timestamp timestamp) {
			return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
		}
		if (value instanceof java.util.Date date) {
			return date.toInstant();
		}
		return null;
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

	}

	private record Admission(String quotaDay, long initialReservedRequests, long reservedRequests,
			long remainingRequests, long consumedRequests, long actualRequests, long retryRequests, long topUpRequests,
			Map<String, Long> operationRequests, String status) {
		static Admission from(Map<String, Object> data) {
			if (data == null) {
				return new Admission(null, 0, 0, 0, 0, 0, 0, 0, Map.of(), null);
			}
			long reserved = number(data.get("reservedRequests"));
			long remaining = number(data.get("remainingRequests"));
			long initial = data.containsKey("initialReservedRequests")
					? number(data.get("initialReservedRequests"))
					: reserved;
			long consumed = data.containsKey("consumedRequests")
					? number(data.get("consumedRequests"))
					: Math.max(0, reserved - remaining);
			long actual = data.containsKey("actualRequests") ? number(data.get("actualRequests")) : consumed;
			long retries = data.containsKey("retryRequests")
					? number(data.get("retryRequests"))
					: Math.max(0, actual - initial);
			long topUps = data.containsKey("topUpRequests")
					? number(data.get("topUpRequests"))
					: Math.max(0, reserved - initial);
			Map<String, Long> operations = new HashMap<>();
			if (data.get("operationRequests") instanceof Map<?, ?> rawOperations)
				rawOperations.forEach((key, value) -> operations.put(String.valueOf(key), number(value)));
			return new Admission(String.valueOf(data.get("quotaDay")), initial, reserved, remaining, consumed, actual,
					retries, topUps, operations, String.valueOf(data.get("status")));
		}

		boolean active() {
			return "ACTIVE".equals(status) && quotaDay != null;
		}

		boolean activeFor(String day) {
			return active() && quotaDay.equals(day);
		}
	}

	private static long number(Object value) {
		return value instanceof Number number ? number.longValue() : 0;
	}
}
