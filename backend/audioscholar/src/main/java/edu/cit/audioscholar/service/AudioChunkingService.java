package edu.cit.audioscholar.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.AudioChunk;

@Service
public class AudioChunkingService {
	private static final Pattern SILENCE_END = Pattern.compile("silence_end: ([0-9]+(?:\\.[0-9]+)?)");

	private final MediaCommandRunner commandRunner;
	private final MediaRuntimeService mediaRuntime;
	private final Duration targetDuration;
	private final Duration minDuration;
	private final Duration maxDuration;
	private final Duration silenceWindow;
	private final Duration fallbackOverlap;
	private final int silenceNoiseDb;
	private final Duration silenceMinDuration;
	private final Duration commandTimeout;

	public AudioChunkingService(MediaCommandRunner commandRunner, MediaRuntimeService mediaRuntime,
			@Value("${audio.chunking.target-duration:12m}") Duration targetDuration,
			@Value("${audio.chunking.min-duration:8m}") Duration minDuration,
			@Value("${audio.chunking.max-duration:15m}") Duration maxDuration,
			@Value("${audio.chunking.silence-window:90s}") Duration silenceWindow,
			@Value("${audio.chunking.fallback-overlap:2s}") Duration fallbackOverlap,
			@Value("${audio.chunking.silence-noise-db:-35}") int silenceNoiseDb,
			@Value("${audio.chunking.silence-min-duration:600ms}") Duration silenceMinDuration,
			@Value("${audio.chunking.command-timeout:10m}") Duration commandTimeout) {
		this.commandRunner = commandRunner;
		this.mediaRuntime = mediaRuntime;
		this.targetDuration = targetDuration;
		this.minDuration = minDuration;
		this.maxDuration = maxDuration;
		this.silenceWindow = silenceWindow;
		this.fallbackOverlap = fallbackOverlap;
		this.silenceNoiseDb = silenceNoiseDb;
		this.silenceMinDuration = silenceMinDuration;
		this.commandTimeout = commandTimeout;
	}

	public List<AudioChunk> prepareChunks(Path source, Path workDirectory) throws IOException {
		Files.createDirectories(workDirectory);
		long durationMs = probeDurationMs(source);
		List<Long> silenceEnds = detectSilenceEnds(source);
		List<Boundary> boundaries = planBoundaries(durationMs, silenceEnds);
		List<AudioChunk> chunks = new ArrayList<>();
		long previousEnd = 0;
		for (int index = 0; index < boundaries.size(); index++) {
			Boundary boundary = boundaries.get(index);
			long startMs = previousEnd;
			boolean overlapsPrevious = index > 0 && boundaries.get(index - 1).hardCut();
			if (overlapsPrevious) {
				startMs = Math.max(0, startMs - fallbackOverlap.toMillis());
			}
			Path output = workDirectory.resolve(String.format(Locale.ROOT, "chunk_%04d.flac", index));
			extractChunk(source, output, startMs, boundary.endMs());
			chunks.add(new AudioChunk(index, startMs, boundary.endMs(), overlapsPrevious, output));
			previousEnd = boundary.endMs();
		}
		return chunks;
	}

	private long probeDurationMs(Path source) throws IOException {
		return mediaRuntime.durationMillis(source);
	}

	private List<Long> detectSilenceEnds(Path source) throws IOException {
		String filter = String.format(Locale.ROOT, "silencedetect=noise=%ddB:d=%.3f", silenceNoiseDb,
				silenceMinDuration.toMillis() / 1000.0);
		MediaProcessResult result = commandRunner.run(List.of(mediaRuntime.ffmpegPath(), "-hide_banner", "-nostats",
				"-i", source.toString(), "-af", filter, "-f", "null", "-"), commandTimeout);
		if (result.exitCode() != 0) {
			return List.of();
		}
		List<Long> ends = new ArrayList<>();
		Matcher matcher = SILENCE_END.matcher(result.stderr());
		while (matcher.find()) {
			ends.add(Math.round(Double.parseDouble(matcher.group(1)) * 1000));
		}
		return ends;
	}

	private List<Boundary> planBoundaries(long durationMs, List<Long> silenceEnds) {
		List<Boundary> boundaries = new ArrayList<>();
		long current = 0;
		while (durationMs - current > maxDuration.toMillis()) {
			long target = current + targetDuration.toMillis();
			long lower = Math.max(current + minDuration.toMillis(), target - silenceWindow.toMillis());
			long upper = Math.min(current + maxDuration.toMillis(), target + silenceWindow.toMillis());
			Long selected = silenceEnds.stream().filter(value -> value >= lower && value <= upper)
					.min(Comparator.comparingLong(value -> Math.abs(value - target))).orElse(null);
			boolean hardCut = selected == null;
			long end = hardCut ? target : selected;
			boundaries.add(new Boundary(end, hardCut));
			current = end;
		}
		boundaries.add(new Boundary(durationMs, false));
		return boundaries;
	}

	private void extractChunk(Path source, Path output, long startMs, long endMs) throws IOException {
		List<String> command = List.of(mediaRuntime.ffmpegPath(), "-y", "-hide_banner", "-loglevel", "error", "-ss",
				seconds(startMs), "-i", source.toString(), "-t", seconds(endMs - startMs), "-vn", "-ac", "1", "-ar",
				"16000", "-codec:a", "flac", output.toString());
		MediaProcessResult result = commandRunner.run(command, commandTimeout);
		if (result.exitCode() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
			throw new IOException("ffmpeg failed to create chunk " + output.getFileName() + ": " + result.stderr());
		}
	}

	private String seconds(long milliseconds) {
		return String.format(Locale.ROOT, "%.3f", milliseconds / 1000.0);
	}

	private record Boundary(long endMs, boolean hardCut) {
	}
}
