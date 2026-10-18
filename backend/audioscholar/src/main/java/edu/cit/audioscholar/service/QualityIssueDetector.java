package edu.cit.audioscholar.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.QualityIssue;

@Service
public class QualityIssueDetector {
	public static final String DETECTOR_VERSION = "pcm-window-v2";
	public static final String THRESHOLD_VERSION = "quality-thresholds-v2";
	private static final Logger log = LoggerFactory.getLogger(QualityIssueDetector.class);
	private static final int WINDOW_SECONDS = 15;
	private static final int MIN_CLEAR_SAMPLE_RATE = 16_000;
	private static final int LOW_BITRATE_MONO_KBPS = 32;
	private static final int LOW_BITRATE_STEREO_KBPS = 64;
	private static final double MIN_CLEAR_SNR_DB = 10.0;
	private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

	@Value("${quality.analysis.full-scan-max-duration:30m}")
	private Duration fullScanMaxDuration = Duration.ofMinutes(30);
	@Value("${quality.analysis.command-timeout:10m}")
	private Duration commandTimeout = Duration.ofMinutes(10);
	private MediaCommandRunner commandRunner;
	private MediaRuntimeService mediaRuntime;

	public QualityIssueDetector() {
	}

	@Autowired
	public QualityIssueDetector(MediaCommandRunner commandRunner, MediaRuntimeService mediaRuntime) {
		this.commandRunner = commandRunner;
		this.mediaRuntime = mediaRuntime;
	}

	public record AnalysisResult(List<QualityIssue> issues, String analysisStatus, String analysisMode,
			long analyzedDurationMs, long totalDurationMs, String failureCode, String failureDetail) {
		public double coverageRatio() {
			return totalDurationMs <= 0 ? 0.0 : Math.min(1.0, analyzedDurationMs / (double) totalDurationMs);
		}
	}

	public List<QualityIssue> detectIssues(Path mediaPath) {
		return analyze(mediaPath).issues();
	}

	public AnalysisResult analyze(Path mediaPath) {
		if (mediaPath == null) {
			return new AnalysisResult(List.of(), "FAILED", "NONE", 0, 0, "SOURCE_MISSING",
					"No media path was supplied.");
		}
		List<QualityIssue> issues = inspectMediaMetadata(mediaPath);
		long durationMs = mediaDurationMillis(mediaPath);
		if (durationMs > fullScanMaxDuration.toMillis()) {
			log.info("Skipping full PCM quality scan for long recording {}", mediaPath.getFileName());
			enrich(issues);
			return new AnalysisResult(issues, "PARTIAL", "METADATA_ONLY", 0, durationMs, "DURATION_LIMIT",
					"Recording exceeds the configured full-scan duration.");
		}
		Path canonical = null;
		try {
			canonical = canonicalPcm(mediaPath);
			try (AudioInputStream originalStream = AudioSystem.getAudioInputStream(canonical.toFile())) {
				AudioFormat baseFormat = originalStream.getFormat();
				AudioFormat pcmFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, baseFormat.getSampleRate(), 16,
						baseFormat.getChannels(), baseFormat.getChannels() * 2, baseFormat.getSampleRate(), false);
				try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, originalStream)) {
					inspectPcmStream(pcmStream, pcmFormat, issues);
					long analyzed = durationMs > 0 ? durationMs : mediaDurationMillis(canonical);
					enrich(issues);
					return new AnalysisResult(issues, "COMPLETED", "FULL_PCM", analyzed, analyzed, null, null);
				}
			}
		} catch (Exception e) {
			log.info("Quality issue detector could not inspect PCM for {}: {}", mediaPath.getFileName(),
					e.getMessage());
			enrich(issues);
			return new AnalysisResult(issues, "FAILED", issues.isEmpty() ? "NONE" : "METADATA_ONLY", 0, durationMs,
					failureCode(e), safeDetail(e));
		} finally {
			if (canonical != null && !canonical.equals(mediaPath)) {
				try {
					Files.deleteIfExists(canonical);
				} catch (IOException cleanup) {
					log.warn("Could not delete canonical PCM {}", canonical, cleanup);
				}
			}
		}
	}

	private Path canonicalPcm(Path source) throws IOException {
		if (commandRunner == null || mediaRuntime == null)
			return source;
		Path target = Files.createTempFile("audioscholar-quality-", ".wav");
		try {
			MediaProcessResult result = commandRunner.run(List.of(mediaRuntime.ffmpegPath(), "-y", "-hide_banner",
					"-loglevel", "error", "-i", source.toString(), "-vn", "-ac", "1", "-ar", "16000", "-sample_fmt",
					"s16", target.toString()), commandTimeout);
			if (result.exitCode() != 0 || !Files.isRegularFile(target) || Files.size(target) == 0)
				throw new IOException("DECODER_FAILED: " + result.stderr());
			return target;
		} catch (IOException | RuntimeException failure) {
			Files.deleteIfExists(target);
			throw failure;
		}
	}

	private long mediaDurationMillis(Path mediaPath) {
		try {
			return mediaRuntime != null
					? mediaRuntime.durationMillis(mediaPath)
					: mediaDurationSeconds(mediaPath) * 1000;
		} catch (Exception e) {
			return mediaDurationSeconds(mediaPath) * 1000;
		}
	}

	private String failureCode(Exception e) {
		String text = safeDetail(e).toLowerCase(java.util.Locale.ROOT);
		return text.contains("timed out") || text.contains("timeout") ? "DECODER_TIMEOUT" : "DECODER_FAILED";
	}

	private String safeDetail(Exception e) {
		String value = e.getMessage();
		if (value == null)
			return e.getClass().getSimpleName();
		return value.length() > 500 ? value.substring(0, 500) : value;
	}

	private void enrich(List<QualityIssue> issues) {
		for (QualityIssue issue : issues) {
			issue.setDetectorVersion(DETECTOR_VERSION);
			issue.setThresholdVersion(THRESHOLD_VERSION);
			if (issue.getDetectionSource() == null)
				issue.setDetectionSource("PCM_WINDOW");
			issue.setDetectorRuleId(
					"rule-" + issue.getIssueType().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
			issue.setThresholdSnapshot(Map.of("windowMs", WINDOW_SECONDS * 1000L, "minClearSampleRate",
					MIN_CLEAR_SAMPLE_RATE, "maxClippingRatio", 0.02, "minAudibleRms", 0.01, "minClearRms", 0.025,
					"minClearSnrDb", MIN_CLEAR_SNR_DB));
		}
	}

	private void inspectPcmStream(AudioInputStream stream, AudioFormat format, List<QualityIssue> issues)
			throws IOException {
		int frameSize = format.getFrameSize();
		int sampleRate = Math.max(1, Math.round(format.getSampleRate()));
		if (frameSize <= 0) {
			issues.add(new QualityIssue("00:00", "00:15", "MISSING_AUDIO", "HIGH",
					"The audio could not be measured. Review or replace this source if the summary seems incomplete."));
			return;
		}
		if (sampleRate < MIN_CLEAR_SAMPLE_RATE && !hasIssueType(issues, "UNCLEAR_AUDIO")) {
			issues.add(new QualityIssue("00:00", "00:15", "UNCLEAR_AUDIO", "MODERATE",
					"The recording uses a low sample rate and may lose speech detail. Re-upload a clearer recording if possible."));
		}

		int windowBytes = WINDOW_SECONDS * sampleRate * frameSize;
		byte[] data = new byte[windowBytes];
		long totalBytes = 0;
		int read;
		while ((read = readWindow(stream, data)) > 0) {
			inspectPcmWindow(data, read, format, issues, totalBytes / (sampleRate * frameSize));
			totalBytes += read;
		}
		if (totalBytes == 0) {
			issues.add(new QualityIssue("00:00", "00:15", "MISSING_AUDIO", "HIGH",
					"The audio could not be measured. Review or replace this source if the summary seems incomplete."));
		}
	}

	private int readWindow(AudioInputStream stream, byte[] data) throws IOException {
		int total = 0;
		while (total < data.length) {
			int read = stream.read(data, total, data.length - total);
			if (read < 0) {
				break;
			}
			total += read;
		}
		return total;
	}

	private void inspectPcmWindow(byte[] data, int length, AudioFormat format, List<QualityIssue> issues,
			long baseSecond) {
		int frameSize = format.getFrameSize();
		int sampleRate = Math.max(1, Math.round(format.getSampleRate()));
		int channelCount = Math.max(1, format.getChannels());
		for (int offset = 0; offset < length; offset += WINDOW_SECONDS * sampleRate * frameSize) {
			int end = Math.min(length, offset + WINDOW_SECONDS * sampleRate * frameSize);
			Map<String, Double> metrics = metrics(data, offset, end, frameSize, sampleRate, channelCount);
			double rms = metrics.get("rms");
			double peak = metrics.get("peak");
			double clippingRatio = metrics.get("clippingRatio");
			double snrDb = metrics.get("snrDb");
			int startSecond = Math.toIntExact(baseSecond + offset / (sampleRate * frameSize));
			int endSecond = Math.max(startSecond + 1, Math.toIntExact(baseSecond + end / (sampleRate * frameSize)));

			int issueCountBeforeWindow = issues.size();
			if (clippingRatio > 0.02) {
				issues.add(new QualityIssue(formatTime(startSecond), formatTime(endSecond), "AUDIO_CLIPPING", "HIGH",
						"Audio appears distorted in this section. Cross-check this part with the original recording."));
			} else if (rms < 0.01 && peak < 0.03) {
				issues.add(new QualityIssue(formatTime(startSecond), formatTime(endSecond), "INAUDIBLE_SPEECH", "HIGH",
						"Speech may be too quiet or absent. Re-record or verify the generated notes."));
			} else if (rms < 0.025) {
				issues.add(new QualityIssue(formatTime(startSecond), formatTime(endSecond), "UNCLEAR_AUDIO", "MODERATE",
						"Audio is weak in this section. Review the corresponding summary content."));
			} else if (snrDb < MIN_CLEAR_SNR_DB) {
				issues.add(new QualityIssue(formatTime(startSecond), formatTime(endSecond), "HIGH_BACKGROUND_NOISE",
						"MODERATE",
						"Background noise may make speech unreliable in this section. Review this part before trusting the summary."));
			}
			if (issues.size() > issueCountBeforeWindow) {
				QualityIssue detected = issues.get(issues.size() - 1);
				Map<String, Object> observed = new HashMap<>();
				observed.put("rms", rms);
				observed.put("peak", peak);
				observed.put("clippingRatio", clippingRatio);
				observed.put("snrDb", Double.isFinite(snrDb) ? snrDb : "INFINITE");
				detected.setObservedMetrics(observed);
				detected.setDetectionSource("PCM_WINDOW");
			}
		}
	}

	private long mediaDurationSeconds(Path mediaPath) {
		try {
			return AudioFileIO.read(mediaPath.toFile()).getAudioHeader().getTrackLength();
		} catch (Exception e) {
			return 0;
		}
	}

	private boolean hasIssueType(List<QualityIssue> issues, String issueType) {
		return issues.stream().anyMatch(issue -> issueType.equals(issue.getIssueType()));
	}

	private List<QualityIssue> inspectMediaMetadata(Path mediaPath) {
		List<QualityIssue> issues = new ArrayList<>();
		try {
			AudioFile audioFile = AudioFileIO.read(mediaPath.toFile());
			AudioHeader header = audioFile.getAudioHeader();
			int durationSeconds = Math.max(1, header.getTrackLength());
			int channelCount = Math.max(1, parseFirstInteger(header.getChannels()));
			int sampleRate = parseSampleRate(header.getSampleRate());
			int bitRate = parseFirstInteger(header.getBitRate());
			if (sampleRate > 0 && sampleRate < MIN_CLEAR_SAMPLE_RATE) {
				issues.add(new QualityIssue("00:00", formatTime(durationSeconds), "UNCLEAR_AUDIO", "MODERATE",
						"The recording uses a low sample rate and may lose speech detail. Re-upload a clearer recording if possible."));
				issues.get(issues.size() - 1).setObservedMetrics(Map.of("sampleRate", sampleRate));
				issues.get(issues.size() - 1).setDetectionSource("FILE_METADATA");
			}
			if (isLowBitRate(bitRate, channelCount)) {
				issues.add(new QualityIssue("00:00", formatTime(durationSeconds), "UNCLEAR_AUDIO", "MODERATE",
						"The recording appears highly compressed. Use a less compressed audio file so speech details are easier to verify."));
				issues.get(issues.size() - 1)
						.setObservedMetrics(Map.of("bitRateKbps", bitRate, "channels", channelCount));
				issues.get(issues.size() - 1).setDetectionSource("FILE_METADATA");
			}
		} catch (Exception e) {
			log.debug("Audio metadata could not be inspected for {}: {}", mediaPath.getFileName(), e.getMessage());
		}
		return issues;
	}

	private Map<String, Double> metrics(byte[] data, int start, int end, int frameSize, int sampleRate,
			int channelCount) {
		double sumSquares = 0;
		double peak = 0;
		int clipped = 0;
		int samples = 0;
		for (int i = start; i + 1 < end; i += 2) {
			int sample = (data[i + 1] << 8) | (data[i] & 0xff);
			double normalized = sample / 32768.0;
			double abs = Math.abs(normalized);
			sumSquares += normalized * normalized;
			peak = Math.max(peak, abs);
			if (abs > 0.98) {
				clipped++;
			}
			samples++;
		}
		double rms = samples == 0 ? 0 : Math.sqrt(sumSquares / samples);
		Map<String, Double> result = new HashMap<>();
		result.put("rms", rms);
		result.put("peak", peak);
		result.put("clippingRatio", samples == 0 ? 0 : clipped / (double) samples);
		result.put("snrDb", estimateSnrDb(data, start, end, frameSize, sampleRate, channelCount, rms));
		return result;
	}

	private double estimateSnrDb(byte[] data, int start, int end, int frameSize, int sampleRate, int channelCount,
			double windowRms) {
		int bytesPerSample = Math.max(1, frameSize / Math.max(1, channelCount));
		int frameBytes = Math.max(frameSize, sampleRate / 20 * frameSize);
		List<Double> frameRmsValues = new ArrayList<>();
		for (int frameStart = start; frameStart < end; frameStart += frameBytes) {
			int frameEnd = Math.min(end, frameStart + frameBytes);
			double sumSquares = 0;
			int samples = 0;
			for (int i = frameStart; i + bytesPerSample - 1 < frameEnd; i += bytesPerSample) {
				int sample = (data[i + 1] << 8) | (data[i] & 0xff);
				double normalized = sample / 32768.0;
				sumSquares += normalized * normalized;
				samples++;
			}
			if (samples > 0) {
				frameRmsValues.add(Math.sqrt(sumSquares / samples));
			}
		}
		if (frameRmsValues.size() < 4 || windowRms <= 0) {
			return Double.POSITIVE_INFINITY;
		}
		Collections.sort(frameRmsValues);
		double noiseFloor = frameRmsValues.get(Math.max(0, frameRmsValues.size() / 10));
		if (noiseFloor <= 0) {
			return Double.POSITIVE_INFINITY;
		}
		return 20 * Math.log10(windowRms / noiseFloor);
	}

	private boolean isLowBitRate(int bitRateKbps, int channelCount) {
		if (bitRateKbps <= 0) {
			return false;
		}
		int threshold = channelCount > 1 ? LOW_BITRATE_STEREO_KBPS : LOW_BITRATE_MONO_KBPS;
		return bitRateKbps < threshold;
	}

	private int parseFirstInteger(String value) {
		if (value == null) {
			return 0;
		}
		Matcher matcher = FIRST_NUMBER.matcher(value);
		if (!matcher.find()) {
			return 0;
		}
		try {
			return (int) Math.round(Double.parseDouble(matcher.group()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private int parseSampleRate(String value) {
		int parsed = parseFirstInteger(value);
		if (parsed > 0 && value != null && value.toLowerCase().contains("khz")) {
			return parsed * 1_000;
		}
		return parsed;
	}

	private String formatTime(int totalSeconds) {
		int minutes = totalSeconds / 60;
		int seconds = totalSeconds % 60;
		return String.format("%02d:%02d", minutes, seconds);
	}
}
