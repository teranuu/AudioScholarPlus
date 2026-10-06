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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;

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
				"thresholdVersion"))
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
