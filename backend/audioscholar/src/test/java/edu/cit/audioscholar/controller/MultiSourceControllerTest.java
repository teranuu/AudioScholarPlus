package edu.cit.audioscholar.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;

import edu.cit.audioscholar.model.MultiSourceJob;
import edu.cit.audioscholar.service.MultiSourceJobService;

class MultiSourceControllerTest {
	@Test
	void validSubmissionReturns202AndPropagatesWebClientMarker() throws Exception {
		MultiSourceJobService service = mock(MultiSourceJobService.class);
		Authentication authentication = mock(Authentication.class);
		when(authentication.getName()).thenReturn("user-1");
		MultiSourceJob queued = new MultiSourceJob();
		queued.setJobId("job-1");
		queued.setStatus("PROCESSING_QUEUED");
		when(service.createAndProcess(eq("user-1"), anyList(), eq(null), eq("Title"), eq(null), eq("NOTES"), eq("WEB")))
				.thenReturn(queued);
		MockMultipartFile first = new MockMultipartFile("mediaFiles", "a.mp3", "audio/mpeg", new byte[]{1});
		MockMultipartFile second = new MockMultipartFile("mediaFiles", "b.mp3", "audio/mpeg", new byte[]{2});

		ResponseEntity<?> response = new MultiSourceController(service).createJob(List.of(first, second), null, "Title",
				null, "NOTES", "WEB", authentication);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(((java.util.Map<?, ?>) response.getBody()).get("status")).isEqualTo("PROCESSING_QUEUED");
		verify(service).createAndProcess(eq("user-1"), anyList(), eq(null), eq("Title"), eq(null), eq("NOTES"),
				eq("WEB"));
	}
}
