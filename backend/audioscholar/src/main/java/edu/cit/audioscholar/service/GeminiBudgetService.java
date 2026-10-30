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
			@Value("${gemini.guardrails.project-rpm:5}") long projectRpm,
			@Value("${gemini.guardrails.project-input-tpm:250000}") long projectInputTpm,
			@Value("${gemini.guardrails.project-rpd:20}") long projectRpd,
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
	 * Reserves a pacing slot for one provider model. Gemini applies RPM/RPD per
	 * project and model, so unrelated models must not consume the same local
	 * bucket.
	 */
	public Instant reserveModelPacingSlot(String model, String operation, String contextId) {
		Instant now = Instant.now();
		if (!enabled || projectRpm <= 0) {
			return now;
		}
		String normalizedModel = normalizeModel(model);
		DocumentReference pacingRef = modelRef(normalizedModel, "pacing");
		DocumentReference availabilityRef = modelRef(normalizedModel, "availability");
		try {
			return firestore.runTransaction(transaction -> {
				Instant observedNow = Instant.now();
				ModelAvailability availability = ModelAvailability
						.from(transaction.get(availabilityRef).get().getData());
				Instant blockedUntil = availability.blockedUntil(observedNow);
				if (blockedUntil != null)
					throw new GeminiBudgetExceededException(
							"Gemini model " + normalizedModel + " is unavailable until its quota resets", blockedUntil);
				Map<String, Object> data = transaction.get(pacingRef).get().getData();
				Instant nextAllowedAt = instant(data == null ? null : data.get("nextAllowedAt"));
				Instant scheduledAt = nextAllowedAt != null && nextAllowedAt.isAfter(observedNow)
						? nextAllowedAt
						: observedNow;
				Map<String, Object> update = new HashMap<>();
				long effectiveRpm = availability.limit(GeminiQuotaDimension.RPM, projectRpm);
				long spacingMillis = Math.max(1L, (long) Math.ceil(60_000d / Math.max(1, effectiveRpm)));
				Instant followingSlot = scheduledAt.plusMillis(spacingMillis);
				update.put("nextAllowedAt",
						Timestamp.ofTimeSecondsAndNanos(followingSlot.getEpochSecond(), followingSlot.getNano()));
				update.put("lastScheduledAt",
						Timestamp.ofTimeSecondsAndNanos(scheduledAt.getEpochSecond(), scheduledAt.getNano()));
				update.put("lastOperation", operation);
				update.put("lastContextId", contextId);
				update.put("model", normalizedModel);
				update.put("rpm", availability.limit(GeminiQuotaDimension.RPM, projectRpm));
				update.put("updatedAt", Timestamp.now());
				transaction.set(pacingRef, update);
				return scheduledAt;
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while reserving Gemini model pacing slot", e);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof GeminiBudgetExceededException exceeded)
				throw exceeded;
			throw new IllegalStateException("Could not reserve Gemini model pacing slot", e.getCause());
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

	/** Records one request attempt against model-specific RPM, TPM, and RPD. */
	public Reservation reserveModel(String model, String operation, long inputTokens, String contextId) {
		if (!enabled)
			return new Reservation(operation, inputTokens, Instant.now(), false, false);
		Instant now = Instant.now();
		Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
		String normalizedModel = normalizeModel(model);
		DocumentReference minuteRef = modelRef(normalizedModel, "minute-" + minute);
		DocumentReference dayRef = modelRef(normalizedModel, "day-" + quotaDay(now));
		DocumentReference availabilityRef = modelRef(normalizedModel, "availability");
		boolean boundedStage = StringUtils.hasText(contextId) && isBoundedMultiSourceStage(operation);
		DocumentReference stageRef = boundedStage ? stageRef(contextId) : null;
		try {
			return firestore.runTransaction(transaction -> {
				Usage minuteUsage = Usage.from(transaction.get(minuteRef).get().getData());
				Usage dayUsage = Usage.from(transaction.get(dayRef).get().getData());
				ModelAvailability availability = ModelAvailability
						.from(transaction.get(availabilityRef).get().getData());
				Map<String, Object> stageData = boundedStage ? transaction.get(stageRef).get().getData() : null;
				Map<String, Long> stageRequests = operationRequests(stageData);
				long operationAttempts = stageRequests.getOrDefault(operation, 0L);
				if (boundedStage && multiSourceStageMaxRequests > 0 && operationAttempts >= multiSourceStageMaxRequests)
					throw new GeminiStageRequestLimitException(operation, multiSourceStageMaxRequests);
				Instant blockedUntil = availability.blockedUntil(now);
				if (blockedUntil != null)
					throw new GeminiBudgetExceededException(
							"Gemini model " + normalizedModel + " is unavailable until its quota resets", blockedUntil);

				long rpm = availability.limit(GeminiQuotaDimension.RPM, projectRpm);
				long tpm = availability.limit(GeminiQuotaDimension.TPM, projectInputTpm);
				long rpd = availability.limit(GeminiQuotaDimension.RPD, projectRpd);
				checkLimit(minuteUsage.requests + 1, rpm, minute.plus(1, ChronoUnit.MINUTES),
						"Gemini model RPM budget exceeded for " + normalizedModel);
				checkLimit(minuteUsage.inputTokens + inputTokens, tpm, minute.plus(1, ChronoUnit.MINUTES),
						"Gemini model input TPM budget exceeded for " + normalizedModel);
				checkLimit(dayUsage.requests + 1, rpd, nextQuotaDay(now),
						"Gemini model RPD budget exceeded for " + normalizedModel);

				Map<String, Object> minuteUpdate = minuteUsage.add(1, inputTokens).toMap("minute", minute, operation,
						contextId);
				minuteUpdate.put("model", normalizedModel);
				transaction.set(minuteRef, minuteUpdate);
				Map<String, Object> dayUpdate = dayUsage.add(1, inputTokens).toMap("day", quotaDayStart(now), operation,
						contextId);
				dayUpdate.put("model", normalizedModel);
				transaction.set(dayRef, dayUpdate);
				if (boundedStage) {
					stageRequests.put(operation, operationAttempts + 1);
					Map<String, Object> stageUpdate = new HashMap<>();
					stageUpdate.put("contextId", contextId);
					stageUpdate.put("operationRequests", stageRequests);
					stageUpdate.put("updatedAt", Timestamp.now());
					transaction.set(stageRef, stageUpdate, SetOptions.merge());
				}
				return new Reservation(operation, inputTokens, now, false, false);
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while reserving Gemini model budget", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof GeminiBudgetExceededException exceeded)
				throw exceeded;
			if (cause instanceof GeminiStageRequestLimitException stageLimit)
				throw stageLimit;
			throw new IllegalStateException("Could not reserve Gemini model budget", cause);
		}
	}

	/**
	 * Persists provider-reported blocking state so all backend instances see it.
	 */
	public void recordProviderRateLimit(String model, GeminiQuotaDimension dimension, Instant retryAt, Long quotaLimit,
			String detail) {
		if (!enabled || retryAt == null)
			return;
		String normalizedModel = normalizeModel(model);
		DocumentReference availabilityRef = modelRef(normalizedModel, "availability");
		Map<String, Object> update = new HashMap<>();
		update.put("model", normalizedModel);
		update.put(blockField(dimension), Timestamp.of(java.util.Date.from(retryAt)));
		update.put("lastQuotaDimension", dimension.name());
		update.put("lastQuotaDetail", detail == null ? "" : detail);
		update.put("updatedAt", Timestamp.now());
		if (quotaLimit != null && quotaLimit > 0)
			update.put(limitField(dimension), quotaLimit);
		try {
			firestore.runTransaction(transaction -> {
				transaction.set(availabilityRef, update, SetOptions.merge());
				return null;
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while recording Gemini provider quota", e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("Could not record Gemini provider quota", e.getCause());
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

	private DocumentReference modelRef(String model, String suffix) {
		return firestore.collection(collectionName)
				.document("project-model-" + ValidationEventService.digest(model) + "-" + suffix);
	}

	private DocumentReference stageRef(String contextId) {
		return firestore.collection(collectionName)
				.document("project-context-" + ValidationEventService.digest(contextId));
	}

	private Map<String, Long> operationRequests(Map<String, Object> data) {
		Map<String, Long> operations = new HashMap<>();
		if (data != null && data.get("operationRequests") instanceof Map<?, ?> rawOperations)
			rawOperations.forEach((key, value) -> operations.put(String.valueOf(key), number(value)));
		return operations;
	}

	private String normalizeModel(String model) {
		return StringUtils.hasText(model) ? model.trim().toLowerCase(java.util.Locale.ROOT) : "unknown";
	}

	private String blockField(GeminiQuotaDimension dimension) {
		return switch (dimension) {
			case RPM -> "rpmBlockedUntil";
			case TPM -> "tpmBlockedUntil";
			case RPD -> "rpdBlockedUntil";
			case SPEND -> "spendBlockedUntil";
			case UNKNOWN -> "unknownBlockedUntil";
		};
	}

	private String limitField(GeminiQuotaDimension dimension) {
		return switch (dimension) {
			case RPM -> "observedRpmLimit";
			case TPM -> "observedTpmLimit";
			case RPD -> "observedRpdLimit";
			case SPEND, UNKNOWN -> "observedOtherLimit";
		};
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

	private record ModelAvailability(Map<String, Object> data) {
		static ModelAvailability from(Map<String, Object> data) {
			return new ModelAvailability(data == null ? Map.of() : data);
		}

		Instant blockedUntil(Instant now) {
			Instant latest = null;
			for (String field : java.util.List.of("rpmBlockedUntil", "tpmBlockedUntil", "rpdBlockedUntil",
					"spendBlockedUntil", "unknownBlockedUntil")) {
				Instant value = GeminiBudgetService.instant(data.get(field));
				if (value != null && value.isAfter(now) && (latest == null || value.isAfter(latest)))
					latest = value;
			}
			return latest;
		}

		long limit(GeminiQuotaDimension dimension, long configured) {
			String field = switch (dimension) {
				case RPM -> "observedRpmLimit";
				case TPM -> "observedTpmLimit";
				case RPD -> "observedRpdLimit";
				case SPEND, UNKNOWN -> "observedOtherLimit";
			};
			long observed = number(data.get(field));
			return observed > 0 ? observed : configured;
		}
	}

	private static long number(Object value) {
		return value instanceof Number number ? number.longValue() : 0;
	}
}
