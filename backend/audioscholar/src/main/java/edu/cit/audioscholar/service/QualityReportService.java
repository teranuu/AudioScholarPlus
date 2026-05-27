package edu.cit.audioscholar.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.QualityIssue;
import edu.cit.audioscholar.model.QualityReport;

@Service
public class QualityReportService {
	private static final Logger log = LoggerFactory.getLogger(QualityReportService.class);
	private static final String COLLECTION_NAME = "qualityReports";
	private static final int WINDOW_SECONDS = 15;

	private final FirebaseService firebaseService;

	public QualityReportService(FirebaseService firebaseService) {
		this.firebaseService = firebaseService;
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
		List<Map<String, Object>> results = firebaseService.queryCollection(COLLECTION_NAME, "recordingId",
				recordingId);
		if (results.isEmpty()) {
			return null;
		}
		return QualityReport.fromMap(results.get(0));
	}

	public void save(QualityReport report) throws ExecutionException, InterruptedException {
		firebaseService.saveData(COLLECTION_NAME, report.getReportId(), report.toMap());
	}

	public QualityReport analyze(String recordingId, Path mediaPath) {
		if (mediaPath == null) {
			return QualityReport.unavailable(recordingId);
		}
		try (AudioInputStream originalStream = AudioSystem.getAudioInputStream(mediaPath.toFile())) {
			AudioFormat baseFormat = originalStream.getFormat();
			AudioFormat pcmFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, baseFormat.getSampleRate(), 16,
					baseFormat.getChannels(), baseFormat.getChannels() * 2, baseFormat.getSampleRate(), false);
			try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, originalStream)) {
				byte[] data = readAllBytes(pcmStream);
				List<QualityIssue> issues = inspectPcm(recordingId, data, pcmFormat);
				QualityReport report = new QualityReport();
				report.setRecordingId(recordingId);
				report.setIssues(issues);
				report.setStatus(issues.isEmpty() ? "ALL_CLEAR" : "ISSUES_DETECTED");
				return report;
			}
		} catch (Exception e) {
			log.info("[{}] Quality analyzer unavailable for {}: {}", recordingId, mediaPath.getFileName(),
					e.getMessage());
			return QualityReport.unavailable(recordingId);
		}
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

	private List<QualityIssue> inspectPcm(String recordingId, byte[] data, AudioFormat format) {
		List<QualityIssue> issues = new ArrayList<>();
		int frameSize = format.getFrameSize();
		if (frameSize <= 0 || data.length < frameSize) {
			issues.add(new QualityIssue("00:00", "00:15", "MISSING_AUDIO", "HIGH",
					"The audio could not be measured. Review or replace this source if the summary seems incomplete."));
			return issues;
		}

		int sampleRate = Math.max(1, Math.round(format.getSampleRate()));
		int windowBytes = WINDOW_SECONDS * sampleRate * frameSize;
		for (int offset = 0; offset < data.length; offset += windowBytes) {
			int end = Math.min(data.length, offset + windowBytes);
			Map<String, Double> metrics = metrics(data, offset, end, frameSize);
			double rms = metrics.get("rms");
			double peak = metrics.get("peak");
			double clippingRatio = metrics.get("clippingRatio");
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
			}
		}
		return issues;
	}

	private Map<String, Double> metrics(byte[] data, int start, int end, int frameSize) {
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
		Map<String, Double> result = new HashMap<>();
		result.put("rms", samples == 0 ? 0 : Math.sqrt(sumSquares / samples));
		result.put("peak", peak);
		result.put("clippingRatio", samples == 0 ? 0 : clipped / (double) samples);
		return result;
	}

	private String formatTime(int totalSeconds) {
		int minutes = totalSeconds / 60;
		int seconds = totalSeconds % 60;
		return String.format("%02d:%02d", minutes, seconds);
	}
}
