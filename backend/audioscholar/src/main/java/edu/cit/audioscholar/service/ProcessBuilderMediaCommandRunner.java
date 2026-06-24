package edu.cit.audioscholar.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

@Component
public class ProcessBuilderMediaCommandRunner implements MediaCommandRunner {

	@Override
	public MediaProcessResult run(List<String> command, Duration timeout) throws IOException {
		Process process = new ProcessBuilder(command).start();
		CompletableFuture<String> stdout = readAsync(process.getInputStream());
		CompletableFuture<String> stderr = readAsync(process.getErrorStream());
		try {
			if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				throw new IOException("Media command timed out after " + timeout);
			}
			return new MediaProcessResult(process.exitValue(), stdout.join(), stderr.join());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("Media command was interrupted", e);
		}
	}

	private CompletableFuture<String> readAsync(InputStream stream) {
		return CompletableFuture.supplyAsync(() -> {
			try (stream) {
				return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		});
	}
}
