package edu.cit.audioscholar.service;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.cloud.Timestamp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Service
@ConditionalOnProperty(name = "multi-source.operations.enabled", havingValue = "true", matchIfMissing = true)
public class MultiSourceOperationsService {
	private static final Logger log = LoggerFactory.getLogger(MultiSourceOperationsService.class);
	private final MultiSourceJobRepository jobs;
	private final FirebaseService firebase;
	private final ValidationEventService events;
	private final Duration timeout;
	private final Map<String, AtomicLong> gauges = new HashMap<>();
	private final Counter timeoutCounter;

	public MultiSourceOperationsService(MultiSourceJobRepository jobs, FirebaseService firebase,
			ValidationEventService events, MeterRegistry registry,
			@Value("${multi-source.processing-timeout:30m}") Duration timeout) {
		this.jobs = jobs;
		this.firebase = firebase;
		this.events = events;
		this.timeout = timeout;
		this.timeoutCounter = Counter.builder("audioscholar.multi_source.processing_timeout_total").register(registry);
		for (String name : List.of("nonterminal", "timed_out", "source_count_mismatch", "incomplete_stage_runs",
				"failed_stage_runs", "completed_missing_evidence", "duplicate_terminal_events", "merge_latency_p50_ms",
				"merge_latency_p95_ms", "merge_latency_max_ms")) {
			AtomicLong value = new AtomicLong();
			gauges.put(name, value);
			Gauge.builder("audioscholar.multi_source." + name, value, AtomicLong::get).register(registry);
		}
	}

	@Scheduled(fixedDelayString = "${multi-source.reconciliation-interval-ms:60000}")
	public void reconcileAndMeasure() {
		Date cutoff = Date.from(java.time.Instant.now().minus(timeout));
		List<Map<String, Object>> allJobs = jobs.findAll();
		long timedOut = 0;
		for (Map<String, Object> job : allJobs) {
			String status = String.valueOf(job.get("status"));
			if ("COMPLETE".equals(status) || "FAILED".equals(status))
				continue;
			Date updatedAt = date(job.get("updatedAt"));
			String jobId = String.valueOf(job.get("jobId"));
			if (updatedAt != null && updatedAt.before(cutoff) && jobs.failIfNonterminalAndStale(jobId, cutoff)) {
				timedOut++;
				timeoutCounter.increment();
				boolean emitted = events.emit(
						jobId, "MULTI_SOURCE", String.valueOf(job.getOrDefault("clientSource", "UNKNOWN")),
						"JOB_FAILED", List.of(9, 12), "initial", Map.of("jobId", jobId, "outcome", "FAILED",
								"failureStage", "RECONCILIATION", "failureCode", "PROCESSING_TIMEOUT"),
						"multiSourceJobs");
				if (emitted)
					jobs.markTerminalEventPublished(jobId);
				log.warn("Reconciled stale multi-source job jobId={} failureCode=PROCESSING_TIMEOUT", jobId);
			}
		}
		gauges.get("timed_out").set(timedOut);
		measure(jobs.findAll());
	}

	private void measure(List<Map<String, Object>> allJobs) {
		List<Map<String, Object>> sources = firebase.getAllData("sourceFiles");
		List<Map<String, Object>> stageRuns = firebase.getAllData("multiSourceStageRuns");
		List<Map<String, Object>> lineage = firebase.getAllData("mergedKeyPointLineage");
		List<Map<String, Object>> sourcePoints = firebase.getAllData("sourceKeyPoints");
		List<Map<String, Object>> decisions = firebase.getAllData("semanticDecisions");
		List<Map<String, Object>> attributions = firebase.getAllData("sourceAttributions");
		List<Map<String, Object>> alignments = firebase.getAllData("crossSourceAlignments");
		List<Map<String, Object>> summaries = firebase.getAllData("summaries");
		List<Map<String, Object>> terminalEvents = firebase.getAllData("validationEvents").stream()
				.filter(e -> List.of("JOB_COMPLETED", "JOB_FAILED").contains(e.get("eventType"))).toList();
		long nonterminal = 0, mismatch = 0, incompleteRuns = 0, missingEvidence = 0, duplicateTerminal = 0;
		long failedRuns = stageRuns.stream().filter(r -> "FAILED".equals(r.get("status"))).count();
		for (Map<String, Object> job : allJobs) {
			String jobId = String.valueOf(job.get("jobId"));
			String status = String.valueOf(job.get("status"));
			if (!"COMPLETE".equals(status) && !"FAILED".equals(status))
				nonterminal++;
			long actualSources = sources.stream().filter(s -> jobId.equals(s.get("jobId"))).count();
			long expectedSources = job.get("sourceCount") instanceof Number n ? n.longValue() : -1;
			if (expectedSources != actualSources)
				mismatch++;
			if ("COMPLETE".equals(status)) {
				List<Map<String, Object>> jobRuns = stageRuns.stream().filter(r -> jobId.equals(r.get("jobId")))
						.toList();
				if (List.of("SOURCE_KEY_POINT_EXTRACTION", "DEDUPLICATION", "ATTRIBUTION", "ALIGNMENT").stream()
						.anyMatch(stage -> jobRuns.stream()
								.noneMatch(r -> stage.equals(r.get("stage")) && "COMPLETED".equals(r.get("status")))))
					incompleteRuns++;
				if (missingEvidence(jobId, jobRuns, sourcePoints, decisions, lineage, attributions, alignments,
						summaries))
					missingEvidence++;
			}
			long terminalCount = terminalEvents.stream().filter(e -> jobId.equals(e.get("workflowId"))).count();
			if (terminalCount > 1)
				duplicateTerminal++;
			if (("COMPLETE".equals(status) || "FAILED".equals(status))
					&& Boolean.TRUE.equals(job.get("terminalEventPending"))) {
				dispatchTerminalEvent(job, status);
			}
		}
		gauges.get("nonterminal").set(nonterminal);
		gauges.get("source_count_mismatch").set(mismatch);
		gauges.get("incomplete_stage_runs").set(incompleteRuns);
		gauges.get("failed_stage_runs").set(failedRuns);
		gauges.get("completed_missing_evidence").set(missingEvidence);
		gauges.get("duplicate_terminal_events").set(duplicateTerminal);
		long[] latencies = allJobs.stream().map(j -> j.get("mergeLatencyMs")).filter(Number.class::isInstance)
				.mapToLong(v -> ((Number) v).longValue()).sorted().toArray();
		gauges.get("merge_latency_p50_ms").set(percentile(latencies, .50));
		gauges.get("merge_latency_p95_ms").set(percentile(latencies, .95));
		gauges.get("merge_latency_max_ms").set(latencies.length == 0 ? 0 : latencies[latencies.length - 1]);
		if (nonterminal + mismatch + incompleteRuns + failedRuns + missingEvidence + duplicateTerminal > 0)
			log.warn(
					"Multi-source health nonterminal={} sourceMismatch={} incompleteRuns={} failedRuns={} missingEvidence={} duplicateTerminal={}",
					nonterminal, mismatch, incompleteRuns, failedRuns, missingEvidence, duplicateTerminal);
	}

	private boolean missingEvidence(String jobId, List<Map<String, Object>> runs,
			List<Map<String, Object>> sourcePoints, List<Map<String, Object>> decisions,
			List<Map<String, Object>> lineage, List<Map<String, Object>> attributions,
			List<Map<String, Object>> alignments, List<Map<String, Object>> summaries) {
		if (requiresRecords(runs, "SOURCE_KEY_POINT_EXTRACTION", "outputCount")
				&& sourcePoints.stream().noneMatch(row -> jobId.equals(row.get("jobId"))))
			return true;
		if (requiresRecords(runs, "DEDUPLICATION", "candidateCount")
				&& decisions.stream().noneMatch(row -> jobId.equals(row.get("jobId"))))
			return true;
		if (requiresRecords(runs, "ALIGNMENT", "outputCount")
				&& alignments.stream().noneMatch(row -> jobId.equals(row.get("jobId"))))
			return true;
		java.util.Set<Object> mergedPoints = new java.util.HashSet<>();
		for (Map<String, Object> summary : summaries) {
			if (!jobId.equals(summary.get("recordingId"))
					|| !(summary.get("summaryKeyPoints") instanceof List<?> points))
				continue;
			for (Object point : points)
				if (point instanceof Map<?, ?> map && map.get("keyPointId") != null)
					mergedPoints.add(map.get("keyPointId"));
		}
		if (mergedPoints.isEmpty())
			return requiresRecords(runs, "DEDUPLICATION", "outputCount");
		for (Object point : mergedPoints) {
			boolean hasLineage = lineage.stream()
					.anyMatch(row -> jobId.equals(row.get("jobId")) && point.equals(row.get("mergedKeyPointId")));
			boolean hasAttribution = attributions.stream()
					.anyMatch(row -> jobId.equals(row.get("jobId")) && point.equals(row.get("keyPointId")));
			if (!hasLineage || !hasAttribution)
				return true;
		}
		return false;
	}

	private boolean requiresRecords(List<Map<String, Object>> runs, String stage, String countField) {
		return runs.stream().filter(run -> stage.equals(run.get("stage")) && "COMPLETED".equals(run.get("status")))
				.anyMatch(run -> run.get(countField) instanceof Number count && count.longValue() > 0);
	}

	private void dispatchTerminalEvent(Map<String, Object> job, String status) {
		String jobId = String.valueOf(job.get("jobId"));
		String type = job.get("terminalEventType") instanceof String value
				? value
				: "COMPLETE".equals(status) ? "JOB_COMPLETED" : "JOB_FAILED";
		String occurrenceKey = job.get("terminalEventOccurrenceKey") instanceof String value ? value : "initial";
		List<Integer> objectives = job.get("terminalEventObjectives") instanceof List<?> raw
				? raw.stream().filter(Number.class::isInstance).map(value -> ((Number) value).intValue()).toList()
				: List.of(9, 12);
		Map<String, Object> payload = map(job.get("terminalEventPayload"));
		if (payload.isEmpty())
			payload = Map.of("jobId", jobId, "outcome", status);
		if (events.emit(jobId, "MULTI_SOURCE", String.valueOf(job.getOrDefault("clientSource", "UNKNOWN")), type,
				objectives.isEmpty() ? List.of(9, 12) : objectives, occurrenceKey, payload, "multiSourceJobs"))
			jobs.markTerminalEventPublished(jobId);
	}

	private Map<String, Object> map(Object value) {
		Map<String, Object> result = new HashMap<>();
		if (value instanceof Map<?, ?> raw)
			raw.forEach((key, item) -> result.put(String.valueOf(key), item));
		return result;
	}

	private long percentile(long[] values, double percentile) {
		if (values.length == 0)
			return 0;
		int index = (int) Math.ceil(percentile * values.length) - 1;
		return values[Math.max(0, Math.min(index, values.length - 1))];
	}

	private Date date(Object value) {
		if (value instanceof Date date)
			return date;
		if (value instanceof Timestamp timestamp)
			return timestamp.toDate();
		return null;
	}
}
