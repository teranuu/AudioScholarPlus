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
}
