package edu.cit.audioscholar.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import edu.cit.audioscholar.exception.GeminiBudgetExceededException;

/**
 * Shared admission, pacing, and request-attempt telemetry for every Gemini
 * generateContent call. Firestore-backed slots in {@link GeminiBudgetService}
 * make the cadence project-wide even when more than one application instance is
 * running.
 */
@Service
public class GeminiRequestGovernor {
	private static final Logger log = LoggerFactory.getLogger(GeminiRequestGovernor.class);

	private final GeminiBudgetService budget;
	private final Duration maxPacingWait;
	private final Map<String, MutableUsage> usageByContext = new ConcurrentHashMap<>();

	public GeminiRequestGovernor(GeminiBudgetService budget,
			@Value("${gemini.guardrails.max-pacing-wait:5m}") Duration maxPacingWait) {
		this.budget = budget;
		this.maxPacingWait = maxPacingWait;
	}

	public void acquire(String operation, long inputTokens, String contextId) {
		Instant requestedAt = Instant.now();
		Instant scheduledAt = budget.reservePacingSlot(operation, contextId);
		long waitMillis = Math.max(0, Duration.between(requestedAt, scheduledAt).toMillis());
		if (waitMillis > maxPacingWait.toMillis()) {
			throw new GeminiBudgetExceededException(
					"Gemini pacing backlog exceeds the configured maximum wait for " + operation, scheduledAt);
		}
		waitForSlot(waitMillis);
		GeminiBudgetService.Reservation reservation = budget.reserve(operation, inputTokens, null, contextId);

		Instant acquiredAt = Instant.now();
		if (StringUtils.hasText(contextId)) {
			usageByContext.computeIfAbsent(contextId, ignored -> new MutableUsage()).record(acquiredAt, waitMillis,
					operation, reservation.retryRequest(), reservation.topUpRequest());
		}
		log.info(
				"Gemini request permit acquired operation={} contextId={} pacingWaitMs={} scheduledAt={} inputTokens={}",
				operation, contextId, waitMillis, scheduledAt, inputTokens);
	}

	public GeminiBudgetService.DailyCapacity requireDailyCapacity(String operation, long requiredRequests,
			String contextId) {
		GeminiBudgetService.DailyCapacity capacity = budget.requireDailyCapacity(operation, requiredRequests,
				contextId);
		log.info(
				"Gemini daily admission accepted operation={} contextId={} requiredRequests={} usedRequests={} admittedRequests={} remainingRequests={} dailyLimit={} resetsAt={}",
				operation, contextId, requiredRequests, capacity.usedRequests(), capacity.admittedRequests(),
				capacity.remainingRequests(), capacity.limit(), capacity.resetsAt());
		return capacity;
	}

	public void releaseDailyAdmission(String contextId) {
		budget.releaseDailyAdmission(contextId);
		log.info("Gemini daily admission released contextId={}", contextId);
	}

	public UsageSnapshot snapshot(String contextId) {
		MutableUsage usage = usageByContext.get(contextId);
		return usage == null ? UsageSnapshot.empty() : usage.snapshot();
	}

	public UsageSnapshot snapshotAndClear(String contextId) {
		MutableUsage usage = usageByContext.remove(contextId);
		return usage == null ? UsageSnapshot.empty() : usage.snapshot();
	}

	private void waitForSlot(long waitMillis) {
		if (waitMillis <= 0) {
			return;
		}
		try {
			Thread.sleep(waitMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for a Gemini request permit", e);
		}
	}

	public record UsageSnapshot(int requestAttempts, int retryRequests, int topUpRequests, String lastAttemptOperation,
			long pacingWaitMs, Instant firstRequestAt, Instant lastRequestAt) {
		public UsageSnapshot(int requestAttempts, long pacingWaitMs, Instant firstRequestAt, Instant lastRequestAt) {
			this(requestAttempts, 0, 0, null, pacingWaitMs, firstRequestAt, lastRequestAt);
		}

		public UsageSnapshot(int requestAttempts, int retryRequests, String lastAttemptOperation, long pacingWaitMs,
				Instant firstRequestAt, Instant lastRequestAt) {
			this(requestAttempts, retryRequests, 0, lastAttemptOperation, pacingWaitMs, firstRequestAt, lastRequestAt);
		}

		static UsageSnapshot empty() {
			return new UsageSnapshot(0, 0, 0, null, 0, null, null);
		}
	}

	private static final class MutableUsage {
		private int requestAttempts;
		private int retryRequests;
		private int topUpRequests;
		private String lastAttemptOperation;
		private long pacingWaitMs;
		private Instant firstRequestAt;
		private Instant lastRequestAt;

		synchronized void record(Instant acquiredAt, long waitedMillis, String operation, boolean retryRequest,
				boolean topUpRequest) {
			requestAttempts++;
			if (retryRequest)
				retryRequests++;
			if (topUpRequest)
				topUpRequests++;
			lastAttemptOperation = operation;
			pacingWaitMs += waitedMillis;
			if (firstRequestAt == null) {
				firstRequestAt = acquiredAt;
			}
			lastRequestAt = acquiredAt;
		}

		synchronized UsageSnapshot snapshot() {
			return new UsageSnapshot(requestAttempts, retryRequests, topUpRequests, lastAttemptOperation, pacingWaitMs,
					firstRequestAt, lastRequestAt);
		}
	}
}
