package edu.cit.audioscholar.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import ws.schild.jave.EncoderException;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.process.ffmpeg.DefaultFFMPEGLocator;

@Service
public class MediaRuntimeService {
	private static final Logger log = LoggerFactory.getLogger(MediaRuntimeService.class);

	private final MediaCommandRunner commandRunner;
	private final DefaultFFMPEGLocator managedLocator;
	private final String ffmpegPath;

	public MediaRuntimeService(MediaCommandRunner commandRunner,
			@Value("${audio.chunking.ffmpeg-path:}") String configuredFfmpegPath) {
		this.commandRunner = commandRunner;
		this.managedLocator = new DefaultFFMPEGLocator();
		this.ffmpegPath = StringUtils.hasText(configuredFfmpegPath)
				? configuredFfmpegPath.trim()
				: managedLocator.getExecutablePath();
	}

	@PostConstruct
	void verifyRuntime() throws IOException {
		MediaProcessResult result = commandRunner.run(List.of(ffmpegPath, "-version"), Duration.ofSeconds(20));
		if (result.exitCode() != 0) {
			throw new IOException("MEDIA_RUNTIME_UNAVAILABLE: FFmpeg preflight failed: " + result.stderr());
		}
		log.info("FFmpeg media runtime is ready at {}", ffmpegPath);
	}

	public String ffmpegPath() {
		return ffmpegPath;
	}

	public long durationMillis(Path source) throws IOException {
		if (!Files.isRegularFile(source)) {
			throw new IOException("MEDIA_PREPARATION_FAILED: Audio source does not exist: " + source);
		}
		try {
			long duration = new MultimediaObject(source.toFile(), managedLocator).getInfo().getDuration();
			if (duration <= 0) {
				throw new IOException("MEDIA_PREPARATION_FAILED: Could not determine audio duration");
			}
			return duration;
		} catch (EncoderException e) {
			throw new IOException("MEDIA_PREPARATION_FAILED: Could not inspect audio: " + e.getMessage(), e);
		}
	}
}
