package edu.cit.audioscholar.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.controller.ValidationExportController;
import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;
import edu.cit.audioscholar.model.QualityReport;
import edu.cit.audioscholar.model.Summary;
import edu.cit.audioscholar.model.SummaryKeyPoint;
import edu.cit.audioscholar.model.WarningIndicator;
import edu.cit.audioscholar.service.FirebaseService;
import edu.cit.audioscholar.service.MediaRuntimeService;
import edu.cit.audioscholar.service.ProcessBuilderMediaCommandRunner;
import edu.cit.audioscholar.service.QualityIssueDetector;
import edu.cit.audioscholar.service.QualityReportRepository;
import edu.cit.audioscholar.service.QualityReportService;
import edu.cit.audioscholar.service.SummaryService;
import edu.cit.audioscholar.service.ValidationEventService;
import edu.cit.audioscholar.service.WarningIndicatorService;
import edu.cit.audioscholar.service.WarningTemplateRegistry;

class Module2ProcessingLoggingSmokeTest {
	@TempDir
	Path output;

	@Test
	void controlledCorpusProducesExplicitPositiveAndNegativeQualityDecisions() throws Exception {
		ProcessBuilderMediaCommandRunner runner = new ProcessBuilderMediaCommandRunner();
		QualityIssueDetector detector = new QualityIssueDetector(runner, new MediaRuntimeService(runner, ""));
		Path corpus = Path.of("validation", "controlled-corpus");
		List<String> rows = Files.readAllLines(corpus.resolve("manifest.csv"));
		assertThat(rows).hasSizeGreaterThan(1);

		for (String row : rows.subList(1, rows.size())) {
			String[] fields = row.split(",", 5);
			String caseId = fields[0], expectedType = fields[2];
			QualityIssueDetector.AnalysisResult result = detector.analyze(corpus.resolve(fields[1]));
			if (expectedType.isBlank()) {
				assertThat(result.analysisStatus()).as(caseId).isEqualTo("COMPLETED");
				assertThat(result.analysisMode()).as(caseId).isEqualTo("FULL_PCM");
				assertThat(result.issues()).as(caseId).isEmpty();
			} else {
				assertThat(result.issues().stream().map(issue -> issue.getIssueType()).toList()).as(caseId)
						.contains(expectedType);
			}
			result.issues().forEach(issue -> {
				assertThat(issue.getDetectorRuleId()).as(caseId).isNotBlank();
				assertThat(issue.getObservedMetrics()).as(caseId).isNotEmpty();
				assertThat(issue.getThresholdSnapshot()).as(caseId).isNotEmpty();
				assertThat(issue.getThresholdSnapshot()).as(caseId).doesNotContainKey("rule");
				assertThat(issue.getDetectionSource()).as(caseId).isIn("PCM_WINDOW", "FILE_METADATA");
				assertThat(issue.getDetectorVersion()).as(caseId).isEqualTo(QualityIssueDetector.DETECTOR_VERSION);
				assertThat(issue.getThresholdVersion()).as(caseId).isEqualTo(QualityIssueDetector.THRESHOLD_VERSION);
				assertThat(issue.getConfidence()).as(caseId).isNull();
			});
		}
	}

	@Test
	void controlledAudioUsesProductionProcessingAndLoggingPathsThroughEvaluation() throws Exception {
		ObjectMapper json = new ObjectMapper();
		Map<String, Map<String, Map<String, Object>>> store = new ConcurrentHashMap<>();
		FirebaseService firebase = inMemoryFirebase(store);

		AudioMetadata metadata = new AudioMetadata();
		metadata.setId("recording-logging-1");
		metadata.setRecordingId("recording-logging-1");
		metadata.setClientSource("WEB");
		metadata.setProcessingAttempt(1);
		metadata.setAttemptId("recording-logging-1:1");
		metadata.setStudySessionId("study-session-logging-1");
		store(store, "audio_metadata", metadata.getId(), metadata.toMap());
		when(firebase.getAudioMetadataById(metadata.getId())).thenReturn(metadata);
		when(firebase.getAudioMetadataByRecordingId(metadata.getRecordingId())).thenReturn(metadata);

		ValidationEventService events = new ValidationEventService(firebase, json, true, 180, "smoke-test");
		assertThat(events.emit(metadata.getId(), "SINGLE_SOURCE", "WEB", "PROCESSING_STARTED", List.of(3, 4, 5, 6),
				metadata.getAttemptId(), Map.of("recordingId", metadata.getId(), "attemptId", metadata.getAttemptId()),
				"audio_metadata")).isTrue();

		ProcessBuilderMediaCommandRunner runner = new ProcessBuilderMediaCommandRunner();
		MediaRuntimeService runtime = new MediaRuntimeService(runner, "");
		QualityReportService qualityReports = new QualityReportService(new QualityReportRepository(firebase),
				new QualityIssueDetector(runner, runtime), events);
		QualityReport report = qualityReports.analyzeAndSave(metadata.getId(),
				Path.of("validation", "controlled-corpus", "clipping.wav"), metadata.getAttemptId(), null,
				metadata.getId(), "SINGLE_SOURCE", "WEB", "audio_metadata");

		assertThat(report.getStatus()).isEqualTo("ISSUES_DETECTED");
		assertThat(report.getAnalysisStatus()).isEqualTo("COMPLETED");
		assertThat(report.getAnalysisMode()).isEqualTo("FULL_PCM");
		assertThat(report.getIssues()).anyMatch(issue -> "AUDIO_CLIPPING".equals(issue.getIssueType()));
		assertThat(report.getIssues()).allMatch(issue -> issue.getIssueId() != null && !issue.getIssueId().isBlank());
		assertThat(collection(store, "qualityReports")).containsKey(report.getReportId());

		Summary summary = summary(report);
		SummaryService summaryService = mock(SummaryService.class);
		when(summaryService.getSummaryById(summary.getSummaryId())).thenReturn(summary);
		when(summaryService.updateSummary(any(Summary.class))).thenAnswer(invocation -> {
			Summary updated = invocation.getArgument(0);
			store(store, "summaries", updated.getSummaryId(), updated.toMap());
			return updated;
		});
		WarningIndicatorService warnings = new WarningIndicatorService(summaryService, qualityReports, firebase, events,
				new WarningTemplateRegistry(), "smoke-test", 180);
		List<WarningIndicator> generated = warnings.generateWarningIndicators(summary.getSummaryId());

		assertThat(generated).isNotEmpty();
		assertThat(collection(store, "warningMappings").values())
				.anyMatch(mapping -> "OVERLAP".equals(mapping.get("decision")));
		assertThat(collection(store, "warningCandidates")).isNotEmpty();
		assertThat(collection(store, "warningIndicators")).isNotEmpty();

		WarningIndicator warning = generated.get(0);
		Map<String, Object> receipt = warnings.recordDisplay(summary.getSummaryId(), warning.getWarningId(),
				"INDICATOR_VISIBLE");
		assertThat(receipt).doesNotContainKeys("userId", "email");
		assertThat(collection(store, "warningDisplayReceipts")).containsKey(String.valueOf(receipt.get("receiptId")));

		assertThat(events.emitAttemptTerminal(metadata.getId(), ProcessingStatus.COMPLETE)).isTrue();
		List<Map<String, Object>> loggedEvents = new ArrayList<>(collection(store, "validationEvents").values());
		assertThat(eventTypes(loggedEvents)).contains("PROCESSING_STARTED", "QUALITY_ANALYSIS_COMPLETED",
				"QUALITY_REPORT_PERSISTED", "QUALITY_ISSUE_PERSISTED", "WARNING_MAPPING_PERSISTED",
				"WARNING_GENERATION_COMPLETED", "COMPLETE");
		assertThat(loggedEvents.stream().filter(e -> "COMPLETE".equals(e.get("eventType")))).hasSize(1);

		ValidationExportController exporter = new ValidationExportController(firebase, json);
		Path export = output.resolve("module2-processing-logging-smoke.jsonl");
		Files.write(export, exporter.export(Instant.now().minusSeconds(3600).toString(),
				Instant.now().plusSeconds(3600).toString(), null, "WEB", "jsonl", false).getBody());
		JsonNode manifest = json.readTree(Files.readString(export).lines().findFirst().orElseThrow()).path("data");
		assertThat(manifest.path("integrityIssues")).isEmpty();
		assertThat(manifest.path("counts").path("qualityIssues").asInt()).isPositive();
		assertThat(manifest.path("counts").path("warningMappings").asInt()).isPositive();
		assertThat(manifest.path("counts").path("warningIndicators").asInt()).isPositive();
		assertThat(manifest.path("counts").path("warningDisplayReceipts").asInt()).isPositive();
		assertThat(manifest.path("objectiveCodes")).containsExactly(json.valueToTree(3), json.valueToTree(4),
				json.valueToTree(5), json.valueToTree(6), json.valueToTree(7), json.valueToTree(8));
		for (String versionField : List.of("applicationVersions", "detectorVersions", "thresholdVersions",
				"warningTemplateVersions", "conformanceRuleVersions", "evaluatorVersions", "algorithmVersions"))
			assertThat(manifest.path(versionField)).as(versionField).isNotEmpty();

		Path truth = output.resolve("ground-truth.json");
		Files.writeString(truth, groundTruth(json, manifest, report, warning));
		Path evaluation = output.resolve("evaluation");
		ValidationEvaluator.main(new String[]{export.toString(), truth.toString(), evaluation.toString()});
		JsonNode metrics = json.readTree(Files.readString(evaluation.resolve("metrics.json")));
		for (int objective = 3; objective <= 8; objective++)
			assertThat(metrics.path(String.valueOf(objective)).path("status").asText()).as("T" + objective)
					.isEqualTo("PASS");
	}

	private FirebaseService inMemoryFirebase(Map<String, Map<String, Map<String, Object>>> store) {
		FirebaseService firebase = mock(FirebaseService.class);
		when(firebase.getAudioMetadataCollectionName()).thenReturn("audio_metadata");
		when(firebase.saveData(anyString(), anyString(), any())).thenAnswer(invocation -> {
			String collection = invocation.getArgument(0), id = invocation.getArgument(1);
			Object value = invocation.getArgument(2);
			@SuppressWarnings("unchecked")
			Map<String, Object> row = value instanceof Map<?, ?> map
					? new LinkedHashMap<>((Map<String, Object>) map)
					: new LinkedHashMap<>();
			store(store, collection, id, row);
			return "saved";
		});
		doAnswer(invocation -> {
			store(store, invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
			return null;
		}).when(firebase).createEvidence(anyString(), anyString(), anyMap());
		when(firebase.createAttemptTerminalEvidence(anyString(), anyString(), anyString(), anyString(), anyString(),
				anyString(), anyMap())).thenAnswer(invocation -> {
					store(store, invocation.getArgument(4), invocation.getArgument(5), invocation.getArgument(6));
					return true;
				});
		when(firebase.getData(anyString(), anyString()))
				.thenAnswer(invocation -> collection(store, invocation.getArgument(0)).get(invocation.getArgument(1)));
		when(firebase.getAllData(anyString()))
				.thenAnswer(invocation -> new ArrayList<>(collection(store, invocation.getArgument(0)).values()));
		when(firebase.queryCollection(anyString(), anyString(), any())).thenAnswer(invocation -> {
			String collection = invocation.getArgument(0), field = invocation.getArgument(1);
			Object expected = invocation.getArgument(2);
			return collection(store, collection).values().stream()
					.filter(row -> java.util.Objects.equals(expected, row.get(field))).toList();
		});
		when(firebase.queryCollectionWhereIn(anyString(), anyString(), any())).thenAnswer(invocation -> {
			String collection = invocation.getArgument(0), field = invocation.getArgument(1);
			List<?> expected = invocation.getArgument(2);
			return collection(store, collection).values().stream().filter(row -> expected.contains(row.get(field)))
					.toList();
		});
		doAnswer(invocation -> {
			String collection = invocation.getArgument(0), id = invocation.getArgument(1);
			@SuppressWarnings("unchecked")
			Map<String, Object> updates = invocation.getArgument(2);
			collection(store, collection).computeIfAbsent(id, ignored -> new LinkedHashMap<>()).putAll(updates);
			return null;
		}).when(firebase).updateDataWithMap(anyString(), anyString(), anyMap());
		return firebase;
	}

	private Summary summary(QualityReport report) {
		SummaryKeyPoint point = new SummaryKeyPoint();
		point.setKeyPointId("key-point-logging-1");
		point.setSummaryId("summary-logging-1");
		point.setText("The recording discusses the controlled test topic.");
		point.setSourceFileId("recording-logging-1");
		point.setSourceSegmentId("segment-logging-1");
		point.setSourceStartTime("00:00");
		point.setSourceEndTime("00:03");
		Summary summary = new Summary("summary-logging-1", "recording-logging-1", "Controlled summary");
		summary.setQualityReport(report);
		summary.setKeyPoints(List.of(point.getText()));
		summary.setSummaryKeyPoints(List.of(point));
		return summary;
	}

	private String groundTruth(ObjectMapper json, JsonNode manifest, QualityReport report, WarningIndicator warning)
			throws Exception {
		Map<String, Object> truth = new LinkedHashMap<>();
		truth.put("schemaVersion", 2);
		truth.put("exportManifestHash", manifest.path("exportBindingHash").asText());
		truth.put("reviewedRecordings",
				List.of(Map.of("studySessionId", "study-session-logging-1", "recordingId", "recording-logging-1",
						"attemptId", report.getAttemptId(), "reportId", report.getReportId(), "reviewerKey",
						"reviewer-1", "reviewComplete", true, "issueFreeConfirmed", false)));
		var issue = report.getIssues().stream().filter(i -> "AUDIO_CLIPPING".equals(i.getIssueType())).findFirst()
				.orElseThrow();
		truth.put("problemSegments",
				List.of(Map.of("annotationId", "annotation-logging-1", "reportId", report.getReportId(), "issueType",
						issue.getIssueType(), "startMs", issue.getStartMs(), "endMs", issue.getEndMs())));
		truth.put("warningTemplateReviews", List.of(Map.of("templateId", warning.getTemplateId(), "templateVersion",
				warning.getTemplateVersion(), "reviewerConforms", true)));
		return json.writeValueAsString(truth);
	}

	private List<String> eventTypes(List<Map<String, Object>> events) {
		return events.stream().map(event -> String.valueOf(event.get("eventType"))).toList();
	}

	private void store(Map<String, Map<String, Map<String, Object>>> store, String collection, String id,
			Map<String, Object> value) {
		Map<String, Object> row = new LinkedHashMap<>(value);
		row.putIfAbsent("id", id);
		collection(store, collection).put(id, row);
	}

	private Map<String, Map<String, Object>> collection(Map<String, Map<String, Map<String, Object>>> store,
			String collection) {
		return store.computeIfAbsent(collection, ignored -> new ConcurrentHashMap<>());
	}
}
