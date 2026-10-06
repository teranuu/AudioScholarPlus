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
	private final Map<String, List<JsonNode>> rows = new HashMap<>();
	private final JsonNode truth;
	private final Map<Integer, Map<String, Object>> metrics = new LinkedHashMap<>();
	private ValidationEvaluator(Path export, Path truthFile) throws Exception {
		for (String line : Files.readAllLines(export)) {
			if (line.isBlank())
				continue;
			JsonNode wrapper = JSON.readTree(line);
			rows.computeIfAbsent(wrapper.path("dataset").asText(), ignored -> new ArrayList<>())
					.add(wrapper.path("data"));
		}
		truth = JSON.readTree(Files.readString(truthFile));
		if (truth.path("schemaVersion").asInt() != 1)
			throw new IllegalArgumentException("ground-truth schemaVersion must be 1");
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
	}
	private void evaluate() {
		Set<String> incomplete = new HashSet<>();
		for (JsonNode j : data("multiSourceJobs"))
			if (j.path("measurementIncomplete").asBoolean())
				incomplete.add(j.path("jobId").asText());
		for (JsonNode r : data("audio_metadata"))
			if (r.path("measurementIncomplete").asBoolean())
				incomplete.add(r.path("id").asText(r.path("recordingId").asText()));
		for (JsonNode e : data("events"))
			if ("EVIDENCE_WRITE_FAILED".equals(e.path("eventType").asText()))
				incomplete.add(e.path("workflowId").asText());
		lifecycle(incomplete);
		quality(incomplete);
		warnings(incomplete);
		semantic(incomplete);
		attribution(incomplete);
		alignment(incomplete);
	}
	private void lifecycle(Set<String> incomplete) {
		Map<String, Map<String, Instant>> events = new HashMap<>();
		for (JsonNode e : data("events")) {
			if (!"WEB".equals(e.path("clientSource").asText()))
				continue;
			try {
				events.computeIfAbsent(e.path("workflowId").asText(), ignored -> new HashMap<>())
						.put(e.path("eventType").asText(), Instant.parse(e.path("occurredAt").asText()));
			} catch (Exception x) {
				incomplete.add(e.path("workflowId").asText());
			}
		}
		long accepted = 0, configured = 0, valid = 0, complete = 0, acceptedStage = 0, queuedStage = 0,
				uploadedStage = 0, under = 0, missingLatencyEvidence = 0;
		Map<String, Object> jobs = new LinkedHashMap<>();
		Map<String, Integer> expected = new HashMap<>(), completed = new HashMap<>();
		Map<String, Instant> lastSource = new HashMap<>();
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
			if (e.containsKey("JOB_ACCEPTED") || e.containsKey("CONFIG_CONFIRMED")) {
				accepted++;
				if (e.containsKey("CONFIG_CONFIRMED") && e.containsKey("PROCESSING_STARTED")
						&& e.get("CONFIG_CONFIRMED").isBefore(e.get("PROCESSING_STARTED")))
					configured++;
			}
			if (e.containsKey("BATCH_VALIDATED")) {
				valid++;
				if (e.containsKey("JOB_ACCEPTED"))
					acceptedStage++;
				if (e.containsKey("JOB_QUEUED"))
					queuedStage++;
				if (e.containsKey("ALL_SOURCES_UPLOADED"))
					uploadedStage++;
				if (e.keySet()
						.containsAll(Set.of("JOB_ACCEPTED", "JOB_QUEUED", "ALL_SOURCES_UPLOADED", "JOB_COMPLETED")))
					complete++;
				Map<String, Object> d = new LinkedHashMap<>();
				d.put("expectedSources", expected.get(id));
				d.put("completedSources", completed.getOrDefault(id, 0));
				if (e.containsKey("JOB_COMPLETED") && lastSource.containsKey(id)
						&& e.containsKey("MERGED_SUMMARY_AVAILABLE")
						&& completed.getOrDefault(id, 0) == expected.getOrDefault(id, -1)) {
					long ms = java.time.Duration.between(lastSource.get(id), e.get("MERGED_SUMMARY_AVAILABLE"))
							.toMillis();
					d.put("mergeDurationMs", ms);
					if (ms >= 0 && ms < 120000)
						under++;
				} else if (e.containsKey("JOB_COMPLETED"))
					missingLatencyEvidence++;
				jobs.put(id, d);
			}
		}
		boolean invalid = !incomplete.isEmpty();
		put(2, configured, accepted, 1, invalid, incomplete.size(), incomplete.size(), Map.of("jobs", jobs));
		put(9, complete, valid, 1, invalid, incomplete.size(), incomplete.size(),
				Map.of("stageRates", Map.of("accepted", ratio(acceptedStage, valid), "queued",
						ratio(queuedStage, valid), "allSourcesUploaded", ratio(uploadedStage, valid)), "jobs", jobs));
		put(12, under, complete, 1, invalid || missingLatencyEvidence > 0, incomplete.size() + missingLatencyEvidence,
				incomplete.size() + missingLatencyEvidence, Map.of("limitMsExclusive", 120000, "jobs", jobs));
	}
	private void quality(Set<String> incomplete) {
		List<JsonNode> predicted = data("qualityIssues"), actual = array(truth.path("problemSegments"));
		List<List<Match>> graph = new ArrayList<>();
		for (int i = 0; i < predicted.size(); i++)
			graph.add(new ArrayList<>());
		for (int i = 0; i < predicted.size(); i++)
			for (int j = 0; j < actual.size(); j++) {
				JsonNode p = predicted.get(i), a = actual.get(j);
				if (p.path("recordingId").asText().equals(a.path("recordingId").asText())
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
			}
		boolean invalid = actual.isEmpty() || !validSegments(actual) || !validSegments(predicted)
				|| !incomplete.isEmpty();
		put(3, usedPred.size(), predicted.size(), .8, invalid, incomplete.size(), incomplete.size(), Map.of());
		put(4, usedPred.size(), actual.size(), .75, invalid, incomplete.size(), incomplete.size(), Map.of());
		errors.sort(Long::compare);
		Map<String, Object> diag = new LinkedHashMap<>();
		if (!errors.isEmpty()) {
			diag.put("medianErrorMs", errors.get(errors.size() / 2));
			diag.put("p95ErrorMs", errors.get((int) Math.ceil(.95 * errors.size()) - 1));
			diag.put("maxErrorMs", errors.get(errors.size() - 1));
		}
		put(5, accurate, usedPred.size(), 1, invalid, incomplete.size(), incomplete.size(), diag);
		Set<String> expected = new HashSet<>(), delivered = new HashSet<>();
		for (JsonNode e : data("events")) {
			if (!"WEB".equals(e.path("clientSource").asText()))
				continue;
			String type = e.path("eventType").asText();
			if ("CONFIG_CONFIRMED".equals(type) && "SINGLE_SOURCE".equals(e.path("workflowType").asText()))
				expected.add(e.path("workflowId").asText());
			if ("SOURCE_UPLOADED".equals(type) && "MEDIA".equals(e.path("payload").path("sourceKind").asText()))
				expected.add(e.path("payload").path("sourceFileId").asText());
			if ("QUALITY_REPORT_PERSISTED".equals(type)
					&& Set.of("ALL_CLEAR", "ISSUES_DETECTED").contains(e.path("payload").path("status").asText()))
				delivered.add(e.path("payload").path("recordingId").asText());
		}
		put(6, expected.stream().filter(delivered::contains).count(), expected.size(), 1, !incomplete.isEmpty(),
				incomplete.size(), incomplete.size(), Map.of());
	}
	private void warnings(Set<String> incomplete) {
		Set<String> eligible = new HashSet<>(), warned = new HashSet<>();
		long unmappable = 0;
		for (JsonNode m : data("warningMappings")) {
			String item = m.path("summaryId").asText() + ":" + m.path("itemId").asText();
			if ("OVERLAP".equals(m.path("decision").asText()))
				eligible.add(item);
			if ("UNMAPPABLE".equals(m.path("decision").asText()))
				unmappable++;
		}
		for (JsonNode w : data("warningIndicators")) {
			String item = w.path("keyPointId").asText();
			if (item.isBlank())
				item = w.path("cardId").asText();
			warned.add(w.path("summaryId").asText() + ":" + item);
		}
		put(7, eligible.stream().filter(warned::contains).count(), eligible.size(), .8,
				unmappable > 0 || !incomplete.isEmpty(), incomplete.size() + unmappable, incomplete.size() + unmappable,
				Map.of("unmappable", unmappable));
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
			if (!"SEMANTIC".equals(d.path("decisionMethod").asText()) || !d.path("duplicate").asBoolean())
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
			if (n.path("recordingId").asText().isBlank() || n.path("issueType").asText().isBlank()
					|| !n.path("startMs").canConvertToLong() || !n.path("endMs").canConvertToLong()
					|| n.path("startMs").asLong() < 0 || n.path("endMs").asLong() <= n.path("startMs").asLong())
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
