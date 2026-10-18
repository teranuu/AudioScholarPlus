package edu.cit.audioscholar.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedQualityDecodeIntegrationTest {
	@TempDir
	Path directory;

	@Test
	void managedRuntimeActuallyDecodesWavMp3M4aAndAac() throws Exception {
		ProcessBuilderMediaCommandRunner runner = new ProcessBuilderMediaCommandRunner();
		MediaRuntimeService runtime = new MediaRuntimeService(runner, "");
		runtime.verifyRuntime();
		Path wav = directory.resolve("seed.wav");
		writeWave(wav);

		for (String extension : List.of("wav", "mp3", "m4a", "aac")) {
			Path source = "wav".equals(extension) ? wav : encode(runtime, runner, wav, extension);

			QualityIssueDetector.AnalysisResult result = new QualityIssueDetector(runner, runtime).analyze(source);

			assertThat(result.analysisStatus()).as(extension).isEqualTo("COMPLETED");
			assertThat(result.analysisMode()).as(extension).isEqualTo("FULL_PCM");
			assertThat(result.coverageRatio()).as(extension).isEqualTo(1.0);
		}
	}

	private Path encode(MediaRuntimeService runtime, MediaCommandRunner runner, Path wav, String extension)
			throws Exception {
		Path output = directory.resolve("encoded." + extension);
		List<String> command = new ArrayList<>(
				List.of(runtime.ffmpegPath(), "-y", "-hide_banner", "-loglevel", "error", "-i", wav.toString()));
		if ("m4a".equals(extension) || "aac".equals(extension))
			command.addAll(List.of("-c:a", "aac", "-b:a", "96k"));
		if ("aac".equals(extension))
			command.addAll(List.of("-f", "adts"));
		command.add(output.toString());
		MediaProcessResult encoded = runner.run(command, Duration.ofSeconds(30));
		assertThat(encoded.exitCode()).as(encoded.stderr()).isZero();
		assertThat(output).isNotEmptyFile();
		return output;
	}

	private void writeWave(Path path) throws Exception {
		int sampleRate = 16_000;
		ByteBuffer samples = ByteBuffer.allocate(sampleRate * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < sampleRate; i++)
			samples.putShort((short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * .3 * Short.MAX_VALUE));
		AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
		try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(samples.array()), format,
				sampleRate)) {
			AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
		}
		assertThat(Files.size(path)).isPositive();
	}
}
