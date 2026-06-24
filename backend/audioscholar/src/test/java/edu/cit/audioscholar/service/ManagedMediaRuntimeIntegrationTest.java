package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import edu.cit.audioscholar.model.AudioChunk;

class ManagedMediaRuntimeIntegrationTest {
	private static final Path LECTURE = Path.of(
			"C:/Users/Denn Cayacap/Downloads/Lecture 1 Introduction to CS and Programming Using Python - MIT OpenCourseWare (128k).mp3");

	@TempDir
	Path tempDirectory;

	@Test
	void preparesTheReportedLargeLectureWithManagedFfmpeg() throws Exception {
		assumeTrue(Files.isRegularFile(LECTURE), "Local lecture fixture is not available");
		MediaCommandRunner runner = new ProcessBuilderMediaCommandRunner();
		MediaRuntimeService runtime = new MediaRuntimeService(runner, "");
		runtime.verifyRuntime();
		AudioChunkingService chunking = new AudioChunkingService(runner, runtime, Duration.ofMinutes(12),
				Duration.ofMinutes(8), Duration.ofMinutes(15), Duration.ofSeconds(90), Duration.ofSeconds(2), -35,
				Duration.ofMillis(600), Duration.ofMinutes(10));

		List<AudioChunk> chunks = chunking.prepareChunks(LECTURE, tempDirectory.resolve("chunks"));

		assertTrue(runtime.durationMillis(LECTURE) > Duration.ofMinutes(30).toMillis());
		assertTrue(chunks.size() >= 4);
		assertTrue(chunks.stream().allMatch(chunk -> Files.isRegularFile(chunk.path())));
		assertTrue(chunks.stream().allMatch(chunk -> {
			try {
				return Files.size(chunk.path()) > 0;
			} catch (Exception e) {
				return false;
			}
		}));
	}
}
