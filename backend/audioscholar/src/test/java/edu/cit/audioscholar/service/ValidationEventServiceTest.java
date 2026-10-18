package edu.cit.audioscholar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;

import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;

class ValidationEventServiceTest {
	@Test
	void eventIsDeterministicVersionedExpiringAndContentSafe() {
		FirebaseService firebase = mock(FirebaseService.class);
		ValidationEventService service = new ValidationEventService(firebase, new ObjectMapper(), true, 180, "test");
		assertThat(service.emit("j1", "MULTI_SOURCE", "WEB", "CONFIG_CONFIRMED", List.of(2), "initial",
				Map.of("outputType", "NOTES", "nested", Map.of("transcriptText", "secret", "segmentId", "s1")),
				"multiSourceJobs")).isTrue();
		ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
		String id = ValidationEventService.digest("MULTI_SOURCE:j1:CONFIG_CONFIRMED:initial");
		verify(firebase).createEvidence(eq("validationEvents"), eq(id), captor.capture());
		Map<String, Object> event = captor.getValue();
		assertThat(event.get("schemaVersion")).isEqualTo(1);
		assertThat(((Timestamp) event.get("expiresAt")).toDate().getTime()
				- ((Timestamp) event.get("occurredAt")).toDate().getTime()).isBetween(179L * 86400000, 181L * 86400000);
		assertThat(event.toString()).doesNotContain("secret", "transcriptText");
	}
	@Test
	void failedWriteMarksWorkflowIncomplete() {
		FirebaseService firebase = mock(FirebaseService.class);
		doThrow(new IllegalStateException()).when(firebase).createEvidence(eq("validationEvents"), any(), any());
		ValidationEventService service = new ValidationEventService(firebase, new ObjectMapper(), true, 180, "test");
		assertThat(service.emit("j1", "MULTI_SOURCE", "WEB", "JOB_ACCEPTED", List.of(9), "initial", Map.of(),
				"multiSourceJobs")).isFalse();
		verify(firebase).updateDataWithMap("multiSourceJobs", "j1", Map.of("measurementIncomplete", true));
	}
	@Test
	void nonWebClientIsExcluded() {
		FirebaseService firebase = mock(FirebaseService.class);
		ValidationEventService service = new ValidationEventService(firebase, new ObjectMapper(), true, 180, "test");
		assertThat(ValidationEventService.clientSource("ANDROID")).isEqualTo("UNKNOWN");
		assertThat(service.emit("j1", "MULTI_SOURCE", "UNKNOWN", "JOB_ACCEPTED", List.of(9), "initial", Map.of(),
				"multiSourceJobs")).isTrue();
		org.mockito.Mockito.verifyNoInteractions(firebase);
	}

	@Test
	void rejectedWebRequestIsReportedAnonymouslyWithoutEnteringAnAttempt() {
		FirebaseService firebase = mock(FirebaseService.class);
		ValidationEventService service = new ValidationEventService(firebase, new ObjectMapper(), true, 180, "test");

		assertThat(service.recordRejectedRequest("WEB", "UNSUPPORTED_AUDIO_TYPE")).isTrue();

		ArgumentCaptor<Map<String, Object>> event = ArgumentCaptor.forClass(Map.class);
		verify(firebase).createEvidence(eq("validationEvents"), any(), event.capture());
		assertThat(event.getValue()).containsEntry("eventType", "REQUEST_REJECTED").containsEntry("workflowType",
				"SINGLE_SOURCE");
		assertThat(event.getValue().toString()).contains("UNSUPPORTED_AUDIO_TYPE").doesNotContain("userId", "email",
				"attemptId");
	}

	@Test
	void terminalEventUsesAtomicAttemptStoreAndBackfillsMissingQualityResult() {
		FirebaseService firebase = mock(FirebaseService.class);
		AudioMetadata metadata = new AudioMetadata();
		metadata.setId("r1");
		metadata.setRecordingId("r1");
		metadata.setAttemptId("r1:2");
		metadata.setProcessingAttempt(2);
		metadata.setClientSource("WEB");
		when(firebase.getAudioMetadataById("r1")).thenReturn(metadata);
		when(firebase.getAudioMetadataCollectionName()).thenReturn("audio_metadata");
		when(firebase.queryCollection("qualityReports", "attemptId", "r1:2")).thenReturn(List.of());
		when(firebase.createAttemptTerminalEvidence(eq("audio_metadata"), eq("r1"), eq("r1:2"), eq("FAILED"),
				eq("validationEvents"), any(), any())).thenReturn(true);
		ValidationEventService service = new ValidationEventService(firebase, new ObjectMapper(), true, 180, "test");

		assertThat(service.emitAttemptTerminal("r1", ProcessingStatus.FAILED)).isTrue();

		verify(firebase).saveData(eq("qualityReports"), any(), any());
		verify(firebase).createAttemptTerminalEvidence(eq("audio_metadata"), eq("r1"), eq("r1:2"), eq("FAILED"),
				eq("validationEvents"), any(), any());
	}
}
