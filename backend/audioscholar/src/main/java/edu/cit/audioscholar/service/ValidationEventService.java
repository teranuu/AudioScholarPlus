package edu.cit.audioscholar.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;

import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;
import edu.cit.audioscholar.model.QualityReport;

@Service
public class ValidationEventService {
	private static final Logger log = LoggerFactory.getLogger(ValidationEventService.class);
	public static final String COLLECTION = "validationEvents";
	private static final Set<String> FORBIDDEN = Set.of("userid", "email", "filename", "fileurl", "text", "content",
			"transcript", "transcripttext", "summarytext", "formattedsummarytext", "front", "back", "title",
			"description", "tempaudiofilepath", "temppptxfilepath");
	private final FirebaseService firebase;
	private final ObjectMapper json;
	private final boolean enabled;
	private final int retentionDays;
	private final String applicationVersion;

	public ValidationEventService(FirebaseService firebase, @Qualifier("objectMapper") ObjectMapper json,
			@Value("${validation.telemetry.enabled:false}") boolean enabled,
			@Value("${validation.retention-days:180}") int retentionDays,
			@Value("${spring.application.version:unknown}") String applicationVersion) {
		this.firebase = firebase;
		this.json = json;
		this.enabled = enabled;
		this.retentionDays = Math.max(1, retentionDays);
		this.applicationVersion = applicationVersion;
	}

	public static String clientSource(String header) {
		return "WEB".equals(header) ? "WEB" : "UNKNOWN";
	}

	public boolean isEnabled() {
		return enabled;
	}

	public boolean recordRejectedRequest(String clientHeader, String reasonCode) {
		String source = clientSource(clientHeader);
		if (!enabled || !"WEB".equals(source))
			return true;
		String rejectionId = "rejected-" + UUID.randomUUID();
		return emit(rejectionId, "SINGLE_SOURCE", source, "REQUEST_REJECTED", List.of(6), "initial",
				Map.of("reasonCode", blank(reasonCode) ? "UNSPECIFIED" : reasonCode), null);
	}

	public boolean emit(String workflowId, String workflowType, String clientSource, String eventType,
			List<Integer> objectives, String occurrenceKey, Map<String, ?> details, String workflowCollection) {
		if (!enabled || !"WEB".equals(clientSource))
			return true;
		if (blank(workflowId) || blank(workflowType) || blank(eventType) || blank(occurrenceKey) || objectives == null
				|| details == null) {
			failure(workflowCollection, workflowId, eventType, "invalid");
			return false;
		}
		Map<String, Object> payload = sanitize(details);
		String eventId = digest(workflowType + ":" + workflowId + ":" + eventType + ":" + occurrenceKey);
		Instant now = Instant.now();
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("eventId", eventId);
		event.put("schemaVersion", 1);
		event.put("eventType", eventType);
		event.put("objectiveCodes", objectives);
		event.put("occurredAt", Timestamp.ofTimeSecondsAndNanos(now.getEpochSecond(), now.getNano()));
		event.put("expiresAt",
				Timestamp.ofTimeSecondsAndNanos(now.plus(retentionDays, ChronoUnit.DAYS).getEpochSecond(), 0));
		event.put("clientSource", clientSource);
		event.put("workflowType", workflowType);
		event.put("workflowId", workflowId);
		event.put("measurementRunId", workflowId);
		event.put("applicationVersion", applicationVersion);
		event.put("outcome", payload.getOrDefault("outcome", eventType));
		for (String key : List.of("recordingId", "jobId", "summaryId", "sourceFileId", "segmentId", "decisionId",
				"reportId", "issueId", "modelVersion", "promptVersion", "detectorVersion", "algorithmVersion",
				"thresholdVersion", "attemptId", "templateId", "templateVersion", "summaryEvidenceVersion"))
			if (payload.containsKey(key))
				event.put(key, payload.get(key));
		event.put("payload", payload);
		try {
			firebase.createEvidence(COLLECTION, eventId, event);
			log.info("VALIDATION_EVENT {}", json.writeValueAsString(event));
			return true;
		} catch (Exception e) {
			failure(workflowCollection, workflowId, eventType, eventId);
			return false;
		}
	}

	public boolean emitAttemptTerminal(String metadataId, ProcessingStatus status) {
		if (!Set.of(ProcessingStatus.COMPLETE, ProcessingStatus.COMPLETED_WITH_WARNINGS, ProcessingStatus.FAILED,
				ProcessingStatus.SUMMARY_FAILED, ProcessingStatus.PROCESSING_HALTED_NO_SPEECH,
				ProcessingStatus.PROCESSING_HALTED_UNSUITABLE_CONTENT).contains(status))
			return false;
		AudioMetadata metadata = firebase.getAudioMetadataById(metadataId);
		if (metadata == null)
			return false;
		String attemptId = blank(metadata.getAttemptId())
				? metadataId + ":" + metadata.getProcessingAttempt()
				: metadata.getAttemptId();
		if (!enabled || !"WEB".equals(metadata.getClientSource()))
			return true;
		ensureQualityResult(metadataId, attemptId, status);
		Map<String, Object> payload = sanitize(
				Map.of("recordingId", metadataId, "attemptId", attemptId, "outcome", status.name()));
		String eventId = digest("SINGLE_SOURCE:" + metadataId + ":" + status.name() + ":terminal:" + attemptId);
		Instant now = Instant.now();
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("eventId", eventId);
		event.put("schemaVersion", 1);
		event.put("eventType", status.name());
		event.put("objectiveCodes", List.of(6));
		event.put("occurredAt", Timestamp.ofTimeSecondsAndNanos(now.getEpochSecond(), now.getNano()));
		event.put("expiresAt",
				Timestamp.ofTimeSecondsAndNanos(now.plus(retentionDays, ChronoUnit.DAYS).getEpochSecond(), 0));
		event.put("clientSource", metadata.getClientSource());
		event.put("workflowType", "SINGLE_SOURCE");
		event.put("workflowId", metadataId);
		event.put("measurementRunId", metadataId);
		event.put("applicationVersion", applicationVersion);
		event.put("outcome", status.name());
		event.put("recordingId", metadataId);
		event.put("attemptId", attemptId);
		event.put("payload", payload);
		try {
			boolean stored = firebase.createAttemptTerminalEvidence(firebase.getAudioMetadataCollectionName(),
					metadataId, attemptId, status.name(), COLLECTION, eventId, event);
			if (stored)
				log.info("VALIDATION_EVENT {}", json.writeValueAsString(event));
			return stored;
		} catch (Exception failure) {
			failure(firebase.getAudioMetadataCollectionName(), metadataId, status.name(), eventId);
			return false;
		}
	}

	private void ensureQualityResult(String recordingId, String attemptId, ProcessingStatus terminalStatus) {
		try {
			if (!firebase.queryCollection("qualityReports", "attemptId", attemptId).isEmpty())
				return;
			QualityReport report = QualityReport.unavailable(recordingId);
			report.setAttemptId(attemptId);
			report.setAudioFingerprint("unavailable");
			report.setDetectorVersion(QualityIssueDetector.DETECTOR_VERSION);
			report.setThresholdVersion(QualityIssueDetector.THRESHOLD_VERSION);
			report.setAnalysisWindowMs(15_000L);
			report.setAnalyzedDurationMs(0L);
			report.setTotalDurationMs(0L);
			report.setCoverageRatio(0.0);
			report.setFailureCode("PROCESSING_TERMINATED_BEFORE_ANALYSIS");
			report.setFailureDetail("Attempt terminated with " + terminalStatus.name() + " before full PCM analysis.");
			report.setReportId(digest(attemptId + report.getAudioFingerprint() + report.getDetectorVersion()
					+ report.getThresholdVersion()));
			firebase.saveData("qualityReports", report.getReportId(), report.toMap());
		} catch (Exception failure) {
			failure(firebase.getAudioMetadataCollectionName(), recordingId, "QUALITY_REPORT_PERSISTED", attemptId);
		}
	}

	private void failure(String collection, String workflowId, String failedType, String eventId) {
		try {
			log.error("VALIDATION_EVENT {}",
					json.writeValueAsString(Map.of("eventType", "EVIDENCE_WRITE_FAILED", "eventId", eventId,
							"workflowId", workflowId == null ? "" : workflowId, "failedEventType",
							failedType == null ? "" : failedType)));
		} catch (Exception ignored) {
			log.error("VALIDATION_EVENT {\"eventType\":\"EVIDENCE_WRITE_FAILED\"}");
		}
		if (!blank(collection) && !blank(workflowId))
			try {
				firebase.updateDataWithMap(collection, workflowId, Map.of("measurementIncomplete", true));
			} catch (Exception markFailure) {
				log.error("Cannot mark workflow {} measurement incomplete", workflowId, markFailure);
			}
	}

	private Map<String, Object> sanitize(Map<?, ?> source) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (var entry : source.entrySet()) {
			String key = String.valueOf(entry.getKey());
			if (!FORBIDDEN.contains(key.toLowerCase(java.util.Locale.ROOT)))
				result.put(key, sanitizeValue(entry.getValue()));
		}
		return result;
	}

	private Object sanitizeValue(Object value) {
		if (value instanceof Map<?, ?> map)
			return sanitize(map);
		if (value instanceof Collection<?> collection)
			return collection.stream().map(this::sanitizeValue).toList();
		return value;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
	public static String digest(String value) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
