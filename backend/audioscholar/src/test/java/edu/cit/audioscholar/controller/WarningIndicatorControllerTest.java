package edu.cit.audioscholar.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import edu.cit.audioscholar.model.Recording;
import edu.cit.audioscholar.model.Summary;
import edu.cit.audioscholar.service.FirebaseService;
import edu.cit.audioscholar.service.RecordingService;
import edu.cit.audioscholar.service.SummaryService;
import edu.cit.audioscholar.service.WarningIndicatorService;

class WarningIndicatorControllerTest {
	@Test
	void crossUserCannotReadWarningsOrCreateDisplayReceipts() throws Exception {
		WarningIndicatorService warnings = mock(WarningIndicatorService.class);
		SummaryService summaries = mock(SummaryService.class);
		RecordingService recordings = mock(RecordingService.class);
		Summary summary = new Summary();
		summary.setSummaryId("summary-1");
		summary.setRecordingId("recording-1");
		Recording recording = new Recording();
		recording.setUserId("owner-user");
		when(summaries.getSummaryById("summary-1")).thenReturn(summary);
		when(recordings.getRecordingById("recording-1")).thenReturn(recording);
		Authentication authentication = mock(Authentication.class);
		Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("different-user").issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(60)).build();
		when(authentication.getPrincipal()).thenReturn(jwt);
		WarningIndicatorController controller = new WarningIndicatorController(warnings, summaries, recordings,
				mock(FirebaseService.class));

		assertForbidden(() -> controller.getWarningIndicators("summary-1", authentication));
		assertForbidden(() -> controller.recordDisplay("summary-1", "warning-1", "WEB",
				Map.of("displayState", "INDICATOR_VISIBLE"), authentication));
	}

	private void assertForbidden(ThrowingCall call) {
		assertThatThrownBy(call::run).isInstanceOf(ResponseStatusException.class)
				.satisfies(error -> org.assertj.core.api.Assertions
						.assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
	}

	@FunctionalInterface
	private interface ThrowingCall {
		void run() throws Exception;
	}
}
