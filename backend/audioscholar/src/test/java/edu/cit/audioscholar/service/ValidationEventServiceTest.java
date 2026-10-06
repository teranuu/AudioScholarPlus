package edu.cit.audioscholar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;

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
}
