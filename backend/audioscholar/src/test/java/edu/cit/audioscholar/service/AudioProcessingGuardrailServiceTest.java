package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import edu.cit.audioscholar.exception.ProcessingGuardrailException;

class AudioProcessingGuardrailServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void acceptsAudioWithinConfiguredLimits() throws Exception {
		MediaRuntimeService mediaRuntimeService = mock(MediaRuntimeService.class);
		Path audio = tempDir.resolve("lecture.mp3");
		Files.writeString(audio, "audio");
		when(mediaRuntimeService.durationMillis(audio)).thenReturn(Duration.ofMinutes(10).toMillis());
		AudioProcessingGuardrailService service = service(mediaRuntimeService);

		AudioProcessingGuardrailService.GuardrailResult result = service.validateAudioFile(audio, "lecture.mp3");

		assertEquals(600, result.durationSeconds());
		assertEquals(19_200, result.estimatedAudioTokens());
		assertNotNull(result.fingerprint());
	}

	@Test
	void rejectsAudioBeyondDurationLimit() throws Exception {
		MediaRuntimeService mediaRuntimeService = mock(MediaRuntimeService.class);
		Path audio = tempDir.resolve("lecture.mp3");
		Files.writeString(audio, "audio");
		when(mediaRuntimeService.durationMillis(audio)).thenReturn(Duration.ofMinutes(76).toMillis());
		AudioProcessingGuardrailService service = service(mediaRuntimeService);

		ProcessingGuardrailException exception = assertThrows(ProcessingGuardrailException.class,
				() -> service.validateAudioFile(audio, "lecture.mp3"));

		assertTrue(exception.getMessage().contains("duration exceeds"));
	}

	@Test
	void rejectsMultiSourceAggregateTokens() {
		AudioProcessingGuardrailService service = service(mock(MediaRuntimeService.class));

		ProcessingGuardrailException exception = assertThrows(ProcessingGuardrailException.class,
				() -> service.validateMultiSourceAggregate(
						List.of(new AudioProcessingGuardrailService.GuardrailResult(2_000, 96_000, "a", "a.mp3"),
								new AudioProcessingGuardrailService.GuardrailResult(2_000, 64_000, "b", "b.mp3"))));

		assertTrue(exception.getMessage().contains("Combined media input"));
	}

	@Test
	void rejectsMultiSourceAggregateUploadBytes() {
		AudioProcessingGuardrailService service = service(mock(MediaRuntimeService.class));
		MockMultipartFile first = new MockMultipartFile("mediaFiles", "a.mp3", "audio/mpeg",
				new byte[60 * 1024 * 1024]);
		MockMultipartFile second = new MockMultipartFile("mediaFiles", "b.mp3", "audio/mpeg",
				new byte[50 * 1024 * 1024]);

		ProcessingGuardrailException exception = assertThrows(ProcessingGuardrailException.class,
				() -> service.validateUploadBytes(List.of(first, second), List.of()));

		assertTrue(exception.getMessage().contains("Combined multi-source upload size"));
	}

	@Test
	void rejectsSummaryInputBeyondLimit() {
		AudioProcessingGuardrailService service = service(mock(MediaRuntimeService.class));
		String transcript = "x".repeat(480_004);

		ProcessingGuardrailException exception = assertThrows(ProcessingGuardrailException.class,
				() -> service.validateSummaryInput(transcript, 0));

		assertTrue(exception.getMessage().contains("summary input"));
	}

	private AudioProcessingGuardrailService service(MediaRuntimeService mediaRuntimeService) {
		return new AudioProcessingGuardrailService(mediaRuntimeService, Duration.ofMinutes(75), 144_000, 120_000,
				"100MB", 5, "100MB", Duration.ofMinutes(75), 144_000);
	}
}
