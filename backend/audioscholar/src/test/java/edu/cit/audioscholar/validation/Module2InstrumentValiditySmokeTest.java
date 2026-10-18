package edu.cit.audioscholar.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.controller.ValidationExportController;
import edu.cit.audioscholar.model.QualityIssue;
import edu.cit.audioscholar.model.QualityReport;
import edu.cit.audioscholar.service.FirebaseService;
import edu.cit.audioscholar.service.MediaRuntimeService;
import edu.cit.audioscholar.service.ProcessBuilderMediaCommandRunner;
import edu.cit.audioscholar.service.QualityIssueDetector;
import edu.cit.audioscholar.service.ValidationEventService;

class Module2InstrumentValiditySmokeTest {
	@TempDir
	Path output;

	@Test
	void controlledClippingAudioFlowsThroughExportAndT3ToT8Evaluation() throws Exception {
		ObjectMapper json = new ObjectMapper();
		Path controlledAudio = Path.of("validation", "controlled-corpus", "clipping.wav");
		assertThat(controlledAudio).isRegularFile();
		ProcessBuilderMediaCommandRunner runner = new ProcessBuilderMediaCommandRunner();
		MediaRuntimeService runtime = new MediaRuntimeService(runner, "");
		QualityIssueDetector.AnalysisResult analysis = new QualityIssueDetector(runner, runtime)
				.analyze(controlledAudio);
		QualityIssue issue = analysis.issues().stream().filter(i -> "AUDIO_CLIPPING".equals(i.getIssueType()))
				.findFirst().orElseThrow();
		issue.setIssueId(ValidationEventService
				.digest("report-1:rule-audio-clipping:AUDIO_CLIPPING:" + issue.getStartMs() + ":" + issue.getEndMs()));

		QualityReport report = new QualityReport();
		report.setReportId("report-1");
		report.setRecordingId("recording-1");
		report.setAttemptId("recording-1:1");
		report.setStatus("ISSUES_DETECTED");
		report.setAnalysisStatus(analysis.analysisStatus());
		report.setAnalysisMode(analysis.analysisMode());
		report.setAnalyzedDurationMs(analysis.analyzedDurationMs());
		report.setTotalDurationMs(analysis.totalDurationMs());
		report.setCoverageRatio(analysis.coverageRatio());
		report.setDetectorVersion(QualityIssueDetector.DETECTOR_VERSION);
		report.setThresholdVersion(QualityIssueDetector.THRESHOLD_VERSION);
		report.setAnalysisWindowMs(15_000L);
		report.setAudioFingerprint("controlled-clipping-v1");
		report.setIssues(List.of(issue));

		FirebaseService firebase = mock(FirebaseService.class);
		Map<String, List<Map<String, Object>>> data = datasets(report, issue);
		when(firebase.getAllData(anyString()))
				.thenAnswer(invocation -> data.getOrDefault(invocation.getArgument(0), List.of()));
		ValidationExportController controller = new ValidationExportController(firebase, json);
		byte[] bytes = controller.export("2026-10-18T00:00:00Z", "2026-10-19T00:00:00Z", null, "WEB", "jsonl", false)
				.getBody();
		Path export = output.resolve("export.jsonl");
		Files.write(export, bytes);
		JsonNode manifest = json.readTree(Files.readString(export).lines().findFirst().orElseThrow()).path("data");
		assertThat(manifest.path("integrityIssues")).isEmpty();

		String truthText = "{\"schemaVersion\":2,\"exportManifestHash\":\""
				+ manifest.path("exportBindingHash").asText() + "\","
				+ "\"reviewedRecordings\":[{\"reportId\":\"report-1\",\"reviewComplete\":true}],"
				+ "\"problemSegments\":[{\"annotationId\":\"annotation-1\",\"reportId\":\"report-1\","
				+ "\"issueType\":\"AUDIO_CLIPPING\",\"startMs\":" + issue.getStartMs() + ",\"endMs\":"
				+ issue.getEndMs() + "}]," + "\"warningTemplateReviews\":[{\"templateId\":\"quality-audio-clipping\","
				+ "\"templateVersion\":\"1\",\"reviewerConforms\":true}]}";
		Path truth = output.resolve("ground-truth.json");
		Files.writeString(truth, truthText);
		Path evaluation = output.resolve("evaluation");

		ValidationEvaluator.main(new String[]{export.toString(), truth.toString(), evaluation.toString()});

		JsonNode metrics = json.readTree(Files.readString(evaluation.resolve("metrics.json")));
		for (int objective = 3; objective <= 8; objective++)
			assertThat(metrics.path(String.valueOf(objective)).path("status").asText()).as("T" + objective)
					.isEqualTo("PASS");
		JsonNode matches = json.readTree(Files.readString(evaluation.resolve("quality-matches.json")));
		assertThat(matches).anyMatch(match -> "TP".equals(match.path("result").asText()));
	}

	private Map<String, List<Map<String, Object>>> datasets(QualityReport report, QualityIssue issue) {
		Date occurredAt = Date.from(Instant.parse("2026-10-18T12:00:00Z"));
		Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
		data.put("validationEvents", List.of(
				event("PROCESSING_STARTED", occurredAt, List.of(3, 4, 5, 6), Map.of("attemptId", "recording-1:1")),
				event("QUALITY_REPORT_PERSISTED", occurredAt, List.of(3, 4, 5, 6),
						Map.of("attemptId", "recording-1:1", "status", "ISSUES_DETECTED", "reportId", "report-1")),
				event("WARNING_GENERATION_COMPLETED", occurredAt, List.of(7, 8),
						Map.of("summaryId", "summary-1", "summaryEvidenceVersion", "evidence-v1"))));
		data.put("audio_metadata", List.of(Map.of("id", "recording-1", "recordingId", "recording-1", "attemptId",
				"recording-1:1", "studySessionId", "session-1")));
		data.put("qualityReports", List.of(report.toMap()));
		data.put("summaries", List.of(Map.of("summaryId", "summary-1", "recordingId", "recording-1",
				"summaryEvidenceVersion", "evidence-v1", "warningEvidenceStale", false)));
		data.put("warningMappings",
				List.of(Map.of("mappingId", "mapping-1", "summaryId", "summary-1", "recordingId", "recording-1",
						"summaryEvidenceVersion", "evidence-v1", "itemId", "point-1", "issueId", issue.getIssueId(),
						"decision", "OVERLAP", "evidenceSource", "PROCESSING", "stale", false)));
		Map<String, Object> warning = Map.ofEntries(Map.entry("warningId", "warning-1"),
				Map.entry("summaryId", "summary-1"), Map.entry("recordingId", "recording-1"),
				Map.entry("reportId", "report-1"), Map.entry("issueId", issue.getIssueId()),
				Map.entry("summaryEvidenceVersion", "evidence-v1"), Map.entry("keyPointId", "point-1"),
				Map.entry("templateId", "quality-audio-clipping"), Map.entry("templateVersion", "1"),
				Map.entry("generationStatus", "DISPLAYABLE"), Map.entry("automatedConformancePassed", true),
				Map.entry("evidenceSource", "PROCESSING"), Map.entry("stale", false));
		data.put("warningCandidates", List.of(warning));
		data.put("warningIndicators", List.of(warning));
		data.put("warningDisplayReceipts",
				List.of(Map.of("receiptId", "receipt-1", "summaryId", "summary-1", "recordingId", "recording-1",
						"warningId", "warning-1", "summaryEvidenceVersion", "evidence-v1", "displayState",
						"INDICATOR_VISIBLE", "stale", false)));
		return data;
	}

	private Map<String, Object> event(String type, Date occurredAt, List<Integer> objectives,
			Map<String, Object> payload) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("eventId", type);
		event.put("workflowId", "recording-1");
		event.put("workflowType", "SINGLE_SOURCE");
		event.put("clientSource", "WEB");
		event.put("eventType", type);
		event.put("occurredAt", occurredAt);
		event.put("objectiveCodes", objectives);
		event.put("payload", payload);
		return event;
	}
}
