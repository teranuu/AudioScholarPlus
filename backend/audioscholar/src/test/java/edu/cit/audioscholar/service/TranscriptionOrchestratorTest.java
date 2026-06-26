package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import edu.cit.audioscholar.exception.GeminiQuotaTimeoutException;
import edu.cit.audioscholar.exception.GeminiRateLimitException;
import edu.cit.audioscholar.exception.NonRetryableTaskException;
import edu.cit.audioscholar.model.AudioChunk;
import edu.cit.audioscholar.model.TranscriptChunk;

class TranscriptionOrchestratorTest {
	@Test
	void waitsForQuotaAndResumesWithoutConsumingRetryAttempt() throws Exception {
		AudioChunkingService chunking = mock(AudioChunkingService.class);
		TranscriptionProvider provider = mock(TranscriptionProvider.class);
		TranscriptChunkRepository repository = mock(TranscriptChunkRepository.class);
		FirebaseService firebase = mock(FirebaseService.class);
		AudioChunk audioChunk = new AudioChunk(0, 0, 60_000, false, Path.of("chunk.mp3"));
		when(chunking.prepareChunks(any(), any())).thenReturn(List.of(audioChunk));
		when(repository.findAll("metadata-1")).thenReturn(List.of());
		when(provider.transcribe(audioChunk, "metadata-1"))
				.thenThrow(new GeminiRateLimitException("rate limited", Instant.now().plusMillis(20), null))
				.thenReturn("completed transcript");
		when(provider.name()).thenReturn("GEMINI");
		when(firebase.getAudioMetadataCollectionName()).thenReturn("audio_metadata");
		List<String> statuses = new ArrayList<>();
		List<Integer> attempts = new ArrayList<>();
		doAnswer(invocation -> {
			TranscriptChunk saved = invocation.getArgument(1);
			statuses.add(saved.getStatus());
			attempts.add(saved.getAttempts());
			return null;
		}).when(repository).save(anyString(), any(TranscriptChunk.class));
		TranscriptionOrchestrator orchestrator = new TranscriptionOrchestrator(chunking, provider, repository, firebase,
				1, 3, Duration.ofMinutes(1));

		String result = orchestrator.transcribe("metadata-1", Path.of("source.mp3"), Path.of("work"));

		assertEquals("completed transcript", result);
		assertTrue(statuses.contains("WAITING_FOR_QUOTA"));
		assertTrue(attempts.stream().allMatch(attempt -> attempt == 1));
		verify(provider, org.mockito.Mockito.times(2)).transcribe(audioChunk, "metadata-1");
	}

	@Test
	void reportsQuotaTimeoutWhenRetryWouldExceedJobDeadline() throws Exception {
		AudioChunkingService chunking = mock(AudioChunkingService.class);
		TranscriptionProvider provider = mock(TranscriptionProvider.class);
		TranscriptChunkRepository repository = mock(TranscriptChunkRepository.class);
		FirebaseService firebase = mock(FirebaseService.class);
		AudioChunk audioChunk = new AudioChunk(0, 0, 60_000, false, Path.of("chunk.mp3"));
		when(chunking.prepareChunks(any(), any())).thenReturn(List.of(audioChunk));
		when(repository.findAll("metadata-1")).thenReturn(List.of());
		when(provider.transcribe(audioChunk, "metadata-1"))
				.thenThrow(new GeminiRateLimitException("rate limited", Instant.now().plusSeconds(60), null));
		when(firebase.getAudioMetadataCollectionName()).thenReturn("audio_metadata");
		TranscriptionOrchestrator orchestrator = new TranscriptionOrchestrator(chunking, provider, repository, firebase,
				1, 3, Duration.ofSeconds(1));

		assertThrows(GeminiQuotaTimeoutException.class,
				() -> orchestrator.transcribe("metadata-1", Path.of("source.mp3"), Path.of("work")));
	}

	@Test
	void marksChunkFailedAndDoesNotRetryNonRetryableFailure() throws Exception {
		AudioChunkingService chunking = mock(AudioChunkingService.class);
		TranscriptionProvider provider = mock(TranscriptionProvider.class);
		TranscriptChunkRepository repository = mock(TranscriptChunkRepository.class);
		FirebaseService firebase = mock(FirebaseService.class);
		AudioChunk audioChunk = new AudioChunk(0, 0, 60_000, false, Path.of("chunk.flac"));
		when(chunking.prepareChunks(any(), any())).thenReturn(List.of(audioChunk));
		when(repository.findAll("metadata-1")).thenReturn(List.of());
		when(provider.transcribe(audioChunk, "metadata-1")).thenThrow(
				new NonRetryableTaskException("Gemini blocked content generation. Finish reason: RECITATION"));
		when(firebase.getAudioMetadataCollectionName()).thenReturn("audio_metadata");
		List<String> statuses = new ArrayList<>();
		doAnswer(invocation -> {
			TranscriptChunk saved = invocation.getArgument(1);
			statuses.add(saved.getStatus());
			return null;
		}).when(repository).save(anyString(), any(TranscriptChunk.class));
		TranscriptionOrchestrator orchestrator = new TranscriptionOrchestrator(chunking, provider, repository, firebase,
				1, 3, Duration.ofMinutes(1));

		assertThrows(NonRetryableTaskException.class,
				() -> orchestrator.transcribe("metadata-1", Path.of("source.mp3"), Path.of("work")));

		assertTrue(statuses.contains("FAILED"));
		verify(provider, org.mockito.Mockito.times(1)).transcribe(audioChunk, "metadata-1");
	}

	@Test
	void removesRepeatedWordsForAnOverlappingHardCut() {
		TranscriptChunk first = chunk(0, false, "The compiler reads source code and creates machine instructions");
		TranscriptChunk second = chunk(1, true,
				"source code and creates machine instructions for the processor to execute");

		assertEquals("The compiler reads source code and creates machine instructions\n\nfor the processor to execute",
				TranscriptionOrchestrator.merge(List.of(first, second)));
	}

	@Test
	void preservesSimilarTextWhenChunksDidNotOverlap() {
		TranscriptChunk first = chunk(0, false, "one two three four five six");
		TranscriptChunk second = chunk(1, false, "two three four five six seven");

		assertEquals("one two three four five six\n\ntwo three four five six seven",
				TranscriptionOrchestrator.merge(List.of(first, second)));
	}

	private TranscriptChunk chunk(int index, boolean overlapsPrevious, String text) {
		TranscriptChunk chunk = new TranscriptChunk();
		chunk.setIndex(index);
		chunk.setOverlapsPrevious(overlapsPrevious);
		chunk.setText(text);
		return chunk;
	}
}
