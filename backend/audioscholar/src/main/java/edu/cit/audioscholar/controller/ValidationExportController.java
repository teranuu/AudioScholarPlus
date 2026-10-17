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
public class ValidationExportController {
	private static final Set<String> ALWAYS_REDACT = Set.of("userid", "email", "filename", "fileurl", "storageurl",
			"transcripttext", "transcriptcontent", "tempaudiofilepath", "tempfilepath", "temppptxfilepath",
			"mergedsummary", "sourcefiles", "qualityreport");
	private static final Set<String> TEXT_REDACT = Set.of("text", "content", "formattedsummarytext", "summarytext",
			"front", "back", "title", "description");
	private static final List<String> COLLECTIONS = List.of("audio_metadata", "multiSourceJobs", "sourceFiles",
			"sourceKeyPoints", "summaries", "mergedSummaries", "transcriptSegments", "summaryKeyPoints",
			"qualityReports", "warningMappings", "warningIndicators", "semanticDecisions", "sourceAttributions",
			"crossSourceAlignments", "mergedKeyPointLineage", "multiSourceStageRuns");
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
						issues.add(sanitize(row, includeText));
					}
		datasets.put("qualityIssues", issues);
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("schemaVersion", 2);
		manifest.put("from", from);
		manifest.put("to", to);
		manifest.put("clientSource", clientSource);
		manifest.put("includesReviewerText", includeText);
		manifest.put("objectiveCodes", objectiveCodes == null ? List.of() : objectiveCodes);
		manifest.put("module3ObjectiveCodes", List.of(9, 10, 11, 12, 13, 14));
		manifest.put("schemaVersions", Map.of("validationEvents", 1, "groundTruth", 1, "metrics", 1));
		manifest.put("applicationVersions", distinct(datasets, "applicationVersion"));
		manifest.put("modelVersions", distinct(datasets, "modelVersion"));
		manifest.put("promptVersions", distinct(datasets, "promptVersion"));
		manifest.put("detectorVersions", distinct(datasets, "detectorVersion"));
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
		List<Object> incomplete = new ArrayList<>();
		incomplete.addAll(datasets.get("multiSourceJobs").stream()
				.filter(r -> Boolean.TRUE.equals(r.get("measurementIncomplete"))).map(r -> r.get("jobId")).toList());
		incomplete.addAll(
				datasets.get("audio_metadata").stream().filter(r -> Boolean.TRUE.equals(r.get("measurementIncomplete")))
						.map(r -> r.getOrDefault("id", r.get("recordingId"))).toList());
		manifest.put("measurementIncompleteWorkflows", incomplete);
		manifest.put("missingEvidence", Map.of("incompleteWorkflows", incomplete.size(), "unmappableWarnings",
				datasets.get("warningMappings").stream().filter(r -> "UNMAPPABLE".equals(r.get("decision"))).count(),
				"unresolvedAttributions",
				datasets.get("sourceAttributions").stream().filter(r -> "UNRESOLVED".equals(r.get("attributionStatus"))
						|| "UNRESOLVED".equals(r.get("attributionType"))).count()));
		manifest.put("objectiveEvidenceStatus", module3Statuses(datasets));
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
		List<Map<String, Object>> jobs = datasets.get("multiSourceJobs");
		if (jobs.isEmpty()) {
			for (int code = 9; code <= 14; code++)
				result.put(code, "NO_APPLICABLE_RECORDS");
			return result;
		}
		boolean processingFailed = jobs.stream().anyMatch(j -> "FAILED".equals(j.get("status")));
		boolean incomplete = !integrityIssues(datasets).isEmpty();
		result.put(9, processingFailed ? "PROCESSING_FAILED" : incomplete ? "EXPORT_INCOMPLETE" : "COMPLETE");
		result.put(12, processingFailed ? "PROCESSING_FAILED" : incomplete ? "EXPORT_INCOMPLETE" : "COMPLETE");
		result.put(10,
				reviewerStatus(processingFailed, incomplete,
						datasets.get("sourceKeyPoints").isEmpty() && datasets.get("mergedKeyPointLineage").isEmpty()
								&& completedRunWithCount(datasets, "SOURCE_KEY_POINT_EXTRACTION", "outputCount", 0)
								&& completedRunWithCount(datasets, "DEDUPLICATION", "outputCount", 0)));
		result.put(11, reviewerStatus(processingFailed, incomplete, datasets.get("semanticDecisions").isEmpty()
				&& completedRunWithCount(datasets, "DEDUPLICATION", "candidateCount", 0)));
		result.put(13, reviewerStatus(processingFailed, incomplete, datasets.get("sourceAttributions").isEmpty()
				&& completedRunWithCount(datasets, "ATTRIBUTION", "outputCount", 0)));
		result.put(14, reviewerStatus(processingFailed, incomplete, datasets.get("crossSourceAlignments").isEmpty()
				&& completedRunWithCount(datasets, "ALIGNMENT", "outputCount", 0)));
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
		long exclusions = jobs.stream().filter(j -> "SUBMISSION".equals(j.get("failureStage"))).count();
		long completed = jobs.stream().filter(j -> "COMPLETE".equals(j.get("status"))).count();
		Map<String, Long> failures = jobs.stream().filter(j -> "FAILED".equals(j.get("status")))
				.collect(Collectors.groupingBy(j -> String.valueOf(j.getOrDefault("failureCode", "UNKNOWN")),
						LinkedHashMap::new, Collectors.counting()));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("acceptedJobs", jobs.size());
		result.put("numeratorCompleted", completed);
		result.put("denominatorValid", Math.max(0, jobs.size() - exclusions));
		result.put("excludedSubmissionFailures", exclusions);
		result.put("failureReasons", failures);
		return result;
	}

	private Map<String, Object> module3MergeLatencyMetrics(Map<String, List<Map<String, Object>>> datasets) {
		long[] values = datasets.get("multiSourceJobs").stream().map(j -> j.get("mergeLatencyMs"))
				.filter(Number.class::isInstance).mapToLong(value -> ((Number) value).longValue()).sorted().toArray();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("targetMs", 120_000);
		result.put("measuredJobs", values.length);
		result.put("withinTarget", java.util.Arrays.stream(values).filter(value -> value <= 120_000).count());
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
