package edu.cit.audioscholar.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import edu.cit.audioscholar.exception.ProcessingGuardrailException;

@Service
public class AudioProcessingGuardrailService {
	private static final int GEMINI_AUDIO_TOKENS_PER_SECOND = 32;
	private static final int ESTIMATED_CHARS_PER_TOKEN = 4;

	private final MediaRuntimeService mediaRuntimeService;
	private final Duration maxAudioDuration;
	private final long maxAudioInputTokens;
	private final long maxSummaryInputTokens;
	private final long maxSingleFileBytes;
	private final int maxMultiSourceFiles;
	private final long maxMultiSourceTotalBytes;
	private final Duration maxMultiSourceTotalDuration;
	private final long maxMultiSourceTotalAudioTokens;

	public AudioProcessingGuardrailService(MediaRuntimeService mediaRuntimeService,
			@Value("${audio.guardrails.max-audio-duration:75m}") Duration maxAudioDuration,
			@Value("${audio.guardrails.max-audio-input-tokens:144000}") long maxAudioInputTokens,
			@Value("${audio.guardrails.max-summary-input-tokens:120000}") long maxSummaryInputTokens,
			@Value("${audio.guardrails.max-single-file-size:100MB}") String maxSingleFileSize,
			@Value("${audio.guardrails.multi-source.max-files:5}") int maxMultiSourceFiles,
			@Value("${audio.guardrails.multi-source.max-total-upload-bytes:100MB}") String maxMultiSourceTotalBytes,
			@Value("${audio.guardrails.multi-source.max-total-duration:75m}") Duration maxMultiSourceTotalDuration,
			@Value("${audio.guardrails.multi-source.max-total-audio-input-tokens:144000}") long maxMultiSourceTotalAudioTokens) {
		this.mediaRuntimeService = mediaRuntimeService;
		this.maxAudioDuration = maxAudioDuration;
		this.maxAudioInputTokens = maxAudioInputTokens;
		this.maxSummaryInputTokens = maxSummaryInputTokens;
		this.maxSingleFileBytes = DataSize.parse(maxSingleFileSize).toBytes();
		this.maxMultiSourceFiles = maxMultiSourceFiles;
		this.maxMultiSourceTotalBytes = DataSize.parse(maxMultiSourceTotalBytes).toBytes();
		this.maxMultiSourceTotalDuration = maxMultiSourceTotalDuration;
		this.maxMultiSourceTotalAudioTokens = maxMultiSourceTotalAudioTokens;
	}

	public GuardrailResult validateAudioFile(Path path, String displayName) {
		try {
			long size = Files.size(path);
			validateFileSize(size, displayName);
			long durationMillis = mediaRuntimeService.durationMillis(path);
			long durationSeconds = Math.max(1, (long) Math.ceil(durationMillis / 1000.0));
			long estimatedTokens = estimateAudioTokens(durationSeconds);
			if (durationSeconds > maxAudioDuration.toSeconds()) {
				throw new ProcessingGuardrailException("Audio duration exceeds the maximum allowed limit of "
						+ maxAudioDuration.toMinutes() + " minutes.");
			}
			if (estimatedTokens > maxAudioInputTokens) {
				throw new ProcessingGuardrailException(
						"Estimated Gemini audio input exceeds the maximum allowed token budget.");
			}
			return new GuardrailResult(durationSeconds, estimatedTokens, fingerprint(path), displayName);
		} catch (ProcessingGuardrailException e) {
			throw e;
		} catch (IOException e) {
			throw new ProcessingGuardrailException("Could not inspect media file before processing: " + e.getMessage(),
					e);
		}
	}

	public void validateFileCount(List<MultipartFile> mediaFiles, List<MultipartFile> documentFiles) {
		int total = safeSize(mediaFiles) + safeSize(documentFiles);
		if (total > maxMultiSourceFiles) {
			throw new ProcessingGuardrailException("Select no more than " + maxMultiSourceFiles + " sources.");
		}
	}

	public void validateUploadBytes(List<MultipartFile> mediaFiles, List<MultipartFile> documentFiles) {
		long totalBytes = 0;
		for (MultipartFile file : nullToEmpty(mediaFiles)) {
			validateFileSize(file.getSize(), file.getOriginalFilename());
			totalBytes += file.getSize();
		}
		for (MultipartFile file : nullToEmpty(documentFiles)) {
			validateFileSize(file.getSize(), file.getOriginalFilename());
			totalBytes += file.getSize();
		}
		if (totalBytes > maxMultiSourceTotalBytes) {
			throw new ProcessingGuardrailException("Combined multi-source upload size exceeds the "
					+ DataSize.ofBytes(maxMultiSourceTotalBytes).toMegabytes() + " MB limit.");
		}
	}

	public void validateMultiSourceAggregate(List<GuardrailResult> mediaResults) {
		long totalDuration = mediaResults.stream().mapToLong(GuardrailResult::durationSeconds).sum();
		long totalTokens = mediaResults.stream().mapToLong(GuardrailResult::estimatedAudioTokens).sum();
		if (totalDuration > maxMultiSourceTotalDuration.toSeconds()) {
			throw new ProcessingGuardrailException("Combined media duration exceeds the maximum allowed limit of "
					+ maxMultiSourceTotalDuration.toMinutes() + " minutes.");
		}
		if (totalTokens > maxMultiSourceTotalAudioTokens) {
			throw new ProcessingGuardrailException(
					"Combined media input exceeds the maximum allowed Gemini token budget.");
		}
	}

	public long validateSummaryInput(String transcriptText, long contextBytes) {
		long estimatedTokens = estimateTextTokens(transcriptText) + estimateContextTokens(contextBytes);
		if (estimatedTokens > maxSummaryInputTokens) {
			throw new ProcessingGuardrailException(
					"Estimated summary input exceeds the maximum allowed Gemini token budget.");
		}
		return estimatedTokens;
	}

	public long estimateTextTokens(String text) {
		if (text == null || text.isBlank()) {
			return 0;
		}
		return Math.max(1, (long) Math.ceil(text.length() / (double) ESTIMATED_CHARS_PER_TOKEN));
	}

	public long estimateContextTokens(long contextBytes) {
		if (contextBytes <= 0) {
			return 0;
		}
		return Math.max(1, (long) Math.ceil(contextBytes / (double) ESTIMATED_CHARS_PER_TOKEN));
	}

	private void validateFileSize(long size, String displayName) {
		if (size > maxSingleFileBytes) {
			throw new ProcessingGuardrailException(
					(displayName != null ? displayName : "File") + " exceeds the maximum allowed file size of "
							+ DataSize.ofBytes(maxSingleFileBytes).toMegabytes() + " MB.");
		}
	}

	private long estimateAudioTokens(long durationSeconds) {
		return Math.multiplyExact(durationSeconds, GEMINI_AUDIO_TOKENS_PER_SECOND);
	}

	private String fingerprint(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
				input.transferTo(OutputStream.nullOutputStream());
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 digest is not available", e);
		}
	}

	private int safeSize(List<MultipartFile> files) {
		return files == null ? 0 : files.size();
	}

	private List<MultipartFile> nullToEmpty(List<MultipartFile> files) {
		return files == null ? List.of() : files;
	}

	public record GuardrailResult(long durationSeconds, long estimatedAudioTokens, String fingerprint,
			String displayName) {
	}
}
