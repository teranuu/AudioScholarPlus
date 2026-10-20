package edu.cit.audioscholar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QualityIssueDetectorTest {
	@TempDir
	Path directory;

	@ParameterizedTest
	@ValueSource(strings = {"wav", "mp3", "m4a", "aac"})
	void decodesEveryStudyFormatToCanonicalPcmAndCleansUp(String extension) throws Exception {
		Path source = directory.resolve("source." + extension);
		Files.writeString(source, "encoded-audio-placeholder");
		AtomicReference<Path> canonical = new AtomicReference<>();
		AtomicReference<List<String>> command = new AtomicReference<>();
		MediaCommandRunner runner = (arguments, timeout) -> {
			command.set(arguments);
			Path target = Path.of(arguments.get(arguments.size() - 1));
			canonical.set(target);
			writeWave(target);
			return new MediaProcessResult(0, "", "");
		};
		MediaRuntimeService runtime = mock(MediaRuntimeService.class);
		when(runtime.ffmpegPath()).thenReturn("managed-ffmpeg");
		when(runtime.durationMillis(source)).thenReturn(1_000L);

		QualityIssueDetector.AnalysisResult result = new QualityIssueDetector(runner, runtime).analyze(source);

		assertThat(result.analysisStatus()).isEqualTo("COMPLETED");
		assertThat(result.analysisMode()).isEqualTo("FULL_PCM");
		assertThat(command.get()).containsSubsequence("-ac", "1", "-ar", "16000", "-sample_fmt", "s16");
		assertThat(canonical.get()).isNotNull();
		assertThat(canonical.get()).doesNotExist();
	}

	@Test
	void decoderFailureAndTimeoutArePersistableFailuresAndCleanTemporaryPcm() throws Exception {
		Path source = directory.resolve("bad.mp3");
		Files.writeString(source, "bad");
		for (boolean timeout : List.of(false, true)) {
			AtomicReference<Path> canonical = new AtomicReference<>();
			MediaCommandRunner runner = (arguments, ignored) -> {
				canonical.set(Path.of(arguments.get(arguments.size() - 1)));
				if (timeout)
					throw new IOException("decoder timed out");
				return new MediaProcessResult(1, "", "unsupported input");
			};
			MediaRuntimeService runtime = mock(MediaRuntimeService.class);
			when(runtime.ffmpegPath()).thenReturn("managed-ffmpeg");
			when(runtime.durationMillis(source)).thenReturn(1_000L);

			QualityIssueDetector.AnalysisResult result = new QualityIssueDetector(runner, runtime).analyze(source);

			assertThat(result.analysisStatus()).isEqualTo("FAILED");
			assertThat(result.failureCode()).isEqualTo(timeout ? "DECODER_TIMEOUT" : "DECODER_FAILED");
			assertThat(canonical.get()).doesNotExist();
		}
	}

	private void writeWave(Path path) throws IOException {
		int sampleRate = 16_000;
		short[] samples = new short[sampleRate];
		for (int i = 0; i < samples.length; i++)
			samples[i] = (short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * .3 * Short.MAX_VALUE);
		ByteBuffer bytes = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (short sample : samples)
			bytes.putShort(sample);
		AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
		try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(bytes.array()), format,
				samples.length)) {
			AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
		}
	}
}
