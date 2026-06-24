package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import edu.cit.audioscholar.model.AudioChunk;

class AudioChunkingServiceTest {
	@TempDir
	Path tempDirectory;

	@Test
	void prefersSilenceAndOverlapsOnlyHardCuts() throws Exception {
		MediaCommandRunner runner = (command, timeout) -> {
			if (command.contains("null")) {
				return new MediaProcessResult(0, "", "[silencedetect] silence_end: 730.0");
			}
			Files.write(Path.of(command.get(command.size() - 1)), new byte[]{1});
			return new MediaProcessResult(0, "", "");
		};
		MediaRuntimeService mediaRuntime = mock(MediaRuntimeService.class);
		when(mediaRuntime.ffmpegPath()).thenReturn("ffmpeg");
		when(mediaRuntime.durationMillis(any())).thenReturn(1_800_000L);
		AudioChunkingService service = new AudioChunkingService(runner, mediaRuntime, Duration.ofMinutes(12),
				Duration.ofMinutes(8), Duration.ofMinutes(15), Duration.ofSeconds(90), Duration.ofSeconds(2), -35,
				Duration.ofMillis(600), Duration.ofMinutes(1));

		List<AudioChunk> chunks = service.prepareChunks(tempDirectory.resolve("lecture.mp3"),
				tempDirectory.resolve("chunks"));

		assertEquals(3, chunks.size());
		assertEquals(730_000, chunks.get(0).endMs());
		assertFalse(chunks.get(1).overlapsPrevious());
		assertEquals(1_448_000, chunks.get(2).startMs());
		assertTrue(chunks.get(2).overlapsPrevious());
	}
}
