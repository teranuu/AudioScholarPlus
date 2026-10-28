package edu.cit.audioscholar.validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ValidationEvaluator {
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Set<String> VALID_OUTPUT_TYPES = Set.of("NOTES", "STUDY_MATERIAL", "REVIEW_MATERIAL");
	private final Map<String, List<JsonNode>> rows = new HashMap<>();
	private final JsonNode truth;
	private final Map<Integer, Map<String, Object>> metrics = new LinkedHashMap<>();
	private final List<Map<String, Object>> qualityMatches = new ArrayList<>();
	private ValidationEvaluator(Path export, Path truthFile) throws Exception {
		for (String line : Files.readAllLines(export)) {
			if (line.isBlank())
				continue;
			JsonNode wrapper = JSON.readTree(line);
			rows.computeIfAbsent(wrapper.path("dataset").asText(), ignored -> new ArrayList<>())
					.add(wrapper.path("data"));
		}
		truth = JSON.readTree(Files.readString(truthFile));
		int schemaVersion = truth.path("schemaVersion").asInt();
		if (schemaVersion != 1 && schemaVersion != 2)
			throw new IllegalArgumentException("ground-truth schemaVersion must be 1 or 2");
		if (schemaVersion == 2) {
			JsonNode manifest = data("manifest").stream().findFirst()
					.orElseThrow(() -> new IllegalArgumentException("export manifest missing"));
			if (!manifest.path("exportBindingHash").asText().equals(truth.path("exportManifestHash").asText()))
				throw new IllegalArgumentException("ground truth is not bound to this export manifest");
		}
	}
	public static void main(String[] args) throws Exception {
		if (args.length != 3)
			throw new IllegalArgumentException(
					"Usage: ValidationEvaluator export.jsonl ground-truth.json output-directory");
		ValidationEvaluator e = new ValidationEvaluator(Path.of(args[0]), Path.of(args[1]));
		e.evaluate();
		Path out = Path.of(args[2]);
		Files.createDirectories(out);
		Files.writeString(out.resolve("metrics.json"),
				JSON.writerWithDefaultPrettyPrinter().writeValueAsString(e.metrics));
		StringBuilder csv = new StringBuilder(
				"objective,numerator,denominator,value,target,status,excluded,missingEvidence\n");
		for (var m : e.metrics.entrySet())
			csv.append(m.getKey()).append(',').append(m.getValue().get("numerator")).append(',')
					.append(m.getValue().get("denominator")).append(',').append(m.getValue().get("value")).append(',')
					.append(m.getValue().get("target")).append(',').append(m.getValue().get("status")).append(',')
					.append(m.getValue().get("excludedRecords")).append(',').append(m.getValue().get("missingEvidence"))
					.append('\n');
		Files.writeString(out.resolve("metrics.csv"), csv);
		Files.writeString(out.resolve("quality-matches.json"),
				JSON.writerWithDefaultPrettyPrinter().writeValueAsString(e.qualityMatches));
		StringBuilder matchesCsv = new StringBuilder(
				"result,reportId,detectionIssueId,annotationId,issueType,iou,startErrorMs,endErrorMs\n");
		for (Map<String, Object> row : e.qualityMatches)
			matchesCsv.append(csv(row.get("result"))).append(',').append(csv(row.get("reportId"))).append(',')
					.append(csv(row.get("detectionIssueId"))).append(',').append(csv(row.get("annotationId")))
					.append(',').append(csv(row.get("issueType"))).append(',').append(csv(row.get("iou"))).append(',')
					.append(csv(row.get("startErrorMs"))).append(',').append(csv(row.get("endErrorMs"))).append('\n');
		Files.writeString(out.resolve("quality-matches.csv"), matchesCsv);
	}
	private static String csv(Object value) {
		String text = value == null ? "" : String.valueOf(value);
		return '"' + text.replace("\"", "\"\"") + '"';
	}
	private void evaluate() {
		Set<String> multiSourceIncomplete = new HashSet<>();
		Set<String> configurationIncomplete = new HashSet<>();
		for (JsonNode j : data("multiSourceJobs"))
			if (j.path("measurementIncomplete").asBoolean()) {
				multiSourceIncomplete.add(j.path("jobId").asText());
				configurationIncomplete.add(j.path("jobId").asText());
			}
		for (JsonNode metadata : data("audio_metadata"))
			if (metadata.path("measurementIncomplete").asBoolean())
				configurationIncomplete.add(metadata.path("id").asText(metadata.path("recordingId").asText()));
		for (JsonNode e : data("events"))
			if ("EVIDENCE_WRITE_FAILED".equals(e.path("eventType").asText())) {
				String workflowId = e.path("workflowId").asText();
				configurationIncomplete.add(workflowId);
				if ("MULTI_SOURCE".equals(e.path("workflowType").asText()))
					multiSourceIncomplete.add(workflowId);
			}
		lifecycle(configurationIncomplete, multiSourceIncomplete);
		quality();
		warnings();
		semantic(multiSourceIncomplete);
		attribution(multiSourceIncomplete);
		alignment(multiSourceIncomplete);
	}
	private void lifecycle(Set<String> configurationIncomplete, Set<String> multiSourceIncomplete) {
		Map<String, Map<String, Instant>> events = new HashMap<>();
		Map<String, List<JsonNode>> workflowEvents = new HashMap<>();
		for (JsonNode e : data("events")) {
			if (!"WEB".equals(e.path("clientSource").asText()))
				continue;
			String workflowId = e.path("workflowId").asText();
			workflowEvents.computeIfAbsent(workflowId, ignored -> new ArrayList<>()).add(e);
			try {
				events.computeIfAbsent(workflowId, ignored -> new HashMap<>()).merge(e.path("eventType").asText(),
						Instant.parse(e.path("occurredAt").asText()), (a, b) -> a.isBefore(b) ? a : b);
			} catch (Exception x) {
				configurationIncomplete.add(workflowId);
				if ("MULTI_SOURCE".equals(e.path("workflowType").asText()))
					multiSourceIncomplete.add(workflowId);
			}
		}
		long accepted = 0, configured = 0, valid = 0, complete = 0, acceptedStage = 0, queuedStage = 0,
				uploadedStage = 0, completedValidJobs = 0, under = 0, missingLatencyEvidence = 0,
				invalidLifecycleEvidence = 0;
		Map<String, Object> jobs = new LinkedHashMap<>();
		Map<String, Object> configurationWorkflows = new LinkedHashMap<>();
		Map<String, Integer> expected = new HashMap<>(), completed = new HashMap<>();
		Map<String, Instant> lastSource = new HashMap<>();
		Set<String> validBatchIds = new HashSet<>();
		for (JsonNode j : data("multiSourceJobs"))
			expected.put(j.path("jobId").asText(), j.path("sourceCount").asInt(j.path("sourceFiles").size()));
		for (JsonNode s : data("sourceFiles"))
			try {
				String id = s.path("jobId").asText();
				Instant at = Instant.parse(s.path("transcriptionCompletedAt").asText());
				completed.merge(id, 1, Integer::sum);
				lastSource.merge(id, at, (a, b) -> a.isAfter(b) ? a : b);
			} catch (Exception ignored) {
			}
		for (var entry : events.entrySet()) {
			String id = entry.getKey();
			Map<String, Instant> e = entry.getValue();
			if (e.containsKey("JOB_ACCEPTED") || e.containsKey("CONFIG_CONFIRMED")
					|| e.containsKey("PROCESSING_STARTED")) {
				accepted++;
				JsonNode config = workflowEvents.getOrDefault(id, List.of()).stream()
						.filter(event -> "CONFIG_CONFIRMED".equals(event.path("eventType").asText()))
						.filter(event -> VALID_OUTPUT_TYPES.contains(event.path("payload").path("outputType").asText()))
						.filter(event -> eventTime(event) != null)
						.filter(event -> !e.containsKey("PROCESSING_STARTED")
								|| eventTime(event).isBefore(e.get("PROCESSING_STARTED")))
						.min(Comparator.comparing(ValidationEvaluator::eventTime)).orElse(null);
				if (config != null)
					configured++;
				Map<String, Object> diagnostic = new LinkedHashMap<>();
				diagnostic.put("configurationValid", config != null);
				diagnostic.put("processingStarted", e.containsKey("PROCESSING_STARTED"));
				if (config != null)
					diagnostic.put("outputType", config.path("payload").path("outputType").asText());
				else
					diagnostic.put("reason", configurationFailureReason(workflowEvents.getOrDefault(id, List.of()), e));
				configurationWorkflows.put(id, diagnostic);
			}
			if (e.containsKey("BATCH_VALIDATED")) {
				validBatchIds.add(id);
				valid++;
				if (e.containsKey("JOB_ACCEPTED"))
					acceptedStage++;
				if (e.containsKey("JOB_QUEUED"))
					queuedStage++;
				if (e.containsKey("ALL_SOURCES_UPLOADED"))
					uploadedStage++;
				long terminalCount = workflowEvents.getOrDefault(id, List.of()).stream().filter(
						event -> Set.of("JOB_COMPLETED", "JOB_FAILED").contains(event.path("eventType").asText()))
						.count();
				boolean sourceCountValid = expected.getOrDefault(id, -1) >= 2 && expected.getOrDefault(id, -1) <= 5;
				boolean allSourcesCompleted = completed.getOrDefault(id, 0) == expected.getOrDefault(id, -1);
				boolean lifecycleOrdered = ordered(e, "BATCH_VALIDATED", "JOB_ACCEPTED", "ALL_SOURCES_UPLOADED",
						"JOB_QUEUED", "PROCESSING_STARTED", "JOB_COMPLETED");
				boolean lifecycleComplete = sourceCountValid && allSourcesCompleted && terminalCount == 1
						&& lifecycleOrdered;
				if (lifecycleComplete)
					complete++;
				else if (e.containsKey("JOB_COMPLETED"))
					invalidLifecycleEvidence++;
				Map<String, Object> d = new LinkedHashMap<>();
				d.put("expectedSources", expected.get(id));
				d.put("completedSources", completed.getOrDefault(id, 0));
				d.put("terminalEventCount", terminalCount);
				d.put("sourceCountValid", sourceCountValid);
				d.put("allSourcesCompleted", allSourcesCompleted);
				d.put("lifecycleOrdered", lifecycleOrdered);
				if (e.containsKey("JOB_COMPLETED") && terminalCount == 1) {
					completedValidJobs++;
					if (lastSource.containsKey(id) && e.containsKey("MERGED_SUMMARY_AVAILABLE")
							&& completed.getOrDefault(id, 0) == expected.getOrDefault(id, -1)) {
						long ms = java.time.Duration.between(lastSource.get(id), e.get("MERGED_SUMMARY_AVAILABLE"))
								.toMillis();
						d.put("mergeDurationMs", ms);
						if (ms >= 0 && ms < 120000)
							under++;
					} else
						missingLatencyEvidence++;
				}
				jobs.put(id, d);
			}
		}
		boolean configurationInvalid = !configurationIncomplete.isEmpty();
		long relevantIncomplete = multiSourceIncomplete.stream().filter(validBatchIds::contains).count();
		boolean multiSourceInvalid = relevantIncomplete > 0;
		put(2, configured, accepted, 1, configurationInvalid, configurationIncomplete.size(),
				configurationIncomplete.size(), Map.of("workflows", configurationWorkflows));
		put(9, complete, valid, 1, multiSourceInvalid || invalidLifecycleEvidence > 0,
				relevantIncomplete + invalidLifecycleEvidence, relevantIncomplete + invalidLifecycleEvidence, Map.of(
						"stageRates", Map.of("accepted", ratio(acceptedStage, valid), "queued",
								ratio(queuedStage, valid), "allSourcesUploaded", ratio(uploadedStage, valid)),
						"jobs", jobs));
		put(12, under, completedValidJobs, 1,
				multiSourceInvalid || missingLatencyEvidence > 0 || invalidLifecycleEvidence > 0,
				relevantIncomplete + missingLatencyEvidence + invalidLifecycleEvidence,
				relevantIncomplete + missingLatencyEvidence + invalidLifecycleEvidence,
				Map.of("limitMsExclusive", 120000, "jobs", jobs));
	}

	private static Instant eventTime(JsonNode event) {
		try {
			return Instant.parse(event.path("occurredAt").asText());
		} catch (Exception ignored) {
			return null;
		}
	}

	private static String configurationFailureReason(List<JsonNode> workflowEvents, Map<String, Instant> eventTimes) {
		List<JsonNode> configurations = workflowEvents.stream()
				.filter(event -> "CONFIG_CONFIRMED".equals(event.path("eventType").asText())).toList();
		if (configurations.isEmpty())
			return "CONFIG_CONFIRMED_MISSING";
		if (configurations.stream()
				.noneMatch(event -> VALID_OUTPUT_TYPES.contains(event.path("payload").path("outputType").asText())))
			return "OUTPUT_TYPE_INVALID_OR_MISSING";
		if (eventTimes.containsKey("PROCESSING_STARTED"))
			return "CONFIG_NOT_BEFORE_PROCESSING";
		return "CONFIG_TIMESTAMP_INVALID";
	}
	private void quality() {
		boolean legacy = truth.path("schemaVersion").asInt() == 1;
		Set<String> eligibleReports = new HashSet<>();
		for (JsonNode report : data("qualityReports"))
			if ("COMPLETED".equals(report.path("analysisStatus").asText())
					&& "FULL_PCM".equals(report.path("analysisMode").asText()))
				eligibleReports.add(report.path("reportId").asText());
		Set<String> reviewedReports = new HashSet<>();
		for (JsonNode review : array(truth.path("reviewedRecordings")))
			if (review.path("reviewComplete").asBoolean())
				reviewedReports.add(review.path("reportId").asText());
		List<JsonNode> predicted = data("qualityIssues").stream()
				.filter(p -> legacy || reviewedReports.contains(p.path("reportId").asText())
						&& eligibleReports.contains(p.path("reportId").asText()))
				.sorted(segmentOrder("issueId")).toList();
		List<JsonNode> actual = array(truth.path("problemSegments")).stream()
				.filter(a -> legacy || reviewedReports.contains(a.path("reportId").asText())
						&& eligibleReports.contains(a.path("reportId").asText()))
				.sorted(segmentOrder("annotationId")).toList();
		List<List<Match>> graph = new ArrayList<>();
		for (int i = 0; i < predicted.size(); i++)
			graph.add(new ArrayList<>());
		for (int i = 0; i < predicted.size(); i++)
			for (int j = 0; j < actual.size(); j++) {
				JsonNode p = predicted.get(i), a = actual.get(j);
				String joinField = legacy ? "recordingId" : "reportId";
				if (p.path(joinField).asText().equals(a.path(joinField).asText())
						&& p.path("issueType").asText().equals(a.path("issueType").asText())) {
					double score = iou(p.path("startMs").asLong(-1), p.path("endMs").asLong(-1),
							a.path("startMs").asLong(-1), a.path("endMs").asLong(-1));
					if (score >= .5)
						graph.get(i).add(new Match(i, j, score));
				}
			}
		graph.forEach(list -> list.sort(Comparator.comparingDouble(Match::score).reversed()));
		int[] matched = new int[actual.size()];
		Arrays.fill(matched, -1);
		for (int i = 0; i < predicted.size(); i++)
			augment(i, new boolean[actual.size()], graph, matched);
		Set<Integer> usedPred = new HashSet<>();
		long accurate = 0;
		List<Long> errors = new ArrayList<>();
		for (int a = 0; a < matched.length; a++)
			if (matched[a] >= 0) {
				usedPred.add(matched[a]);
				JsonNode p = predicted.get(matched[a]), t = actual.get(a);
				long se = Math.abs(p.path("startMs").asLong() - t.path("startMs").asLong()),
						ee = Math.abs(p.path("endMs").asLong() - t.path("endMs").asLong());
				errors.add(Math.max(se, ee));
				if (se <= 10000 && ee <= 10000)
					accurate++;
				int actualIndex = a;
				qualityMatches.add(matchRow("TP", p, t, graph.get(matched[a]).stream()
						.filter(m -> m.actual() == actualIndex).map(Match::score).findFirst().orElse(0.0), se, ee));
			}
		for (int i = 0; i < predicted.size(); i++)
			if (!usedPred.contains(i))
				qualityMatches.add(matchRow("FP", predicted.get(i), null, 0, null, null));
		for (int i = 0; i < actual.size(); i++)
			if (matched[i] < 0)
				qualityMatches.add(matchRow("FN", null, actual.get(i), 0, null, null));
		long ineligibleReviews = legacy
				? 0
				: reviewedReports.stream().filter(r -> !eligibleReports.contains(r)).count();
		boolean invalid = (!legacy && reviewedReports.isEmpty()) || ineligibleReviews > 0 || !validSegments(actual)
				|| !validSegments(predicted);
		Map<String, Object> reviewDiagnostics = Map.of("ineligibleReviewedReports", ineligibleReviews);
		put(3, usedPred.size(), predicted.size(), .8, invalid, ineligibleReviews, ineligibleReviews, reviewDiagnostics);
		put(4, usedPred.size(), actual.size(), .75, invalid, ineligibleReviews, ineligibleReviews, reviewDiagnostics);
		errors.sort(Long::compare);
		Map<String, Object> diag = new LinkedHashMap<>();
		if (!errors.isEmpty()) {
			diag.put("medianErrorMs", errors.get(errors.size() / 2));
			diag.put("p95ErrorMs", errors.get((int) Math.ceil(.95 * errors.size()) - 1));
			diag.put("maxErrorMs", errors.get(errors.size() - 1));
		}
		diag.put("ineligibleReviewedReports", ineligibleReviews);
		put(5, accurate, usedPred.size(), 1, invalid, ineligibleReviews, ineligibleReviews, diag);
		Set<String> expected = new HashSet<>(), delivered = new HashSet<>();
		Set<String> startedMultiSourceWorkflows = new HashSet<>();
		for (JsonNode e : data("events")) {
			if (!"WEB".equals(e.path("clientSource").asText()))
				continue;
			String type = e.path("eventType").asText();
			if ("PROCESSING_STARTED".equals(type) && "SINGLE_SOURCE".equals(e.path("workflowType").asText()))
				expected.add("SINGLE_SOURCE:"
						+ e.path("payload").path("attemptId").asText(e.path("idempotencyKey").asText()));
			if ("PROCESSING_STARTED".equals(type) && "MULTI_SOURCE".equals(e.path("workflowType").asText()))
				startedMultiSourceWorkflows.add(e.path("workflowId").asText());
		}
		for (JsonNode source : data("sourceFiles"))
			if (startedMultiSourceWorkflows.contains(source.path("jobId").asText())
					&& "MEDIA".equals(source.path("sourceKind").asText()))
				expected.add("MULTI_SOURCE:" + source.path("sourceFileId").asText());
		for (JsonNode report : data("qualityReports")) {
			String status = report.path("status").asText();
			boolean validStatus = Set.of("ALL_CLEAR", "ISSUES_DETECTED").contains(status);
			boolean validIdentity = !report.path("reportId").asText().isBlank()
					&& !report.path("attemptId").asText().isBlank();
			boolean validAllClear = !"ALL_CLEAR".equals(status)
					|| "COMPLETED".equals(report.path("analysisStatus").asText())
							&& "FULL_PCM".equals(report.path("analysisMode").asText());
			if (validStatus && validIdentity && validAllClear) {
				delivered.add("SINGLE_SOURCE:" + report.path("attemptId").asText());
				delivered.add("MULTI_SOURCE:" + report.path("recordingId").asText());
			}
		}
		long blankAttempts = expected.stream().filter(value -> value.endsWith(":")).count();
		long rejectedBeforeProcessing = data("events").stream()
				.filter(e -> "WEB".equals(e.path("clientSource").asText())
						&& "SINGLE_SOURCE".equals(e.path("workflowType").asText())
						&& "REQUEST_REJECTED".equals(e.path("eventType").asText()))
				.count();
		expected.removeIf(value -> value.endsWith(":"));
		delivered.removeIf(value -> value.endsWith(":"));
		put(6, expected.stream().filter(delivered::contains).count(), expected.size(), 1, blankAttempts > 0, 0,
				blankAttempts, Map.of("rejectedBeforeProcessing", rejectedBeforeProcessing, "expectedDeliveries",
						expected, "multiSourceWorkflows", startedMultiSourceWorkflows));
	}
	private Map<String, Object> matchRow(String result, JsonNode prediction, JsonNode annotation, double iou,
			Long startError, Long endError) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("result", result);
		JsonNode source = prediction != null ? prediction : annotation;
		row.put("reportId", source.path("reportId").asText());
		row.put("detectionIssueId", prediction == null ? null : prediction.path("issueId").asText());
		row.put("annotationId", annotation == null ? null : annotation.path("annotationId").asText());
		row.put("issueType", source.path("issueType").asText());
		row.put("iou", iou);
		row.put("startErrorMs", startError);
		row.put("endErrorMs", endError);
		return row;
	}
	private void warnings() {
		Set<String> eligibleIssues = new HashSet<>(), overlapIssues = new HashSet<>(), warnedIssues = new HashSet<>();
		long invalidMappings = 0;
		for (JsonNode m : data("warningMappings")) {
			if ("LEGACY_READ_BACKFILL".equals(m.path("evidenceSource").asText()) || m.path("stale").asBoolean())
				continue;
			String issueId = m.path("issueId").asText();
			String decision = m.path("decision").asText();
			if (issueId.isBlank()) {
				invalidMappings++;
				continue;
			}
			if ("OVERLAP".equals(decision)) {
				eligibleIssues.add(issueId);
				overlapIssues.add(issueId);
			} else if ("UNMAPPABLE".equals(decision))
				eligibleIssues.add(issueId);
		}
		Set<String> visible = new HashSet<>();
		for (JsonNode receipt : data("warningDisplayReceipts"))
			if ("INDICATOR_VISIBLE".equals(receipt.path("displayState").asText()) && !receipt.path("stale").asBoolean())
				visible.add(receipt.path("warningId").asText() + ":" + receipt.path("summaryEvidenceVersion").asText());
		for (JsonNode w : data("warningIndicators")) {
			String receiptKey = w.path("warningId").asText() + ":" + w.path("summaryEvidenceVersion").asText();
			if (visible.contains(receiptKey) && !w.path("stale").asBoolean()
					&& "DISPLAYABLE".equals(w.path("generationStatus").asText())
					&& !"LEGACY_READ_BACKFILL".equals(w.path("evidenceSource").asText())
					&& !w.path("issueId").asText().isBlank())
				warnedIssues.add(w.path("issueId").asText());
		}
		boolean staleSummary = data("summaries").stream().anyMatch(s -> s.path("warningEvidenceStale").asBoolean());
		Set<String> unmappableOnly = new HashSet<>(eligibleIssues);
		unmappableOnly.removeAll(overlapIssues);
		put(7, eligibleIssues.stream().filter(warnedIssues::contains).count(), eligibleIssues.size(), .8,
				invalidMappings > 0 || staleSummary, invalidMappings, invalidMappings,
				Map.of("measurementUnit", "UNIQUE_QUALITY_ISSUE", "overlapIssues", overlapIssues.size(),
						"unmappableOnlyIssues", unmappableOnly.size(), "unmappablePolicy", "COUNT_AS_UNCOVERED",
						"staleSummaryEvidence", staleSummary));
		Map<String, Boolean> expert = new HashMap<>();
		for (JsonNode review : array(truth.path("warningTemplateReviews")))
			expert.put(review.path("templateId").asText() + ":" + review.path("templateVersion").asText(),
					review.path("reviewerConforms").asBoolean());
		long candidates = 0, conforming = 0, missingReview = 0;
		for (JsonNode candidate : data("warningCandidates")) {
			if ("LEGACY_READ_BACKFILL".equals(candidate.path("evidenceSource").asText())
					|| candidate.path("stale").asBoolean())
				continue;
			candidates++;
			Boolean review = expert
					.get(candidate.path("templateId").asText() + ":" + candidate.path("templateVersion").asText());
			if (review == null)
				missingReview++;
			else if (review && candidate.path("automatedConformancePassed").asBoolean())
				conforming++;
		}
		put(8, conforming, candidates, 1, candidates == 0 || missingReview > 0 || staleSummary, missingReview,
				missingReview, Map.of("missingExpertReviews", missingReview, "staleSummaryEvidence", staleSummary));
	}
	private static String warningItem(JsonNode node) {
		String item = node.path("itemId").asText();
		if (item.isBlank())
			item = node.path("keyPointId").asText();
		if (item.isBlank())
			item = node.path("cardId").asText();
		return node.path("summaryId").asText() + ":" + node.path("summaryEvidenceVersion").asText() + ":" + item;
	}
	private static Comparator<JsonNode> segmentOrder(String idField) {
		return Comparator.comparing((JsonNode n) -> n.path("reportId").asText())
				.thenComparing(n -> n.path("issueType").asText()).thenComparingLong(n -> n.path("startMs").asLong())
				.thenComparingLong(n -> n.path("endMs").asLong()).thenComparing(n -> n.path(idField).asText());
	}
	private void semantic(Set<String> incomplete) {
		Map<String, Boolean> reviewed = new HashMap<>();
		boolean malformed = false;
		for (JsonNode r : array(truth.path("duplicatePairs"))) {
			if (r.path("decisionId").asText().isBlank() || !r.path("duplicate").isBoolean())
				malformed = true;
			reviewed.put(r.path("decisionId").asText(), r.path("duplicate").asBoolean());
		}
		long denominator = 0, correct = 0, missing = 0;
		for (JsonNode d : data("semanticDecisions")) {
			if (!Set.of("SEMANTIC", "SEMANTIC_BATCH").contains(d.path("decisionMethod").asText())
					|| !d.path("duplicate").asBoolean())
				continue;
			denominator++;
			Boolean value = reviewed.get(d.path("decisionId").asText());
			if (value == null)
				missing++;
			else if (value)
				correct++;
		}
		put(11, correct, denominator, .85, malformed || missing > 0 || !incomplete.isEmpty(),
				missing + incomplete.size(), missing + incomplete.size(), Map.of());
	}
	private void attribution(Set<String> incomplete) {
		Map<String, String> expected = new HashMap<>();
		boolean malformed = false;
		for (JsonNode r : array(truth.path("sourceLabels"))) {
			if (r.path("keyPointId").asText().isBlank() || r.path("sourceLabel").asText().isBlank())
				malformed = true;
			expected.put(r.path("keyPointId").asText(), r.path("sourceLabel").asText());
		}
		long found = 0, correct = 0;
		for (JsonNode r : data("sourceAttributions")) {
			String label = expected.remove(r.path("keyPointId").asText());
			if (label == null)
				continue;
			found++;
			if ("SINGLE_SOURCE".equals(r.path("attributionType").asText())
					&& label.equals(r.path("sourceLabel").asText()))
				correct++;
		}
		put(13, correct, found + expected.size(), 1, malformed || !expected.isEmpty() || !incomplete.isEmpty(),
				expected.size() + incomplete.size(), expected.size() + incomplete.size(),
				Map.of("missingAttributions", expected.keySet()));
	}
	private void alignment(Set<String> incomplete) {
		Map<String, JsonNode> expected = new HashMap<>();
		boolean malformed = false;
		for (JsonNode r : array(truth.path("overlapBoundaries"))) {
			if (r.path("sourcePairId").asText().isBlank() || !boundary(r, "sourceA") || !boundary(r, "sourceB"))
				malformed = true;
			expected.put(r.path("sourcePairId").asText(), r);
		}
		int denominator = expected.size();
		long accurate = 0;
		for (JsonNode r : data("crossSourceAlignments")) {
			JsonNode t = expected.get(r.path("sourcePairId").asText());
			if (t == null)
				continue;
			expected.remove(r.path("sourcePairId").asText());
			if (iou(r.path("sourceAStartMs").asLong(-1), r.path("sourceAEndMs").asLong(-1),
					t.path("sourceAStartMs").asLong(-1), t.path("sourceAEndMs").asLong(-1)) < .5
					|| iou(r.path("sourceBStartMs").asLong(-1), r.path("sourceBEndMs").asLong(-1),
							t.path("sourceBStartMs").asLong(-1), t.path("sourceBEndMs").asLong(-1)) < .5)
				continue;
			if (close(r, t, "sourceA") && close(r, t, "sourceB"))
				accurate++;
		}
		put(14, accurate, denominator, 1, malformed || !expected.isEmpty() || !incomplete.isEmpty(),
				expected.size() + incomplete.size(), expected.size() + incomplete.size(), Map.of());
	}
	private void put(int objective, long numerator, long denominator, double target, boolean invalid, long excluded,
			long missing, Map<String, ?> diagnostics) {
		double value = denominator == 0 ? 0 : (double) numerator / denominator;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("numerator", numerator);
		m.put("denominator", denominator);
		m.put("value", denominator == 0 ? null : value);
		m.put("target", target);
		m.put("status", invalid || denominator == 0 ? "NOT_EVALUABLE" : value >= target ? "PASS" : "FAIL");
		m.put("excludedRecords", excluded);
		m.put("missingEvidence", missing);
		m.put("diagnostics", diagnostics);
		metrics.put(objective, m);
	}
	private List<JsonNode> data(String name) {
		return rows.getOrDefault(name, List.of());
	}
	private static List<JsonNode> array(JsonNode n) {
		if (!n.isArray())
			return List.of();
		List<JsonNode> r = new ArrayList<>();
		n.forEach(r::add);
		return r;
	}
	private static double ratio(long n, long d) {
		return d == 0 ? 0 : (double) n / d;
	}
	private static boolean ordered(Map<String, Instant> e, String... names) {
		Instant last = null;
		for (String n : names) {
			Instant at = e.get(n);
			if (at == null || last != null && at.isBefore(last))
				return false;
			last = at;
		}
		return true;
	}
	private static boolean augment(int p, boolean[] seen, List<List<Match>> g, int[] match) {
		for (Match m : g.get(p)) {
			if (seen[m.actual])
				continue;
			seen[m.actual] = true;
			if (match[m.actual] < 0 || augment(match[m.actual], seen, g, match)) {
				match[m.actual] = p;
				return true;
			}
		}
		return false;
	}
	private static boolean validSegments(List<JsonNode> s) {
		for (JsonNode n : s)
			if ((n.path("reportId").asText().isBlank() && n.path("recordingId").asText().isBlank())
					|| n.path("issueType").asText().isBlank() || !n.path("startMs").canConvertToLong()
					|| !n.path("endMs").canConvertToLong() || n.path("startMs").asLong() < 0
					|| n.path("endMs").asLong() <= n.path("startMs").asLong())
				return false;
		return true;
	}
	private static double iou(long as, long ae, long bs, long be) {
		if (as < 0 || bs < 0 || ae <= as || be <= bs)
			return 0;
		long overlap = Math.max(0, Math.min(ae, be) - Math.max(as, bs));
		return (double) overlap / (Math.max(ae, be) - Math.min(as, bs));
	}
	private static boolean boundary(JsonNode n, String p) {
		return n.path(p + "StartMs").canConvertToLong() && n.path(p + "EndMs").canConvertToLong()
				&& n.path(p + "StartMs").asLong() >= 0 && n.path(p + "EndMs").asLong() > n.path(p + "StartMs").asLong();
	}
	private static boolean close(JsonNode a, JsonNode b, String p) {
		return Math.abs(a.path(p + "StartMs").asLong() - b.path(p + "StartMs").asLong()) <= 15000
				&& Math.abs(a.path(p + "EndMs").asLong() - b.path(p + "EndMs").asLong()) <= 15000;
	}
	private record Match(int predicted, int actual, double score) {
	}
}
