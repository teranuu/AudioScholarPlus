package edu.cit.audioscholar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import edu.cit.audioscholar.model.QualityReport;

class QualityReportServiceTest {

	Path tempDir;

	private final QualityReportService service = new QualityReportService(mock(FirebaseService.class),
			new QualityIssueDetector());

	@BeforeEach
	void setUp() throws IOException {
		tempDir = Path.of("target", "quality-report-test-audio", UUID.randomUUID().toString());
		Files.createDirectories(tempDir);
	}

	@Test
	void analyzeFlagsLowSampleRateAsUnclearAudio() throws Exception {
		Path audio = writeSineWave("low-rate.wav", 8_000, 1.0, 440.0, 0.35);

		QualityReport report = service.analyze("recording-low-rate", audio);

		assertThat(report.getStatus()).isEqualTo("ISSUES_DETECTED");
		assertThat(report.getIssues()).anyMatch(issue -> "UNCLEAR_AUDIO".equals(issue.getIssueType())
				&& issue.getRecommendedAction().contains("low sample rate"));
	}

	@Test
	void analyzeFlagsClipping() throws Exception {
		Path audio = writeClippedWave("clipped.wav", 16_000, 1.0);

		QualityReport report = service.analyze("recording-clipped", audio);

		assertThat(report.getStatus()).isEqualTo("ISSUES_DETECTED");
		assertThat(report.getIssues()).anyMatch(issue -> "AUDIO_CLIPPING".equals(issue.getIssueType()));
	}

	@Test
	void analyzeFlagsInaudibleSpeech() throws Exception {
		Path audio = writeSineWave("quiet.wav", 16_000, 1.0, 440.0, 0.005);

		QualityReport report = service.analyze("recording-quiet", audio);

		assertThat(report.getStatus()).isEqualTo("ISSUES_DETECTED");
		assertThat(report.getIssues()).anyMatch(issue -> "INAUDIBLE_SPEECH".equals(issue.getIssueType()));
	}

	@Test
	void analyzeFlagsBackgroundNoise() throws Exception {
		Path audio = writeNoisyWave("noisy.wav", 16_000, 1.0);

		QualityReport report = service.analyze("recording-noisy", audio);

		assertThat(report.getStatus()).isEqualTo("ISSUES_DETECTED");
		assertThat(report.getIssues()).anyMatch(issue -> "HIGH_BACKGROUND_NOISE".equals(issue.getIssueType()));
	}

	@Test
	void analyzeLeavesClearRecordingAllClear() throws Exception {
		Path audio = writeAlternatingSpeechWave("clear.wav", 16_000, 1.0);

		QualityReport report = service.analyze("recording-clear", audio);

		assertThat(report.getStatus()).isEqualTo("ALL_CLEAR");
		assertThat(report.getIssues()).isEmpty();
	}

	private Path writeSineWave(String filename, int sampleRate, double seconds, double frequency, double amplitude)
			throws Exception {
		int sampleCount = (int) (sampleRate * seconds);
		short[] samples = new short[sampleCount];
		for (int i = 0; i < sampleCount; i++) {
			samples[i] = (short) (Math.sin(2 * Math.PI * frequency * i / sampleRate) * amplitude * Short.MAX_VALUE);
		}
		return writeWave(filename, sampleRate, samples);
	}

	private Path writeClippedWave(String filename, int sampleRate, double seconds) throws Exception {
		int sampleCount = (int) (sampleRate * seconds);
		short[] samples = new short[sampleCount];
		for (int i = 0; i < sampleCount; i++) {
			samples[i] = i % 2 == 0 ? Short.MAX_VALUE : Short.MIN_VALUE;
		}
		return writeWave(filename, sampleRate, samples);
	}

	private Path writeNoisyWave(String filename, int sampleRate, double seconds) throws Exception {
		int sampleCount = (int) (sampleRate * seconds);
		short[] samples = new short[sampleCount];
		for (int i = 0; i < sampleCount; i++) {
			double speech = Math.sin(2 * Math.PI * 440 * i / sampleRate) * 0.18;
			double noise = Math.sin(2 * Math.PI * 2_400 * i / sampleRate) * 0.12;
			samples[i] = (short) ((speech + noise) * Short.MAX_VALUE);
		}
		return writeWave(filename, sampleRate, samples);
	}

	private Path writeAlternatingSpeechWave(String filename, int sampleRate, double seconds) throws Exception {
		int sampleCount = (int) (sampleRate * seconds);
		short[] samples = new short[sampleCount];
		for (int i = 0; i < sampleCount; i++) {
			double segmentPosition = (i % (sampleRate / 4.0)) / (sampleRate / 4.0);
			double amplitude = segmentPosition < 0.2 ? 0.02 : 0.35;
			samples[i] = (short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * amplitude * Short.MAX_VALUE);
		}
		return writeWave(filename, sampleRate, samples);
	}

	private Path writeWave(String filename, int sampleRate, short[] samples) throws Exception {
		Path path = tempDir.resolve(filename);
		byte[] bytes = toLittleEndianBytes(samples);
		AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
		try (AudioInputStream stream = new AudioInputStream(new java.io.ByteArrayInputStream(bytes), format,
				samples.length)) {
			AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
		}
		return path;
	}

	private byte[] toLittleEndianBytes(short[] samples) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (short sample : samples) {
			buffer.putShort(sample);
		}
		return buffer.array();
	}
}
