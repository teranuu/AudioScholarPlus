package edu.cit.audioscholar.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.QualityIssue;
import edu.cit.audioscholar.model.QualityReport;

@Service
public class QualityReportService {
	private static final Logger log = LoggerFactory.getLogger(QualityReportService.class);
	private static final int WINDOW_SECONDS = 15;
	private static final int MIN_CLEAR_SAMPLE_RATE = 16_000;
	private static final int LOW_BITRATE_MONO_KBPS = 32;
	private static final int LOW_BITRATE_STEREO_KBPS = 64;
	private static final double MIN_CLEAR_SNR_DB = 10.0;
	private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

	private final QualityReportRepository qualityReportRepository;
	private final QualityIssueDetector qualityIssueDetector;

	public QualityReportService(FirebaseService firebaseService, QualityIssueDetector qualityIssueDetector) {
		this(new QualityReportRepository(firebaseService), qualityIssueDetector);
	}

	@Autowired
	public QualityReportService(QualityReportRepository qualityReportRepository,
			QualityIssueDetector qualityIssueDetector) {
		this.qualityReportRepository = qualityReportRepository;
		this.qualityIssueDetector = qualityIssueDetector;
	}

	public QualityReport analyzeAndSave(String recordingId, Path mediaPath) {
		QualityReport report = analyze(recordingId, mediaPath);
		try {
			save(report);
		} catch (Exception e) {
			log.warn("[{}] Quality report could not be saved: {}", recordingId, e.getMessage());
		}
		return report;
	}

	public QualityReport getReport(String recordingId) throws ExecutionException, InterruptedException {
		Map<String, Object> reportData = qualityReportRepository.findByRecordingId(recordingId);
		if (reportData == null) {
			return null;
		}
		return QualityReport.fromMap(reportData);
	}

	public QualityReport generateReport(String recordingId) throws ExecutionException, InterruptedException {
		QualityReport existing = getReport(recordingId);
		if (existing != null) {
			return existing;
		}
		QualityReport report = QualityReport.unavailable(recordingId);
		save(report);
		return report;
	}

	public QualityReport createAllClearReport(String recordingId) throws ExecutionException, InterruptedException {
		QualityReport report = QualityReport.allClear(recordingId);
		save(report);
		return report;
	}

	public void save(QualityReport report) throws ExecutionException, InterruptedException {
		qualityReportRepository.save(report);
	}

	public QualityReport analyze(String recordingId, Path mediaPath) {
		if (mediaPath == null) {
			return QualityReport.unavailable(recordingId);
		}
		List<QualityIssue> issues = qualityIssueDetector.detectIssues(mediaPath);
		QualityReport report = new QualityReport();
		report.setRecordingId(recordingId);
		report.setIssues(issues);
		report.setStatus(issues.isEmpty() ? "ALL_CLEAR" : "ISSUES_DETECTED");
		return report;
	}

	private byte[] readAllBytes(AudioInputStream stream) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int read;
		while ((read = stream.read(buffer)) != -1) {
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	private List<QualityIssue> inspectPcm(byte[] data, AudioFormat format, List<QualityIssue> existingIssues) {
		List<QualityIssue> issues = new ArrayList<>();
		int frameSize = format.getFrameSize();
		if (frameSize <= 0 || data.length < frameSize) {
			issues.add(new QualityIssue("00:00", "00:15", "MISSING_AUDIO", "HIGH",
					"The audio could not be measured. Review or replace this source if the summary seems incomplete."));
			return issues;
		}

		int sampleRate = Math.max(1, Math.round(format.getSampleRate()));
		int channelCount = Math.max(1, format.getChannels());
		if (sampleRate < MIN_CLEAR_SAMPLE_RATE && !hasIssueType(existingIssues, "UNCLEAR_AUDIO")) {
			issues.add(new QualityIssue("00:00", formatTime(durationSeconds(data.length, sampleRate, frameSize)),
					"UNCLEAR_AUDIO", "MODERATE",
					"The recording uses a low sample rate and may lose speech detail. Re-upload a clearer recording if possible."));
		}

		int windowBytes = WINDOW_SECONDS * sampleRate * frameSize;
		for (int offset = 0; offset < data.length; offset += windowBytes) {
			int end = Math.min(data.length, offset + windowBytes);
			Map<String, Double> metrics = metrics(data, offset, end, frameSize, sampleRate, channelCount);
			double rms = metrics.get("rms");
			double peak = metrics.get("peak");
			double clippingRatio = metrics.get("clippingRatio");
			double snrDb = metrics.get("snrDb");
			int startSecond = offset / (sampleRate * frameSize);
			int endSecond = Math.max(startSecond + 1, end / (sampleRate * frameSize));

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
		}
		return issues;
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
			}
			if (isLowBitRate(bitRate, channelCount)) {
				issues.add(new QualityIssue("00:00", formatTime(durationSeconds), "UNCLEAR_AUDIO", "MODERATE",
						"The recording appears highly compressed. Use a less compressed audio file so speech details are easier to verify."));
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
			if (abs > 0.98)
				clipped++;
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

	private int durationSeconds(int dataLength, int sampleRate, int frameSize) {
		return Math.max(1, dataLength / Math.max(1, sampleRate * frameSize));
	}

	private String formatTime(int totalSeconds) {
		int minutes = totalSeconds / 60;
		int seconds = totalSeconds % 60;
		return String.format("%02d:%02d", minutes, seconds);
	}
}
