package edu.cit.audioscholar.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.cloud.Timestamp;

import edu.cit.audioscholar.exception.GeminiQuotaTimeoutException;
import edu.cit.audioscholar.exception.GeminiRateLimitException;
import edu.cit.audioscholar.model.AudioChunk;
import edu.cit.audioscholar.model.TranscriptChunk;

@Service
public class TranscriptionOrchestrator {
	private static final String COMPLETE = "COMPLETE";

	private final AudioChunkingService chunkingService;
	private final TranscriptionProvider provider;
	private final TranscriptChunkRepository chunkRepository;
	private final FirebaseService firebaseService;
	private final int concurrency;
	private final int chunkMaxAttempts;
	private final Duration jobTimeout;

	public TranscriptionOrchestrator(AudioChunkingService chunkingService, TranscriptionProvider provider,
			TranscriptChunkRepository chunkRepository, FirebaseService firebaseService,
			@Value("${audio.transcription.concurrency:2}") int concurrency,
			@Value("${audio.transcription.chunk-max-attempts:2}") int chunkMaxAttempts,
			@Value("${audio.transcription.job-timeout:25m}") Duration jobTimeout) {
		this.chunkingService = chunkingService;
		this.provider = provider;
		this.chunkRepository = chunkRepository;
		this.firebaseService = firebaseService;
		this.concurrency = Math.max(1, concurrency);
		this.chunkMaxAttempts = Math.max(1, chunkMaxAttempts);
		this.jobTimeout = jobTimeout;
	}

	public String transcribe(String metadataId, Path source, Path workDirectory) throws IOException {
		long deadlineNanos = System.nanoTime() + jobTimeout.toNanos();
		List<AudioChunk> chunks = chunkingService.prepareChunks(source, workDirectory);
		Map<Integer, TranscriptChunk> persisted = new HashMap<>();
		for (TranscriptChunk chunk : chunkRepository.findAll(metadataId)) {
			persisted.put(chunk.getIndex(), chunk);
		}

		AtomicInteger completed = new AtomicInteger((int) persisted.values().stream()
				.filter(chunk -> COMPLETE.equals(chunk.getStatus()) && chunk.getText() != null).count());
		Timestamp startedAt = Timestamp.now();
		updateProgress(metadataId, "TRANSCRIBING_CHUNKS", chunks.size(), completed.get(), startedAt);

		List<Callable<TranscriptChunk>> work = new ArrayList<>();
		for (AudioChunk audioChunk : chunks) {
			TranscriptChunk existing = persisted.get(audioChunk.index());
			if (existing != null && COMPLETE.equals(existing.getStatus()) && existing.getText() != null) {
				continue;
			}
			work.add(() -> transcribeChunk(metadataId, audioChunk, completed, chunks.size(), startedAt, deadlineNanos));
		}

		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		try {
			long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
			if (remainingMillis <= 0) {
				throw new IOException("Transcription exceeded the " + jobTimeout.toMinutes() + " minute deadline");
			}
			List<Future<TranscriptChunk>> futures = executor.invokeAll(work, remainingMillis, TimeUnit.MILLISECONDS);
			for (Future<TranscriptChunk> future : futures) {
				try {
					TranscriptChunk result = future.get(1, TimeUnit.SECONDS);
					persisted.put(result.getIndex(), result);
				} catch (CancellationException | TimeoutException e) {
					throw new IOException("Transcription exceeded the " + jobTimeout.toMinutes() + " minute deadline",
							e);
				} catch (ExecutionException e) {
					throw unwrap(e);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Transcription was interrupted", e);
		} finally {
			executor.shutdownNow();
		}

		List<TranscriptChunk> ordered = new ArrayList<>();
		for (AudioChunk audioChunk : chunks) {
			TranscriptChunk chunk = persisted.get(audioChunk.index());
			if (chunk == null || !COMPLETE.equals(chunk.getStatus()) || chunk.getText() == null) {
				throw new IOException("Transcript chunk " + audioChunk.index() + " did not complete");
			}
			ordered.add(chunk);
		}
		ordered.sort(Comparator.comparingInt(TranscriptChunk::getIndex));
		updateProgress(metadataId, "MERGING_TRANSCRIPT", chunks.size(), chunks.size(), startedAt);
		return merge(ordered);
	}

	private TranscriptChunk transcribeChunk(String metadataId, AudioChunk audioChunk, AtomicInteger completed,
			int total, Timestamp startedAt, long deadlineNanos) throws IOException {
		TranscriptChunk chunk = fromAudioChunk(audioChunk);
		IOException lastFailure = null;
		int attempt = 1;
		while (attempt <= chunkMaxAttempts) {
			chunk.setAttempts(attempt);
			chunk.setStatus("RUNNING");
			chunk.setError(null);
			chunkRepository.save(metadataId, chunk);
			try {
				chunk.setText(provider.transcribe(audioChunk, metadataId));
				chunk.setProvider(provider.name());
				chunk.setStatus(COMPLETE);
				chunkRepository.save(metadataId, chunk);
				updateProgress(metadataId, "TRANSCRIBING_CHUNKS", total, completed.incrementAndGet(), startedAt);
				return chunk;
			} catch (GeminiRateLimitException e) {
				chunk.setStatus("WAITING_FOR_QUOTA");
				chunk.setError(e.getMessage());
				chunkRepository.save(metadataId, chunk);
				waitForQuota(metadataId, e.getRetryAt(), total, completed.get(), startedAt, deadlineNanos);
			} catch (IOException e) {
				lastFailure = e;
				chunk.setStatus(attempt == chunkMaxAttempts ? "FAILED" : "RETRYING");
				chunk.setError(e.getMessage());
				chunkRepository.save(metadataId, chunk);
				if (attempt < chunkMaxAttempts) {
					sleepBeforeRetry(attempt);
				}
				attempt++;
			}
		}
		throw lastFailure != null ? lastFailure : new IOException("Chunk transcription failed");
	}

	private void waitForQuota(String metadataId, Instant retryAt, int total, int completed, Timestamp startedAt,
			long deadlineNanos) throws IOException {
		long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
		long waitMillis = Math.max(1_000, Duration.between(Instant.now(), retryAt).toMillis());
		if (remainingMillis <= 0 || waitMillis >= remainingMillis) {
			throw new GeminiQuotaTimeoutException("Gemini quota did not recover before the transcription deadline");
		}
		Map<String, Object> updates = progressUpdates("WAITING_FOR_GEMINI_QUOTA", total, completed, startedAt);
		updates.put("quotaRetryAt", Timestamp.of(java.util.Date.from(retryAt)));
		firebaseService.updateDataWithMap(firebaseService.getAudioMetadataCollectionName(), metadataId, updates);
		try {
			Thread.sleep(waitMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Quota wait was interrupted", e);
		}
		updateProgress(metadataId, "TRANSCRIBING_CHUNKS", total, completed, startedAt);
	}

	private void sleepBeforeRetry(int attempt) throws IOException {
		try {
			Thread.sleep(Math.min(8_000L, 2_000L * (1L << Math.max(0, attempt - 1))));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Transcription retry was interrupted", e);
		}
	}

	private TranscriptChunk fromAudioChunk(AudioChunk audioChunk) {
		TranscriptChunk chunk = new TranscriptChunk();
		chunk.setIndex(audioChunk.index());
		chunk.setStartMs(audioChunk.startMs());
		chunk.setEndMs(audioChunk.endMs());
		chunk.setOverlapsPrevious(audioChunk.overlapsPrevious());
		return chunk;
	}

	private void updateProgress(String metadataId, String stage, int total, int completed, Timestamp startedAt) {
		Map<String, Object> updates = progressUpdates(stage, total, completed, startedAt);
		updates.put("quotaRetryAt", null);
		firebaseService.updateDataWithMap(firebaseService.getAudioMetadataCollectionName(), metadataId, updates);
	}

	private Map<String, Object> progressUpdates(String stage, int total, int completed, Timestamp startedAt) {
		Map<String, Object> updates = new HashMap<>();
		updates.put("processingStage", stage);
		updates.put("transcriptionChunksTotal", total);
		updates.put("transcriptionChunksCompleted", completed);
		updates.put("transcriptionStartedAt", startedAt);
		updates.put("transcriptionDeadlineAt",
				Timestamp.ofTimeSecondsAndNanos(startedAt.getSeconds() + jobTimeout.toSeconds(), startedAt.getNanos()));
		updates.put("lastUpdated", Timestamp.now());
		return updates;
	}

	static String merge(List<TranscriptChunk> chunks) {
		StringBuilder merged = new StringBuilder();
		for (TranscriptChunk chunk : chunks) {
			String current = chunk.getText() == null ? "" : chunk.getText().trim();
			if (current.isEmpty()) {
				continue;
			}
			if (merged.isEmpty()) {
				merged.append(current);
				continue;
			}
			if (chunk.isOverlapsPrevious()) {
				current = removeRepeatedPrefix(merged.toString(), current);
			}
			if (!current.isEmpty()) {
				merged.append("\n\n").append(current);
			}
		}
		return merged.toString();
	}

	private static String removeRepeatedPrefix(String previous, String current) {
		String[] left = previous.trim().split("\\s+");
		String[] right = current.trim().split("\\s+");
		int maximum = Math.min(40, Math.min(left.length, right.length));
		for (int count = maximum; count >= 5; count--) {
			boolean matches = true;
			for (int index = 0; index < count; index++) {
				if (!normalize(left[left.length - count + index]).equals(normalize(right[index]))) {
					matches = false;
					break;
				}
			}
			if (matches) {
				return String.join(" ", java.util.Arrays.copyOfRange(right, count, right.length));
			}
		}
		return current;
	}

	private static String normalize(String token) {
		return token.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}

	private IOException unwrap(ExecutionException exception) {
		Throwable cause = exception.getCause();
		return cause instanceof IOException ioException
				? ioException
				: new IOException(cause != null ? cause.getMessage() : "Chunk transcription failed", cause);
	}
}
