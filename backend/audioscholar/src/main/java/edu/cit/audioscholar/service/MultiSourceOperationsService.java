package edu.cit.audioscholar.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "multi-source.reconciliation.enabled", havingValue = "true", matchIfMissing = false)
public class MultiSourceOperationsService {
	private static final Logger log = LoggerFactory.getLogger(MultiSourceOperationsService.class);
	private static final List<String> ACTIVE_STATUSES = List.of("ACCEPTED", "PROCESSING_QUEUED", "PROCESSING",
			"SUMMARIZING");

	private final MultiSourceJobRepository jobs;
	private final ValidationEventService events;
	private final MultiSourceOperationsMetrics metrics;
	private final Duration timeout;
	private final Duration leaseDuration;
	private final int maxJobsPerRun;
	private final String workerIdentity;

	public MultiSourceOperationsService(MultiSourceJobRepository jobs, ValidationEventService events,
			MultiSourceOperationsMetrics metrics, @Value("${multi-source.processing-timeout:30m}") Duration timeout,
			@Value("${multi-source.reconciliation-lease-duration:14m}") Duration leaseDuration,
			@Value("${multi-source.reconciliation-max-jobs-per-run:25}") int maxJobsPerRun,
			@Value("${multi-source.worker-identity:multi-source-worker}") String workerIdentity) {
		this.jobs = jobs;
		this.events = events;
		this.metrics = metrics;
		this.timeout = timeout;
		this.leaseDuration = leaseDuration;
		this.maxJobsPerRun = Math.max(1, Math.min(maxJobsPerRun, 50));
		this.workerIdentity = workerIdentity;
	}

	@Scheduled(fixedDelayString = "${multi-source.reconciliation-interval-ms:900000}")
	public void reconcile() {
		long startedAt = System.nanoTime();
		Instant now = Instant.now();
		if (!jobs.tryAcquireReconciliationLease(workerIdentity, Date.from(now.plus(leaseDuration)))) {
			metrics.recordSkippedRun();
			log.debug("Skipping multi-source reconciliation because another instance owns the maintenance lease");
			return;
		}

		Date cutoff = Date.from(now.minus(timeout));
		List<Map<String, Object>> staleJobs = jobs.findStaleNonterminal(ACTIVE_STATUSES, cutoff, maxJobsPerRun);
		int timedOut = reconcileStaleJobs(staleJobs, cutoff);

		List<Map<String, Object>> pendingTerminalEvents = jobs.findPendingTerminalEvents(maxJobsPerRun);
		int republished = republishTerminalEvents(pendingTerminalEvents);
		long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
		metrics.recordRun(staleJobs.size() + pendingTerminalEvents.size(), timedOut, republished, durationMs);

		log.info(
				"Multi-source reconciliation completed candidates={} timedOut={} terminalEventsPublished={} durationMs={} maxJobsPerQuery={}",
				staleJobs.size() + pendingTerminalEvents.size(), timedOut, republished, durationMs, maxJobsPerRun);
	}

	private int reconcileStaleJobs(List<Map<String, Object>> staleJobs, Date cutoff) {
		int timedOut = 0;
		for (Map<String, Object> job : staleJobs) {
			String jobId = string(job.get("jobId"));
			if (jobId == null || !jobs.failIfNonterminalAndStale(jobId, cutoff)) {
				continue;
			}
			timedOut++;
			boolean emitted = events
					.emit(jobId, "MULTI_SOURCE", String.valueOf(job.getOrDefault("clientSource", "UNKNOWN")),
							"JOB_FAILED", List.of(9, 12), "initial", Map.of("jobId", jobId, "outcome", "FAILED",
									"failureStage", "RECONCILIATION", "failureCode", "PROCESSING_TIMEOUT"),
							"multiSourceJobs");
			if (emitted) {
				jobs.markTerminalEventPublished(jobId);
			}
			log.warn("Reconciled stale multi-source job jobId={} failureCode=PROCESSING_TIMEOUT", jobId);
		}
		return timedOut;
	}

	private int republishTerminalEvents(List<Map<String, Object>> pendingJobs) {
		int published = 0;
		for (Map<String, Object> job : pendingJobs) {
			String status = string(job.get("status"));
			if (!"COMPLETE".equals(status) && !"FAILED".equals(status)) {
				log.warn("Ignoring terminal-event marker on nonterminal multi-source job jobId={} status={}",
						job.get("jobId"), status);
				continue;
			}
			String jobId = string(job.get("jobId"));
			if (jobId == null) {
				continue;
			}
			String type = job.get("terminalEventType") instanceof String value
					? value
					: "COMPLETE".equals(status) ? "JOB_COMPLETED" : "JOB_FAILED";
			String occurrenceKey = job.get("terminalEventOccurrenceKey") instanceof String value ? value : "initial";
			List<Integer> objectives = integerList(job.get("terminalEventObjectives"));
			Map<String, Object> payload = map(job.get("terminalEventPayload"));
			if (payload.isEmpty()) {
				payload = Map.of("jobId", jobId, "outcome", status);
			}
			if (events.emit(jobId, "MULTI_SOURCE", String.valueOf(job.getOrDefault("clientSource", "UNKNOWN")), type,
					objectives.isEmpty() ? List.of(9, 12) : objectives, occurrenceKey, payload, "multiSourceJobs")) {
				jobs.markTerminalEventPublished(jobId);
				published++;
			}
		}
		return published;
	}

	private String string(Object value) {
		return value instanceof String text && !text.isBlank() ? text : null;
	}

	private List<Integer> integerList(Object value) {
		if (!(value instanceof List<?> raw)) {
			return List.of();
		}
		return raw.stream().filter(Number.class::isInstance).map(item -> ((Number) item).intValue()).toList();
	}

	private Map<String, Object> map(Object value) {
		if (!(value instanceof Map<?, ?> raw)) {
			return Map.of();
		}
		Map<String, Object> result = new java.util.LinkedHashMap<>();
		raw.forEach((key, item) -> result.put(String.valueOf(key), item));
		return result;
	}
}
