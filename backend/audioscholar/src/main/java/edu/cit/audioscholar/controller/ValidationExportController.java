package edu.cit.audioscholar.controller;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;

import edu.cit.audioscholar.service.FirebaseService;
import edu.cit.audioscholar.service.ValidationEventService;

@RestController
@RequestMapping("/api/admin/validation")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "validation.export.enabled", havingValue = "true", matchIfMissing = false)
public class ValidationExportController {
	private static final long MERGE_LATENCY_LIMIT_MS_EXCLUSIVE = 120_000;
	private static final Set<String> VALID_OUTPUT_TYPES = Set.of("NOTES", "STUDY_MATERIAL", "REVIEW_MATERIAL");
	private static final Set<String> ALWAYS_REDACT = Set.of("userid", "email", "filename", "fileurl", "storageurl",
			"transcripttext", "transcriptcontent", "tempaudiofilepath", "tempfilepath", "temppptxfilepath",
			"mergedsummary", "sourcefiles", "qualityreport");
	private static final Set<String> TEXT_REDACT = Set.of("text", "content", "formattedsummarytext", "summarytext",
			"front", "back", "title", "description");
	private static final List<String> COLLECTIONS = List.of("audio_metadata", "multiSourceJobs", "sourceFiles",
			"sourceKeyPoints", "summaries", "mergedSummaries", "transcriptSegments", "summaryKeyPoints",
			"qualityReports", "warningMappings", "warningCandidates", "warningIndicators", "warningDisplayReceipts",
			"semanticDecisions", "sourceAttributions", "crossSourceAlignments", "mergedKeyPointLineage",
			"multiSourceStageRuns");
	private final FirebaseService firebase;
	private final ObjectMapper json;
	public ValidationExportController(FirebaseService firebase, @Qualifier("objectMapper") ObjectMapper json) {
		this.firebase = firebase;
		this.json = json;
	}

	@GetMapping("/export")
	public ResponseEntity<byte[]> export(@RequestParam String from, @RequestParam String to,
			@RequestParam(required = false) List<Integer> objectiveCodes,
			@RequestParam(defaultValue = "WEB") String clientSource,
			@RequestParam(defaultValue = "jsonl") String format,
			@RequestParam(defaultValue = "false") boolean includeText) throws Exception {
		Instant start = Instant.parse(from), end = Instant.parse(to);
		if (!start.isBefore(end) || !"WEB".equals(clientSource))
			throw new IllegalArgumentException("Use an increasing UTC range and clientSource=WEB");
		if (!Set.of("jsonl", "zip").contains(format))
			throw new IllegalArgumentException("format must be jsonl or zip");
		List<Map<String, Object>> events = firebase.getAllData("validationEvents").stream()
				.filter(e -> "WEB".equals(e.get("clientSource"))).filter(e -> {
					Instant at = instant(e.get("occurredAt"));
					return at != null && !at.isBefore(start) && at.isBefore(end);
				})
				.filter(e -> objectiveCodes == null || objectiveCodes.isEmpty()
						|| ((List<?>) e.getOrDefault("objectiveCodes", List.of())).stream()
								.anyMatch(c -> objectiveCodes.contains(((Number) c).intValue())))
				.sorted(Comparator.comparing(e -> instant(e.get("occurredAt")))).toList();
		Set<String> workflowIds = events.stream().map(e -> (String) e.get("workflowId"))
				.filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		Map<String, List<Map<String, Object>>> datasets = new LinkedHashMap<>();
		datasets.put("events", events.stream().map(e -> sanitize(e, false)).toList());
		Set<String> sourceIds = firebase.getAllData("sourceFiles").stream()
				.filter(r -> workflowIds.contains(r.get("jobId"))).map(r -> (String) r.get("sourceFileId"))
				.filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		Set<String> summaryIds = firebase.getAllData("summaries").stream()
				.filter(r -> workflowIds.contains(r.get("recordingId"))).map(r -> (String) r.get("summaryId"))
				.filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		Set<String> mergedSummaryIds = firebase.getAllData("mergedSummaries").stream()
				.filter(r -> workflowIds.contains(r.get("jobId"))).map(r -> (String) r.get("mergedSummaryId"))
				.filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		for (String collection : COLLECTIONS) {
			List<Map<String, Object>> rows = new ArrayList<>();
			for (Map<String, Object> row : firebase.getAllData(collection))
				if (workflowIds.contains(row.get("id")) || workflowIds.contains(row.get("recordingId"))
						|| workflowIds.contains(row.get("jobId")) || workflowIds.contains(row.get("workflowId"))
						|| sourceIds.contains(row.get("sourceFileId")) || sourceIds.contains(row.get("recordingId"))
						|| summaryIds.contains(row.get("summaryId"))
						|| mergedSummaryIds.contains(row.get("mergedSummaryId")))
					rows.add(sanitize(row, includeText));
			datasets.put(collection, rows);
		}
		List<Map<String, Object>> issues = new ArrayList<>();
		for (Map<String, Object> report : datasets.get("qualityReports"))
			if (report.get("issues") instanceof List<?> raw)
				for (Object item : raw)
					if (item instanceof Map<?, ?> map) {
						Map<String, Object> row = new LinkedHashMap<>();
						map.forEach((k, v) -> row.put(String.valueOf(k), v));
						row.put("recordingId", report.get("recordingId"));
						row.put("attemptId", report.get("attemptId"));
						row.put("reportId", report.get("reportId"));
						issues.add(sanitize(row, includeText));
					}
		datasets.put("qualityIssues", issues);
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("schemaVersion", 2);
		manifest.put("from", from);
		manifest.put("to", to);
		manifest.put("clientSource", clientSource);
		manifest.put("includesReviewerText", includeText);
		Set<Integer> exportedObjectives = new java.util.TreeSet<>();
		for (Map<String, Object> event : events)
			if (event.get("objectiveCodes") instanceof List<?> codes)
				for (Object code : codes)
					if (code instanceof Number n)
						exportedObjectives.add(n.intValue());
		manifest.put("objectiveCodes", exportedObjectives);
		manifest.put("module3ObjectiveCodes", List.of(9, 10, 11, 12, 13, 14));
		manifest.put("schemaVersions", Map.of("validationEvents", 1, "groundTruth", 2, "metrics", 2));
		manifest.put("applicationVersions", distinct(datasets, "applicationVersion"));
		manifest.put("modelVersions", distinct(datasets, "modelVersion"));
		manifest.put("promptVersions", distinct(datasets, "promptVersion"));
		manifest.put("detectorVersions", distinct(datasets, "detectorVersion"));
		manifest.put("thresholdVersions", distinct(datasets, "thresholdVersion"));
		manifest.put("warningTemplateVersions", distinct(datasets, "templateVersion"));
		manifest.put("conformanceRuleVersions", distinct(datasets, "conformanceRuleVersion"));
		manifest.put("evaluatorVersions", List.of("module2-evaluator-v2"));
		manifest.put("algorithmVersions", distinct(datasets, "algorithmVersion"));
		manifest.put("counts", datasets.entrySet().stream().collect(
				Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size(), (a, b) -> a, LinkedHashMap::new)));
		manifest.put("eventCounts", events.stream().collect(Collectors
				.groupingBy(e -> String.valueOf(e.get("eventType")), LinkedHashMap::new, Collectors.counting())));
		manifest.put("module3OutcomeMetrics", module3OutcomeMetrics(datasets));
		manifest.put("module3MergeLatencyMetrics", module3MergeLatencyMetrics(datasets));
		Map<String, String> hashes = new LinkedHashMap<>();
		for (var d : datasets.entrySet())
			hashes.put(d.getKey(), ValidationEventService.digest(json.writeValueAsString(normalize(d.getValue()))));
		manifest.put("sha256", hashes);
		manifest.put("exportBindingHash", ValidationEventService.digest(json.writeValueAsString(hashes)));
		List<String> incomplete = new ArrayList<>(incompleteWorkflowIds(datasets));
		manifest.put("measurementIncompleteWorkflows", incomplete);
		manifest.put("missingEvidence", Map.of("incompleteWorkflows", incomplete.size(), "unmappableWarnings",
				datasets.get("warningMappings").stream().filter(r -> "UNMAPPABLE".equals(r.get("decision"))).count(),
				"unresolvedAttributions",
				datasets.get("sourceAttributions").stream().filter(r -> "UNRESOLVED".equals(r.get("attributionStatus"))
						|| "UNRESOLVED".equals(r.get("attributionType"))).count()));
		Map<Integer, String> statuses = module2Statuses(datasets);
		statuses.putAll(module3Statuses(datasets));
		manifest.put("objectiveEvidenceStatus", statuses);
		manifest.put("objectiveIncompleteReasons", objectiveIncompleteReasons(datasets));
		manifest.put("integrityIssues", integrityIssues(datasets));
		if ("jsonl".equals(format)) {
			StringBuilder out = new StringBuilder(
					json.writeValueAsString(Map.of("dataset", "manifest", "data", manifest))).append('\n');
			for (var d : datasets.entrySet())
				for (Map<String, Object> row : d.getValue())
					out.append(json.writeValueAsString(Map.of("dataset", d.getKey(), "data", normalize(row))))
							.append('\n');
			return ResponseEntity.ok()
					.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=validation-export.jsonl")
					.contentType(MediaType.parseMediaType("application/x-ndjson"))
					.body(out.toString().getBytes(StandardCharsets.UTF_8));
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			write(zip, "manifest.json", json.writeValueAsString(manifest));
			for (var d : datasets.entrySet())
				write(zip, d.getKey() + ".csv", csv(d.getValue()));
		}
		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=validation-export.zip")
				.contentType(MediaType.parseMediaType("application/zip")).body(bytes.toByteArray());
	}
	private Map<String, Object> sanitize(Map<?, ?> source, boolean includeText) {
		Map<String, Object> row = new LinkedHashMap<>();
		for (var e : source.entrySet()) {
			String key = String.valueOf(e.getKey()), lower = key.toLowerCase(java.util.Locale.ROOT);
			if (!ALWAYS_REDACT.contains(lower) && (includeText || !TEXT_REDACT.contains(lower)))
				row.put(key, sanitizeValue(e.getValue(), includeText));
		}
		return row;
	}
	private Map<Integer, String> module3Statuses(Map<String, List<Map<String, Object>>> datasets) {
		Map<Integer, String> result = new LinkedHashMap<>();
		Set<String> validJobIds = validBatchIds(datasets);
		List<Map<String, Object>> jobs = datasets.get("multiSourceJobs").stream()
				.filter(job -> validJobIds.contains(String.valueOf(job.get("jobId")))).toList();
		if (jobs.isEmpty()) {
			for (int code = 9; code <= 14; code++)
				result.put(code, "NO_APPLICABLE_RECORDS");
			return result;
		}
		boolean processingFailed = jobs.stream().anyMatch(j -> "FAILED".equals(j.get("status")));
		boolean reviewerEvidenceIncomplete = integrityIssues(datasets).stream()
				.anyMatch(issue -> !issue.startsWith("qualityReport:") && !issue.startsWith("warningMapping:")
						&& !issue.startsWith("warningCandidate:"));
		boolean lifecycleIncomplete = jobs.stream()
				.anyMatch(job -> !validSourceCount(job) || !allSourcesCompleted(datasets, job)
						|| !hasOrderedSuccessfulLifecycle(datasets, String.valueOf(job.get("jobId"))));
		boolean latencyIncomplete = jobs.stream().filter(job -> "COMPLETE".equals(job.get("status")))
				.anyMatch(job -> job.get("finalTranscriptionCompletedAt") == null
						|| job.get("mergedSummaryAvailableAt") == null || job.get("mergeLatencyMs") == null);
		result.put(9, processingFailed ? "PROCESSING_FAILED" : lifecycleIncomplete ? "EXPORT_INCOMPLETE" : "COMPLETE");
		result.put(12,
				processingFailed
						? "PROCESSING_FAILED"
						: lifecycleIncomplete || latencyIncomplete ? "EXPORT_INCOMPLETE" : "COMPLETE");
		result.put(10,
				reviewerStatus(processingFailed, reviewerEvidenceIncomplete,
						datasets.get("sourceKeyPoints").isEmpty() && datasets.get("mergedKeyPointLineage").isEmpty()
								&& completedRunWithCount(datasets, "SOURCE_KEY_POINT_EXTRACTION", "outputCount", 0)
								&& completedRunWithCount(datasets, "DEDUPLICATION", "outputCount", 0)));
		result.put(11,
				reviewerStatus(processingFailed, reviewerEvidenceIncomplete, datasets.get("semanticDecisions").isEmpty()
						&& completedRunWithCount(datasets, "DEDUPLICATION", "candidateCount", 0)));
		result.put(13,
				reviewerStatus(processingFailed, reviewerEvidenceIncomplete,
						datasets.get("sourceAttributions").isEmpty()
								&& completedRunWithCount(datasets, "ATTRIBUTION", "outputCount", 0)));
		result.put(14,
				reviewerStatus(processingFailed, reviewerEvidenceIncomplete,
						datasets.get("crossSourceAlignments").isEmpty()
								&& completedRunWithCount(datasets, "ALIGNMENT", "outputCount", 0)));
		return result;
	}

	private Map<Integer, String> module2Statuses(Map<String, List<Map<String, Object>>> datasets) {
		Map<Integer, String> result = new LinkedHashMap<>();
		Map<String, List<Map<String, Object>>> configurationEvents = configurationEvents(datasets.get("events"));
		if (configurationEvents.isEmpty())
			result.put(2, "NO_APPLICABLE_RECORDS");
		else {
			Set<String> incomplete = incompleteWorkflowIds(datasets);
			boolean complete = configurationEvents.entrySet().stream()
					.allMatch(entry -> validConfiguration(entry.getValue()));
			result.put(2,
					configurationEvents.keySet().stream().anyMatch(incomplete::contains)
							? "EXPORT_INCOMPLETE"
							: complete ? "COMPLETE" : "NOT_EVALUABLE");
		}
		List<Map<String, Object>> reports = datasets.get("qualityReports");
		boolean hasReviewedEligibleReport = reports.stream()
				.anyMatch(r -> r.get("reportId") != null && r.get("attemptId") != null
						&& "COMPLETED".equals(r.get("analysisStatus")) && "FULL_PCM".equals(r.get("analysisMode")));
		for (int code = 3; code <= 5; code++)
			result.put(code, hasReviewedEligibleReport ? "REVIEWER_EVIDENCE_REQUIRED" : "NOT_EVALUABLE");
		Set<String> expectedDeliveries = expectedQualityDeliveries(datasets);
		Set<String> deliveredAttempts = deliveredQualityDeliveries(reports);
		result.put(6,
				expectedDeliveries.isEmpty()
						? "NO_APPLICABLE_RECORDS"
						: deliveredAttempts.containsAll(expectedDeliveries) ? "COMPLETE" : "NOT_EVALUABLE");
		boolean stale = datasets.get("summaries").stream()
				.anyMatch(s -> Boolean.TRUE.equals(s.get("warningEvidenceStale")));
		List<Map<String, Object>> coverageMappings = datasets.get("warningMappings").stream()
				.filter(m -> !Boolean.TRUE.equals(m.get("stale")))
				.filter(m -> !"LEGACY_READ_BACKFILL".equals(m.get("evidenceSource")))
				.filter(m -> Set.of("OVERLAP", "UNMAPPABLE").contains(String.valueOf(m.get("decision")))).toList();
		boolean malformedCoverageMapping = coverageMappings.stream()
				.anyMatch(m -> m.get("issueId") == null || String.valueOf(m.get("issueId")).isBlank());
		result.put(7,
				coverageMappings.isEmpty()
						? "NO_APPLICABLE_RECORDS"
						: stale || malformedCoverageMapping ? "NOT_EVALUABLE" : "COMPLETE");
		boolean candidates = datasets.get("warningCandidates").stream().anyMatch(
				c -> !Boolean.TRUE.equals(c.get("stale")) && !"LEGACY_READ_BACKFILL".equals(c.get("evidenceSource")));
		result.put(8, candidates ? "EXPERT_REVIEW_REQUIRED" : "NOT_EVALUABLE");
		return result;
	}

	private Map<Integer, List<Map<String, Object>>> objectiveIncompleteReasons(
			Map<String, List<Map<String, Object>>> datasets) {
		Map<Integer, List<Map<String, Object>>> result = new LinkedHashMap<>();
		for (int code = 2; code <= 8; code++)
			result.put(code, new ArrayList<>());
		Set<String> incomplete = incompleteWorkflowIds(datasets);
		for (var entry : configurationEvents(datasets.get("events")).entrySet()) {
			String workflowId = entry.getKey();
			if (incomplete.contains(workflowId))
				result.get(2).add(Map.of("workflowId", workflowId, "reason", "MEASUREMENT_INCOMPLETE"));
			else if (!validConfiguration(entry.getValue()))
				result.get(2)
						.add(Map.of("workflowId", workflowId, "reason", configurationFailureReason(entry.getValue())));
		}
		for (Map<String, Object> report : datasets.get("qualityReports")) {
			if (!"COMPLETED".equals(report.get("analysisStatus")) || !"FULL_PCM".equals(report.get("analysisMode")))
				for (int code = 3; code <= 5; code++)
					result.get(code).add(Map.of("attemptId", String.valueOf(report.get("attemptId")), "reason",
							"QUALITY_ANALYSIS_NOT_FULL_PCM"));
		}
		Set<String> validReportAttempts = deliveredQualityDeliveries(datasets.get("qualityReports"));
		for (String delivery : expectedQualityDeliveries(datasets))
			if (!validReportAttempts.contains(delivery))
				result.get(6).add(Map.of("deliveryId", delivery, "reason", "VALID_QUALITY_REPORT_MISSING"));
		for (Map<String, Object> summary : datasets.get("summaries"))
			if (Boolean.TRUE.equals(summary.get("warningEvidenceStale")))
				for (int code : List.of(7, 8))
					result.get(code).add(Map.of("recordingId", String.valueOf(summary.get("recordingId")), "reason",
							"WARNING_EVIDENCE_STALE"));
		return result;
	}

	private Set<String> validBatchIds(Map<String, List<Map<String, Object>>> datasets) {
		return datasets.get("events").stream().filter(event -> "BATCH_VALIDATED".equals(event.get("eventType")))
				.map(event -> String.valueOf(event.get("workflowId"))).filter(id -> !id.isBlank())
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private boolean validSourceCount(Map<String, Object> job) {
		return job.get("sourceCount") instanceof Number count && count.longValue() >= 2 && count.longValue() <= 5;
	}

	private boolean allSourcesCompleted(Map<String, List<Map<String, Object>>> datasets, Map<String, Object> job) {
		if (!(job.get("sourceCount") instanceof Number expected))
			return false;
		String jobId = String.valueOf(job.get("jobId"));
		long completed = datasets.get("sourceFiles").stream().filter(source -> jobId.equals(source.get("jobId")))
				.filter(source -> source.get("transcriptionCompletedAt") != null).count();
		return completed == expected.longValue();
	}

	private boolean hasOrderedSuccessfulLifecycle(Map<String, List<Map<String, Object>>> datasets, String jobId) {
		List<Map<String, Object>> workflowEvents = datasets.get("events").stream()
				.filter(event -> jobId.equals(String.valueOf(event.get("workflowId")))).toList();
		long terminalCount = workflowEvents.stream()
				.filter(event -> Set.of("JOB_COMPLETED", "JOB_FAILED").contains(event.get("eventType"))).count();
		if (terminalCount != 1)
			return false;
		Instant previous = null;
		for (String type : List.of("BATCH_VALIDATED", "JOB_ACCEPTED", "ALL_SOURCES_UPLOADED", "JOB_QUEUED",
				"PROCESSING_STARTED", "JOB_COMPLETED")) {
			Instant occurredAt = workflowEvents.stream().filter(event -> type.equals(event.get("eventType")))
					.map(event -> instant(event.get("occurredAt"))).filter(java.util.Objects::nonNull)
					.min(Instant::compareTo).orElse(null);
			if (occurredAt == null || previous != null && occurredAt.isBefore(previous))
				return false;
			previous = occurredAt;
		}
		return true;
	}

	private String attemptId(Map<String, Object> event) {
		Object direct = event.get("attemptId");
		if (direct != null)
			return String.valueOf(direct);
		if (event.get("payload") instanceof Map<?, ?> payload && payload.get("attemptId") != null)
			return String.valueOf(payload.get("attemptId"));
		return "";
	}

	private boolean validDeliveredReport(Map<String, Object> report) {
		String status = String.valueOf(report.getOrDefault("status", ""));
		if (report.get("reportId") == null || report.get("attemptId") == null
				|| !Set.of("ALL_CLEAR", "ISSUES_DETECTED").contains(status))
			return false;
		return !"ALL_CLEAR".equals(status)
				|| "COMPLETED".equals(report.get("analysisStatus")) && "FULL_PCM".equals(report.get("analysisMode"));
	}

	private Map<String, List<Map<String, Object>>> configurationEvents(List<Map<String, Object>> events) {
		return events.stream()
				.filter(event -> Set.of("JOB_ACCEPTED", "CONFIG_CONFIRMED", "PROCESSING_STARTED")
						.contains(event.get("eventType")))
				.filter(event -> event.get("workflowId") != null).collect(Collectors.groupingBy(
						event -> String.valueOf(event.get("workflowId")), LinkedHashMap::new, Collectors.toList()));
	}

	private boolean validConfiguration(List<Map<String, Object>> events) {
		Instant processingStarted = events.stream().filter(event -> "PROCESSING_STARTED".equals(event.get("eventType")))
				.map(event -> instant(event.get("occurredAt"))).filter(java.util.Objects::nonNull)
				.min(Instant::compareTo).orElse(null);
		return events.stream().filter(event -> "CONFIG_CONFIRMED".equals(event.get("eventType")))
				.filter(event -> event.get("payload") instanceof Map<?, ?> payload
						&& VALID_OUTPUT_TYPES.contains(String.valueOf(payload.get("outputType"))))
				.map(event -> instant(event.get("occurredAt"))).filter(java.util.Objects::nonNull)
				.anyMatch(configuredAt -> processingStarted == null || configuredAt.isBefore(processingStarted));
	}

	private String configurationFailureReason(List<Map<String, Object>> events) {
		List<Map<String, Object>> confirmations = events.stream()
				.filter(event -> "CONFIG_CONFIRMED".equals(event.get("eventType"))).toList();
		if (confirmations.isEmpty())
			return "CONFIG_CONFIRMED_MISSING";
		if (confirmations.stream().noneMatch(event -> event.get("payload") instanceof Map<?, ?> payload
				&& VALID_OUTPUT_TYPES.contains(String.valueOf(payload.get("outputType")))))
			return "OUTPUT_TYPE_INVALID_OR_MISSING";
		return "CONFIG_NOT_BEFORE_PROCESSING";
	}

	private Set<String> incompleteWorkflowIds(Map<String, List<Map<String, Object>>> datasets) {
		Set<String> result = new LinkedHashSet<>();
		for (Map<String, Object> job : datasets.get("multiSourceJobs"))
			if (Boolean.TRUE.equals(job.get("measurementIncomplete")))
				result.add(String.valueOf(job.get("jobId")));
		for (Map<String, Object> metadata : datasets.get("audio_metadata"))
			if (Boolean.TRUE.equals(metadata.get("measurementIncomplete")))
				result.add(String.valueOf(metadata.getOrDefault("id", metadata.get("recordingId"))));
		for (Map<String, Object> event : datasets.get("events"))
			if ("EVIDENCE_WRITE_FAILED".equals(event.get("eventType")) && event.get("workflowId") != null)
				result.add(String.valueOf(event.get("workflowId")));
		return result;
	}

	private Set<String> expectedQualityDeliveries(Map<String, List<Map<String, Object>>> datasets) {
		Set<String> result = new LinkedHashSet<>();
		Set<String> multiSourceWorkflows = new LinkedHashSet<>();
		for (Map<String, Object> event : datasets.get("events"))
			if ("PROCESSING_STARTED".equals(event.get("eventType"))) {
				if ("SINGLE_SOURCE".equals(event.get("workflowType")))
					result.add("SINGLE_SOURCE:" + attemptId(event));
				else if ("MULTI_SOURCE".equals(event.get("workflowType")))
					multiSourceWorkflows.add(String.valueOf(event.get("workflowId")));
			}
		for (Map<String, Object> source : datasets.get("sourceFiles"))
			if (multiSourceWorkflows.contains(String.valueOf(source.get("jobId")))
					&& "MEDIA".equals(source.get("sourceKind")))
				result.add("MULTI_SOURCE:" + source.getOrDefault("sourceFileId", ""));
		result.removeIf(value -> value.endsWith(":"));
		return result;
	}

	private Set<String> deliveredQualityDeliveries(List<Map<String, Object>> reports) {
		Set<String> result = new LinkedHashSet<>();
		for (Map<String, Object> report : reports)
			if (validDeliveredReport(report)) {
				result.add("SINGLE_SOURCE:" + report.getOrDefault("attemptId", ""));
				result.add("MULTI_SOURCE:" + report.getOrDefault("recordingId", ""));
			}
		result.removeIf(value -> value.endsWith(":"));
		return result;
	}

	private String reviewerStatus(boolean processingFailed, boolean incomplete, boolean noApplicableRecords) {
		if (processingFailed)
			return "PROCESSING_FAILED";
		if (incomplete)
			return "EXPORT_INCOMPLETE";
		return noApplicableRecords ? "NO_APPLICABLE_RECORDS" : "REVIEWER_EVIDENCE_REQUIRED";
	}

	private boolean completedRunWithCount(Map<String, List<Map<String, Object>>> datasets, String stage, String field,
			long expected) {
		return datasets.get("multiSourceStageRuns").stream()
				.anyMatch(run -> stage.equals(run.get("stage")) && "COMPLETED".equals(run.get("status"))
						&& run.get(field) instanceof Number count && count.longValue() == expected);
	}

	private List<String> integrityIssues(Map<String, List<Map<String, Object>>> datasets) {
		List<String> issues = new ArrayList<>();
		Set<Object> qualityReportIds = datasets.get("qualityReports").stream().map(r -> r.get("reportId"))
				.collect(Collectors.toSet());
		Set<Object> qualityIssueIds = datasets.get("qualityIssues").stream().map(r -> r.get("issueId"))
				.collect(Collectors.toSet());
		for (Map<String, Object> report : datasets.get("qualityReports"))
			if (report.get("reportId") == null || report.get("attemptId") == null)
				issues.add("qualityReport:" + report.get("id") + ":missing-report-or-attempt-id");
		for (Map<String, Object> mapping : datasets.get("warningMappings"))
			if (!qualityIssueIds.contains(mapping.get("issueId")))
				issues.add("warningMapping:" + mapping.get("mappingId") + ":missing-quality-issue");
		for (Map<String, Object> candidate : datasets.get("warningCandidates"))
			if (!qualityReportIds.contains(candidate.get("reportId"))
					|| !qualityIssueIds.contains(candidate.get("issueId")))
				issues.add("warningCandidate:" + candidate.get("warningId") + ":invalid-quality-reference");
		Set<Object> sourceIds = datasets.get("sourceFiles").stream().map(r -> r.get("sourceFileId"))
				.collect(Collectors.toSet());
		Set<Object> pointIds = datasets.get("sourceKeyPoints").stream().map(r -> r.get("keyPointId"))
				.collect(Collectors.toSet());
		Set<Object> decisionIds = datasets.get("semanticDecisions").stream().map(r -> r.get("decisionId"))
				.collect(Collectors.toSet());
		for (Map<String, Object> lineage : datasets.get("mergedKeyPointLineage"))
			if (!(lineage.get("sourceKeyPointIds") instanceof List<?> ids) || ids.isEmpty()
					|| ids.stream().anyMatch(id -> !pointIds.contains(id)))
				issues.add("lineage:" + lineage.get("lineageId") + ":missing-source-point");
			else if (lineage.get("semanticDecisionIds") instanceof List<?> lineageDecisions
					&& lineageDecisions.stream().anyMatch(id -> !decisionIds.contains(id)))
				issues.add("lineage:" + lineage.get("lineageId") + ":missing-semantic-decision");
		for (Map<String, Object> decision : datasets.get("semanticDecisions"))
			if (!pointIds.contains(decision.get("leftKeyPointId"))
					|| !pointIds.contains(decision.get("rightKeyPointId")))
				issues.add("semanticDecision:" + decision.get("decisionId") + ":missing-source-point");
		for (Map<String, Object> alignment : datasets.get("crossSourceAlignments"))
			if (!sourceIds.contains(alignment.get("sourceAFileId"))
					|| !sourceIds.contains(alignment.get("sourceBFileId")))
				issues.add("alignment:" + alignment.get("alignmentId") + ":invalid-source");
		for (Map<String, Object> attribution : datasets.get("sourceAttributions"))
			if (!(attribution.get("attributionStatus") instanceof String status)
					|| !Set.of("RESOLVED", "UNRESOLVED", "MULTI_SOURCE").contains(status))
				issues.add("attribution:" + attribution.get("attributionId") + ":missing-status");
			else if (!sourceIds.contains(attribution.get("sourceFileId")))
				issues.add("attribution:" + attribution.get("attributionId") + ":invalid-source");
		for (Map<String, Object> job : datasets.get("multiSourceJobs")) {
			if (!"COMPLETE".equals(job.get("status")))
				continue;
			String jobId = String.valueOf(job.get("jobId"));
			for (String timestamp : List.of("finalTranscriptionCompletedAt", "mergeStartedAt",
					"mergedSummaryAvailableAt", "mergeLatencyMs"))
				if (job.get(timestamp) == null)
					issues.add("job:" + jobId + ":missing-" + timestamp);
			for (String stage : List.of("SOURCE_KEY_POINT_EXTRACTION", "DEDUPLICATION", "ATTRIBUTION", "ALIGNMENT"))
				if (datasets.get("multiSourceStageRuns").stream().noneMatch(r -> jobId.equals(r.get("jobId"))
						&& stage.equals(r.get("stage")) && "COMPLETED".equals(r.get("status"))))
					issues.add("job:" + jobId + ":missing-stage-run-" + stage.toLowerCase(java.util.Locale.ROOT));
			Set<Object> mergedForJob = mergedPointIds(datasets, jobId);
			boolean explainedEmpty = datasets.get("multiSourceStageRuns").stream()
					.anyMatch(run -> jobId.equals(run.get("jobId")) && "DEDUPLICATION".equals(run.get("stage"))
							&& "COMPLETED".equals(run.get("status")) && run.get("outputCount") instanceof Number count
							&& count.longValue() == 0);
			if (mergedForJob.isEmpty() && !explainedEmpty)
				issues.add("job:" + jobId + ":unexplained-empty-merged-evidence");
			for (Object point : mergedForJob) {
				boolean hasLineage = datasets.get("mergedKeyPointLineage").stream()
						.anyMatch(r -> jobId.equals(r.get("jobId")) && point.equals(r.get("mergedKeyPointId")));
				boolean hasAttribution = datasets.get("sourceAttributions").stream()
						.anyMatch(r -> jobId.equals(r.get("jobId")) && point.equals(r.get("keyPointId")));
				if (!hasLineage || !hasAttribution)
					issues.add("mergedKeyPoint:" + point + ":missing-lineage-or-attribution");
			}
		}
		return issues;
	}

	private Set<Object> mergedPointIds(Map<String, List<Map<String, Object>>> datasets, String jobId) {
		Set<Object> result = new LinkedHashSet<>();
		for (Map<String, Object> summary : datasets.get("summaries")) {
			if (!jobId.equals(summary.get("recordingId"))
					|| !(summary.get("summaryKeyPoints") instanceof List<?> points))
				continue;
			for (Object point : points)
				if (point instanceof Map<?, ?> map && map.get("keyPointId") != null)
					result.add(map.get("keyPointId"));
		}
		return result;
	}

	private Map<String, Object> module3OutcomeMetrics(Map<String, List<Map<String, Object>>> datasets) {
		List<Map<String, Object>> jobs = datasets.get("multiSourceJobs");
		Set<String> validJobIds = validBatchIds(datasets);
		List<Map<String, Object>> validJobs = jobs.stream()
				.filter(job -> validJobIds.contains(String.valueOf(job.get("jobId")))).toList();
		long completed = validJobs.stream().filter(j -> "COMPLETE".equals(j.get("status"))).count();
		Map<String, Long> failures = validJobs.stream().filter(j -> "FAILED".equals(j.get("status")))
				.collect(Collectors.groupingBy(j -> String.valueOf(j.getOrDefault("failureCode", "UNKNOWN")),
						LinkedHashMap::new, Collectors.counting()));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("submittedJobs", jobs.size());
		result.put("validJobs", validJobs.size());
		result.put("acceptedJobs", validJobs.size());
		result.put("numeratorCompleted", completed);
		result.put("denominatorValid", validJobs.size());
		result.put("excludedBeforeValidation", Math.max(0, jobs.size() - validJobs.size()));
		result.put("failureReasons", failures);
		return result;
	}

	private Map<String, Object> module3MergeLatencyMetrics(Map<String, List<Map<String, Object>>> datasets) {
		Set<String> validJobIds = validBatchIds(datasets);
		long[] values = datasets.get("multiSourceJobs").stream()
				.filter(job -> validJobIds.contains(String.valueOf(job.get("jobId")))).map(j -> j.get("mergeLatencyMs"))
				.filter(Number.class::isInstance).mapToLong(value -> ((Number) value).longValue()).sorted().toArray();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("targetMs", MERGE_LATENCY_LIMIT_MS_EXCLUSIVE);
		result.put("targetMsExclusive", MERGE_LATENCY_LIMIT_MS_EXCLUSIVE);
		result.put("measuredJobs", values.length);
		result.put("withinTarget",
				java.util.Arrays.stream(values).filter(value -> value < MERGE_LATENCY_LIMIT_MS_EXCLUSIVE).count());
		result.put("p50Ms", percentile(values, .50));
		result.put("p95Ms", percentile(values, .95));
		result.put("maxMs", values.length == 0 ? null : values[values.length - 1]);
		return result;
	}

	private Long percentile(long[] values, double percentile) {
		if (values.length == 0)
			return null;
		int index = (int) Math.ceil(percentile * values.length) - 1;
		return values[Math.max(0, Math.min(index, values.length - 1))];
	}
	private Object sanitizeValue(Object value, boolean includeText) {
		if (value instanceof Map<?, ?> map)
			return sanitize(map, includeText);
		if (value instanceof Collection<?> c)
			return c.stream().map(v -> sanitizeValue(v, includeText)).toList();
		return value;
	}
	private List<Object> distinct(Map<String, List<Map<String, Object>>> datasets, String field) {
		Set<Object> values = new LinkedHashSet<>();
		datasets.values().forEach(rows -> rows.forEach(r -> collect(r, field, values)));
		return new ArrayList<>(values);
	}
	private void collect(Object value, String field, Set<Object> values) {
		if (value instanceof Map<?, ?> map)
			map.forEach((k, v) -> {
				if (field.equals(String.valueOf(k)) && v != null)
					values.add(v);
				collect(v, field, values);
			});
		else if (value instanceof Collection<?> c)
			c.forEach(v -> collect(v, field, values));
	}
	private String csv(List<Map<String, Object>> rows) throws Exception {
		Set<String> columns = new LinkedHashSet<>();
		List<Map<String, Object>> flatRows = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			Map<String, Object> flat = new LinkedHashMap<>();
			flatten("", normalize(row), flat);
			flatRows.add(flat);
			columns.addAll(flat.keySet());
		}
		StringBuilder out = new StringBuilder(columns.stream().map(this::cell).collect(Collectors.joining(",")))
				.append('\n');
		for (Map<String, Object> row : flatRows)
			out.append(columns.stream().map(c -> cell(row.get(c))).collect(Collectors.joining(","))).append('\n');
		return out.toString();
	}
	private void flatten(String prefix, Object value, Map<String, Object> target) {
		if (value instanceof Map<?, ?> map)
			map.forEach((k, v) -> flatten(prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, v, target));
		else
			target.put(prefix, value);
	}
	private String cell(Object value) {
		if (value == null)
			return "";
		String text;
		try {
			text = value instanceof String s ? s : json.writeValueAsString(value);
		} catch (Exception e) {
			text = String.valueOf(value);
		}
		return "\"" + text.replace("\"", "\"\"") + "\"";
	}
	private void write(ZipOutputStream zip, String name, String content) throws Exception {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
	private Object normalize(Object value) {
		if (value instanceof Timestamp t)
			return t.toDate().toInstant().toString();
		if (value instanceof java.util.Date d)
			return d.toInstant().toString();
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> result = new LinkedHashMap<>();
			map.forEach((k, v) -> result.put(String.valueOf(k), normalize(v)));
			return result;
		}
		if (value instanceof Collection<?> c)
			return c.stream().map(this::normalize).toList();
		return value;
	}
	private Instant instant(Object value) {
		if (value instanceof Timestamp t)
			return t.toDate().toInstant();
		if (value instanceof java.util.Date d)
			return d.toInstant();
		if (value instanceof String s)
			try {
				return Instant.parse(s);
			} catch (Exception ignored) {
			}
		return null;
	}
}
