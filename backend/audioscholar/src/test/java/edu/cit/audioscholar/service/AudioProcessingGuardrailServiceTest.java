package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import edu.cit.audioscholar.exception.ProcessingGuardrailException;

class AudioProcessingGuardrailServiceTest {
	@TempDir
	Path tempDirectory;

	@Test
	void acceptsAudioWithinDurationAndTokenLimits() throws Exception {
		Path audio = tempDirectory.resolve("lecture.mp3");
		Files.writeString(audio, "audio");
		MediaRuntimeService mediaRuntime = mock(MediaRuntimeService.class);
		when(mediaRuntime.durationMillis(audio)).thenReturn(Duration.ofMinutes(10).toMillis());
		AudioProcessingGuardrailService service = service(mediaRuntime, Duration.ofMinutes(75), 144_000, 120_000,
				Duration.ofMinutes(75), 144_000);

		AudioProcessingGuardrailService.GuardrailResult result = service.validateAudioFile(audio, "lecture.mp3");

		assertEquals(600, result.durationSeconds());
		assertEquals(19_200, result.estimatedAudioTokens());
		assertEquals(64, result.fingerprint().length());
	}

	@Test
	void rejectsAudioBeyondDurationLimitBeforeGemini() throws Exception {
		Path audio = tempDirectory.resolve("long.mp3");
		Files.writeString(audio, "audio");
		MediaRuntimeService mediaRuntime = mock(MediaRuntimeService.class);
		when(mediaRuntime.durationMillis(audio)).thenReturn(Duration.ofMinutes(76).toMillis());
		AudioProcessingGuardrailService service = service(mediaRuntime, Duration.ofMinutes(75), 144_000, 120_000,
				Duration.ofMinutes(75), 144_000);

		ProcessingGuardrailException failure = assertThrows(ProcessingGuardrailException.class,
				() -> service.validateAudioFile(audio, "long.mp3"));

		assertTrue(failure.getMessage().contains("duration exceeds"));
	}

	@Test
	void rejectsMultiSourceAggregateTokenOverflow() {
		MediaRuntimeService mediaRuntime = mock(MediaRuntimeService.class);
		AudioProcessingGuardrailService service = service(mediaRuntime, Duration.ofMinutes(75), 144_000, 120_000,
				Duration.ofMinutes(75), 10_000);

		assertThrows(ProcessingGuardrailException.class,
				() -> service.validateMultiSourceTotals(
						List.of(new AudioProcessingGuardrailService.GuardrailResult(200, 6_400, "a", "A"),
								new AudioProcessingGuardrailService.GuardrailResult(200, 6_400, "b", "B"))));
	}

	@Test
	void rejectsSummaryInputBeyondTokenLimit() {
		MediaRuntimeService mediaRuntime = mock(MediaRuntimeService.class);
		AudioProcessingGuardrailService service = service(mediaRuntime, Duration.ofMinutes(75), 144_000, 10,
				Duration.ofMinutes(75), 144_000);

		assertThrows(ProcessingGuardrailException.class,
				() -> service.validateSummaryInput("this transcript is definitely more than forty characters", 0));
	}

	private AudioProcessingGuardrailService service(MediaRuntimeService mediaRuntime, Duration maxAudioDuration,
			long maxAudioTokens, long maxSummaryTokens, Duration multiSourceDuration, long multiSourceTokens) {
		return new AudioProcessingGuardrailService(mediaRuntime, maxAudioDuration, maxAudioTokens, maxSummaryTokens, 5,
				multiSourceDuration, multiSourceTokens, "100MB");
	}
}
