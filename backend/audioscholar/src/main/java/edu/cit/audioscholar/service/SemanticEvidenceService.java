package edu.cit.audioscholar.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.model.Flashcard;
import edu.cit.audioscholar.model.MultiSourceJob;
import edu.cit.audioscholar.model.SourceAttribution;
import edu.cit.audioscholar.model.SourceFile;
import edu.cit.audioscholar.model.Summary;
import edu.cit.audioscholar.model.SummaryKeyPoint;

@Service
public class SemanticEvidenceService {
	private final GeminiService gemini;
	private final FirebaseService firebase;
	private final ObjectMapper json;
	private final ValidationEventService events;
	private final double threshold;
	public SemanticEvidenceService(GeminiService gemini, FirebaseService firebase,
			@Qualifier("objectMapper") ObjectMapper json, ValidationEventService events,
			@Value("${validation.semantic-duplicate-threshold:0.80}") double threshold) {
		this.gemini = gemini;
		this.firebase = firebase;
		this.json = json;
		this.events = events;
		this.threshold = threshold;
	}

	public List<SourceAttribution> evaluate(MultiSourceJob job, Summary summary) throws Exception {
		List<Point> points = new ArrayList<>();
		Map<String, Map<String, Object>> links = new HashMap<>();
		boolean incomplete = false;
		for (SourceFile source : job.getSourceFiles()) {
			JsonNode root = parse(gemini.generateTranscriptOnlySummary(source.getTranscriptText(),
					source.getSourceFileId(), job.getOutputType()));
			if (!root.path("keyPoints").isArray()) {
				incomplete = true;
				continue;
			}
			List<Map<String, Object>> segments = firebase.queryCollection("transcriptSegments", "sourceFileId",
					source.getSourceFileId());
			int index = 0;
			for (JsonNode node : root.path("keyPoints")) {
				String text = node.asText("").trim();
				if (text.isBlank())
					continue;
				String id = ValidationEventService
						.digest(job.getJobId() + ":" + source.getSourceFileId() + ":" + index++ + ":" + text);
				Point point = new Point(id, source.getSourceFileId(), source.getSourceLabel(), text);
				points.add(point);
				Map<String, Object> best = bestSegment(text, segments);
				Map<String, Object> artifact = new LinkedHashMap<>();
				artifact.put("keyPointId", id);
				artifact.put("jobId", job.getJobId());
				artifact.put("sourceFileId", source.getSourceFileId());
				artifact.put("sourceLabel", source.getSourceLabel());
				artifact.put("text", text);
				artifact.put("contentHash", ValidationEventService.digest(text));
				artifact.put("segmentId", best == null ? null : best.get("segmentId"));
				firebase.saveData("sourceKeyPoints", id, artifact);
				if (best == null)
					incomplete = true;
				else
					links.put(id, best);
			}
		}
		int[] parent = new int[points.size()];
		for (int i = 0; i < parent.length; i++)
			parent[i] = i;
		List<Decision> decisions = new ArrayList<>();
		Map<String, List<Anchor>> anchors = new HashMap<>();
		for (int i = 0; i < points.size(); i++)
			for (int j = i + 1; j < points.size(); j++) {
				Point a = points.get(i), b = points.get(j);
				if (a.sourceId.equals(b.sourceId))
					continue;
				boolean exact = normalize(a.text).equals(normalize(b.text));
				String method = exact ? "EXACT_FALLBACK" : "SEMANTIC";
				double confidence;
				try {
					confidence = exact ? 1 : classify(a.text, b.text);
				} catch (Exception e) {
					confidence = 0;
					method = "SEMANTIC_UNRESOLVED";
					incomplete = true;
				}
				boolean duplicate = confidence >= threshold;
				if (duplicate)
					parent[find(parent, j)] = find(parent, i);
				if (duplicate && links.containsKey(a.id) && links.containsKey(b.id))
					anchors.computeIfAbsent(a.sourceId + ":" + b.sourceId, ignored -> new ArrayList<>())
							.add(new Anchor(links.get(a.id), links.get(b.id), confidence));
				decisions.add(new Decision(i, a, b, confidence, duplicate, method));
			}
		for (Decision d : decisions)
			persistDecision(job, d, points.get(find(parent, d.leftIndex)).id);
		persistAlignments(job, anchors);
		Map<Integer, List<Point>> clusters = new LinkedHashMap<>();
		for (int i = 0; i < points.size(); i++)
			clusters.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(points.get(i));
		List<SummaryKeyPoint> structured = new ArrayList<>();
		List<SourceAttribution> attributions = new ArrayList<>();
		for (List<Point> cluster : clusters.values()) {
			Point representative = cluster.stream().filter(p -> links.containsKey(p.id)).findFirst()
					.orElse(cluster.get(0));
			SummaryKeyPoint kp = new SummaryKeyPoint();
			kp.setKeyPointId(representative.id);
			kp.setSummaryId(summary.getSummaryId());
			kp.setText(representative.text);
			kp.setSourceFileId(representative.sourceId);
			Map<String, Object> segment = links.get(representative.id);
			if (segment != null) {
				kp.setSourceSegmentId((String) segment.get("segmentId"));
				kp.setSourceStartTime(time(ms(segment, "startMs")));
				kp.setSourceEndTime(time(ms(segment, "endMs")));
			}
			structured.add(kp);
			Set<String> sourceIds = new HashSet<>();
			cluster.forEach(p -> sourceIds.add(p.sourceId));
			SourceAttribution attribution = new SourceAttribution();
			attribution.setKeyPointId(representative.id);
			if (segment == null) {
				attribution.setAttributionType("UNRESOLVED");
				incomplete = true;
			} else {
				attribution.setAttributionType(sourceIds.size() == 1 ? "SINGLE_SOURCE" : "MULTI_SOURCE_CONSENSUS");
				attribution.setSourceLabel(sourceIds.size() == 1 ? representative.sourceLabel : "Multiple Sources");
			}
			attributions.add(attribution);
		}
		summary.setSummaryKeyPoints(structured);
		summary.setKeyPoints(structured.stream().map(SummaryKeyPoint::getText).toList());
		for (Flashcard card : summary.getFlashcards()) {
			Map<String, Object> best = bestSegment(card.getBack(), links.values().stream().toList());
			if (best != null) {
				card.setSourceSegmentId((String) best.get("segmentId"));
				card.setSourceFileId((String) best.get("sourceFileId"));
				card.setSourceStartTime(time(ms(best, "startMs")));
				card.setSourceEndTime(time(ms(best, "endMs")));
			} else
				incomplete = true;
		}
		if (incomplete)
			job.setMeasurementIncomplete(true);
		return attributions;
	}

	private void persistDecision(MultiSourceJob job, Decision d, String clusterId) {
		String id = ValidationEventService.digest(job.getJobId() + ":" + d.a.id + ":" + d.b.id);
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("decisionId", id);
		row.put("jobId", job.getJobId());
		row.put("leftKeyPointId", d.a.id);
		row.put("rightKeyPointId", d.b.id);
		row.put("leftSourceFileId", d.a.sourceId);
		row.put("rightSourceFileId", d.b.sourceId);
		row.put("confidence", d.confidence);
		row.put("duplicate", d.duplicate);
		row.put("threshold", threshold);
		row.put("decisionMethod", d.method);
		row.put("modelVersion", "gemini-simple-text");
		row.put("promptVersion", "semantic-pair-v1");
		row.put("clusterId", d.duplicate ? clusterId : null);
		firebase.saveData("semanticDecisions", id, row);
		events.emit(job.getJobId(), "MULTI_SOURCE", job.getClientSource(), "SEMANTIC_PAIR_DECIDED", List.of(11), id,
				Map.of("decisionId", id, "duplicate", d.duplicate, "confidence", d.confidence, "modelVersion",
						"gemini-simple-text", "promptVersion", "semantic-pair-v1"),
				"multiSourceJobs");
	}
	private void persistAlignments(MultiSourceJob job, Map<String, List<Anchor>> anchors) {
		for (var entry : anchors.entrySet()) {
			List<Anchor> list = entry.getValue();
			if (list.size() < 2)
				continue;
			long as = list.stream().mapToLong(a -> ms(a.left, "startMs")).min().orElse(0),
					ae = list.stream().mapToLong(a -> ms(a.left, "endMs")).max().orElse(0),
					bs = list.stream().mapToLong(a -> ms(a.right, "startMs")).min().orElse(0),
					be = list.stream().mapToLong(a -> ms(a.right, "endMs")).max().orElse(0);
			String id = ValidationEventService.digest(job.getJobId() + ":" + entry.getKey());
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("alignmentId", id);
			row.put("jobId", job.getJobId());
			row.put("sourcePairId", entry.getKey());
			row.put("sourceAStartMs", as);
			row.put("sourceAEndMs", ae);
			row.put("sourceBStartMs", bs);
			row.put("sourceBEndMs", be);
			row.put("inferredOffsetMs", as - bs);
			row.put("anchorCount", list.size());
			row.put("confidence", list.stream().mapToDouble(Anchor::confidence).average().orElse(0));
			row.put("algorithmVersion", "ordered-semantic-anchors-v1");
			firebase.saveData("crossSourceAlignments", id, row);
		}
	}
	private Map<String, Object> bestSegment(String text, List<Map<String, Object>> segments) {
		Set<String> tokens = tokens(text);
		double best = 0;
		Map<String, Object> winner = null;
		for (Map<String, Object> s : segments) {
			Set<String> other = tokens(String.valueOf(s.get("text")));
			Set<String> common = new HashSet<>(tokens);
			common.retainAll(other);
			double score = tokens.isEmpty() ? 0 : (double) common.size() / tokens.size();
			if (score > best) {
				best = score;
				winner = s;
			}
		}
		return best > 0 ? winner : null;
	}
	private Set<String> tokens(String text) {
		Set<String> s = new HashSet<>();
		for (String token : text.toLowerCase().split("[^a-z0-9]+"))
			if (token.length() > 2)
				s.add(token);
		return s;
	}
	private double classify(String a, String b) throws Exception {
		JsonNode root = parse(gemini.callSimpleTextAPI(
				"Return only JSON {\"duplicate\":boolean,\"confidence\":number}. Same substantive claim means duplicate. A: "
						+ a + " B: " + b));
		if (!root.has("duplicate") || !root.has("confidence"))
			throw new IllegalStateException();
		double c = root.path("confidence").asDouble(-1);
		if (c < 0 || c > 1)
			throw new IllegalStateException();
		return root.path("duplicate").asBoolean() ? c : 0;
	}
	private JsonNode parse(String raw) throws Exception {
		JsonNode root = json.readTree(raw.replace("```json", "").replace("```", "").trim());
		if (root.has("candidates"))
			return parse(root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText());
		return root;
	}
	private static int find(int[] p, int i) {
		if (p[i] != i)
			p[i] = find(p, p[i]);
		return p[i];
	}
	private static String normalize(String s) {
		return s.toLowerCase().replaceAll("[^a-z0-9]", "");
	}
	private static long ms(Map<String, Object> m, String k) {
		return ((Number) m.get(k)).longValue();
	}
	private static String time(long ms) {
		long s = ms / 1000;
		return String.format("%02d:%02d", s / 60, s % 60);
	}
	private record Point(String id, String sourceId, String sourceLabel, String text) {
	}
	private record Decision(int leftIndex, Point a, Point b, double confidence, boolean duplicate, String method) {
	}
	private record Anchor(Map<String, Object> left, Map<String, Object> right, double confidence) {
	}
}
