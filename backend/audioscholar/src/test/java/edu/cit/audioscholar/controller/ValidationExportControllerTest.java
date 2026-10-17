package edu.cit.audioscholar.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.service.FirebaseService;

class ValidationExportControllerTest {
	@Test
	void defaultExportRedactsNestedContentAndRequiresAdmin() throws Exception {
		FirebaseService firebase = mock(FirebaseService.class);
		when(firebase.getAllData("validationEvents"))
				.thenReturn(List.of(Map.of("eventId", "e1", "workflowId", "r1", "clientSource", "WEB", "eventType",
						"CONFIG_CONFIRMED", "occurredAt", Date.from(Instant.parse("2026-01-01T12:00:00Z")),
						"objectiveCodes", List.of(2), "payload", Map.of("outputType", "NOTES"))));
		when(firebase.getAllData("audio_metadata"))
				.thenReturn(List.of(Map.of("id", "r1", "user", Map.of("userId", "secret-user"), "segments",
						List.of(Map.of("segmentId", "s1", "startMs", 0, "endMs", 10, "text", "secret text")))));
		ValidationExportController controller = new ValidationExportController(firebase, new ObjectMapper());
		String body = new String(controller
				.export("2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", null, "WEB", "jsonl", false).getBody(),
				StandardCharsets.UTF_8);
		assertThat(body).doesNotContain("secret-user", "secret text", "userId").contains("segmentId", "sha256");
		PreAuthorize authorization = ValidationExportController.class.getAnnotation(PreAuthorize.class);
		assertThat(authorization).isNotNull();
		assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
	}

	@Test
	void module3ZeroResultRunsAreExplicitlyNoApplicableRatherThanIncomplete() throws Exception {
		FirebaseService firebase = mock(FirebaseService.class);
		Date occurredAt = Date.from(Instant.parse("2026-01-01T12:00:00Z"));
		when(firebase.getAllData("validationEvents"))
				.thenReturn(List.of(Map.of("eventId", "e1", "workflowId", "job-1", "clientSource", "WEB", "eventType",
						"JOB_COMPLETED", "occurredAt", occurredAt, "objectiveCodes", List.of(9))));
		when(firebase.getAllData("multiSourceJobs")).thenReturn(List.of(Map.of("jobId", "job-1", "status", "COMPLETE",
				"sourceCount", 0, "finalTranscriptionCompletedAt", occurredAt, "mergeStartedAt", occurredAt,
				"mergedSummaryAvailableAt", occurredAt, "mergeLatencyMs", 0)));
		when(firebase.getAllData("multiSourceStageRuns")).thenReturn(List.of(zeroRun("SOURCE_KEY_POINT_EXTRACTION"),
				zeroRun("DEDUPLICATION"), zeroRun("ATTRIBUTION"), zeroRun("ALIGNMENT")));
		ValidationExportController controller = new ValidationExportController(firebase, new ObjectMapper());

		String body = new String(controller
				.export("2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", null, "WEB", "jsonl", false).getBody(),
				StandardCharsets.UTF_8);
		var manifest = new ObjectMapper().readTree(body.lines().findFirst().orElseThrow()).path("data");

		assertThat(manifest.path("integrityIssues")).isEmpty();
		assertThat(manifest.path("objectiveEvidenceStatus").path("10").asText()).isEqualTo("NO_APPLICABLE_RECORDS");
		assertThat(manifest.path("objectiveEvidenceStatus").path("11").asText()).isEqualTo("NO_APPLICABLE_RECORDS");
		assertThat(manifest.path("objectiveEvidenceStatus").path("13").asText()).isEqualTo("NO_APPLICABLE_RECORDS");
		assertThat(manifest.path("objectiveEvidenceStatus").path("14").asText()).isEqualTo("NO_APPLICABLE_RECORDS");
		assertThat(manifest.path("module3OutcomeMetrics").path("numeratorCompleted").asInt()).isEqualTo(1);
		assertThat(manifest.path("module3OutcomeMetrics").path("denominatorValid").asInt()).isEqualTo(1);
		assertThat(manifest.path("module3MergeLatencyMetrics").path("targetMs").asLong()).isEqualTo(120_000);
	}

	@Test
	void integrityUsesPersistedMergedPointsRatherThanTreatingLineageAsItsOwnProof() throws Exception {
		FirebaseService firebase = mock(FirebaseService.class);
		Date occurredAt = Date.from(Instant.parse("2026-01-01T12:00:00Z"));
		when(firebase.getAllData("validationEvents"))
				.thenReturn(List.of(Map.of("eventId", "e1", "workflowId", "job-1", "clientSource", "WEB", "eventType",
						"JOB_COMPLETED", "occurredAt", occurredAt, "objectiveCodes", List.of(9))));
		when(firebase.getAllData("multiSourceJobs")).thenReturn(List.of(Map.of("jobId", "job-1", "status", "COMPLETE",
				"sourceCount", 2, "finalTranscriptionCompletedAt", occurredAt, "mergeStartedAt", occurredAt,
				"mergedSummaryAvailableAt", occurredAt, "mergeLatencyMs", 10)));
		when(firebase.getAllData("summaries"))
				.thenReturn(List.of(Map.of("summaryId", "summary-1", "recordingId", "job-1", "summaryKeyPoints",
						List.of(Map.of("keyPointId", "merged-point-1", "sourceFileId", "source-a")))));
		when(firebase.getAllData("sourceFiles"))
				.thenReturn(List.of(Map.of("jobId", "job-1", "sourceFileId", "source-a"),
						Map.of("jobId", "job-1", "sourceFileId", "source-b")));
		when(firebase.getAllData("sourceKeyPoints")).thenReturn(
				List.of(Map.of("jobId", "job-1", "sourceFileId", "source-a", "keyPointId", "source-point-1")));
		when(firebase.getAllData("multiSourceStageRuns")).thenReturn(List.of(run("SOURCE_KEY_POINT_EXTRACTION", 1, 1),
				run("DEDUPLICATION", 0, 1), run("ATTRIBUTION", 1, 1), zeroRun("ALIGNMENT")));
		ValidationExportController controller = new ValidationExportController(firebase, new ObjectMapper());

		String body = new String(controller
				.export("2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", null, "WEB", "jsonl", false).getBody(),
				StandardCharsets.UTF_8);
		var manifest = new ObjectMapper().readTree(body.lines().findFirst().orElseThrow()).path("data");

		assertThat(manifest.path("integrityIssues").toString())
				.contains("mergedKeyPoint:merged-point-1:missing-lineage-or-attribution");
	}

	private Map<String, Object> zeroRun(String stage) {
		return run(stage, 0, 0);
	}

	private Map<String, Object> run(String stage, int candidateCount, int outputCount) {
		return Map.of("stageRunId", stage, "jobId", "job-1", "stage", stage, "status", "COMPLETED", "candidateCount",
				candidateCount, "outputCount", outputCount, "algorithmVersion", "test-v1", "completedAt",
				"2026-01-01T12:00:00Z");
	}
}
