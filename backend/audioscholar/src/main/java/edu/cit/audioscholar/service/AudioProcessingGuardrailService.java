package edu.cit.audioscholar.service;

import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import edu.cit.audioscholar.exception.ProcessingGuardrailException;

@Service
public class AudioProcessingGuardrailService {
	private static final int AUDIO_TOKENS_PER_SECOND = 32;
	private static final int CHARS_PER_TOKEN_ESTIMATE = 4;

	private final MediaRuntimeService mediaRuntime;
	private final Duration maxAudioDuration;
	private final long maxAudioInputTokens;
	private final long maxSummaryInputTokens;
	private final int multiSourceMaxFiles;
	private final Duration multiSourceMaxTotalDuration;
	private final long multiSourceMaxTotalAudioInputTokens;
	private final long maxFileBytes;

	public AudioProcessingGuardrailService(MediaRuntimeService mediaRuntime,
			@Value("${audio.guardrails.max-audio-duration:75m}") Duration maxAudioDuration,
			@Value("${audio.guardrails.max-audio-input-tokens:144000}") long maxAudioInputTokens,
			@Value("${audio.guardrails.max-summary-input-tokens:120000}") long maxSummaryInputTokens,
			@Value("${audio.guardrails.multi-source.max-files:5}") int multiSourceMaxFiles,
			@Value("${audio.guardrails.multi-source.max-total-duration:75m}") Duration multiSourceMaxTotalDuration,
			@Value("${audio.guardrails.multi-source.max-total-audio-input-tokens:144000}") long multiSourceMaxTotalAudioInputTokens,
			@Value("${spring.servlet.multipart.max-file-size}") String maxFileSizeValue) {
		this.mediaRuntime = mediaRuntime;
		this.maxAudioDuration = maxAudioDuration;
		this.maxAudioInputTokens = maxAudioInputTokens;
		this.maxSummaryInputTokens = maxSummaryInputTokens;
		this.multiSourceMaxFiles = Math.max(1, multiSourceMaxFiles);
		this.multiSourceMaxTotalDuration = multiSourceMaxTotalDuration;
		this.multiSourceMaxTotalAudioInputTokens = multiSourceMaxTotalAudioInputTokens;
		this.maxFileBytes = DataSize.parse(maxFileSizeValue).toBytes();
	}

	public GuardrailResult validateAudioFile(Path audioPath, String displayName) throws IOException {
		if (audioPath == null || !Files.isRegularFile(audioPath) || !Files.isReadable(audioPath)) {
			throw new ProcessingGuardrailException("Audio file is missing or unreadable.");
		}
		long sizeBytes = Files.size(audioPath);
		if (sizeBytes <= 0) {
			throw new ProcessingGuardrailException("Audio file cannot be empty.");
		}
		if (sizeBytes > maxFileBytes) {
			throw new ProcessingGuardrailException("Audio file exceeds the maximum allowed size.");
		}
		long durationMs = mediaRuntime.durationMillis(audioPath);
		if (durationMs <= 0) {
			throw new ProcessingGuardrailException("Audio duration could not be detected.");
		}
		long durationSeconds = Math.max(1, (long) Math.ceil(durationMs / 1000.0));
		if (durationMs > maxAudioDuration.toMillis()) {
			throw new ProcessingGuardrailException("Audio duration exceeds the maximum allowed limit of "
					+ maxAudioDuration.toMinutes() + " minutes.");
		}
		long audioTokens = estimateAudioTokens(durationSeconds);
		if (audioTokens > maxAudioInputTokens) {
			throw new ProcessingGuardrailException("Estimated Gemini audio input exceeds the configured limit.");
		}
		return new GuardrailResult(durationSeconds, audioTokens, sha256(audioPath), display(displayName));
	}

	public void validateMultiSourceTotals(List<GuardrailResult> results) {
		if (results == null || results.isEmpty()) {
			throw new ProcessingGuardrailException("Select at least one source file.");
		}
		if (results.size() > multiSourceMaxFiles) {
			throw new ProcessingGuardrailException("Select no more than " + multiSourceMaxFiles + " sources.");
		}
		long totalSeconds = results.stream().mapToLong(GuardrailResult::durationSeconds).sum();
		long totalTokens = results.stream().mapToLong(GuardrailResult::estimatedAudioTokens).sum();
		if (totalSeconds > multiSourceMaxTotalDuration.toSeconds()) {
			throw new ProcessingGuardrailException("Combined source duration exceeds the maximum allowed limit of "
					+ multiSourceMaxTotalDuration.toMinutes() + " minutes.");
		}
		if (totalTokens > multiSourceMaxTotalAudioInputTokens) {
			throw new ProcessingGuardrailException(
					"Combined estimated Gemini audio input exceeds the configured limit.");
		}
	}

	public void validateFileCount(List<MultipartFile> files) {
		if (files == null || files.size() < 2) {
			throw new ProcessingGuardrailException("Select at least two audio or video sources.");
		}
		if (files.size() > multiSourceMaxFiles) {
			throw new ProcessingGuardrailException("Select no more than " + multiSourceMaxFiles + " sources.");
		}
	}

	public long validateSummaryInput(String transcriptText, long contextBytes) {
		long estimatedTokens = estimateTextTokens(transcriptText)
				+ Math.max(0, contextBytes / CHARS_PER_TOKEN_ESTIMATE);
		if (estimatedTokens > maxSummaryInputTokens) {
			throw new ProcessingGuardrailException("Estimated Gemini summary input exceeds the configured limit.");
		}
		return estimatedTokens;
	}

	public long estimateTextTokens(String text) {
		if (!StringUtils.hasText(text)) {
			return 0;
		}
		return Math.max(1, (long) Math.ceil(text.length() / (double) CHARS_PER_TOKEN_ESTIMATE));
	}

	public long estimateAudioTokensForPath(Path audioPath) throws IOException {
		long durationMs = mediaRuntime.durationMillis(audioPath);
		return estimateAudioTokens(Math.max(1, (long) Math.ceil(durationMs / 1000.0)));
	}

	private long estimateAudioTokens(long durationSeconds) {
		return Math.multiplyExact(durationSeconds, AUDIO_TOKENS_PER_SECOND);
	}

	private String sha256(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(path);
					DigestInputStream digestInput = new DigestInputStream(input, digest)) {
				digestInput.transferTo(OutputStreamDiscard.INSTANCE);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	private String display(String displayName) {
		return StringUtils.hasText(displayName) ? displayName : "audio";
	}

	private static final class OutputStreamDiscard extends java.io.OutputStream {
		private static final OutputStreamDiscard INSTANCE = new OutputStreamDiscard();

		@Override
		public void write(int b) {
		}
	}

	public record GuardrailResult(long durationSeconds, long estimatedAudioTokens, String fingerprint,
			String displayName) {
	}
}
