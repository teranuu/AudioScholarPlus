package edu.cit.audioscholar.service;

import java.io.IOException;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.AudioChunk;

@Service
public class GeminiTranscriptionProvider implements TranscriptionProvider {
	private final GeminiService geminiService;
	private final Semaphore permits;

	public GeminiTranscriptionProvider(GeminiService geminiService,
			@Value("${audio.transcription.concurrency:2}") int concurrency) {
		this.geminiService = geminiService;
		this.permits = new Semaphore(Math.max(1, concurrency), true);
	}

	@Override
	public String name() {
		return "GEMINI";
	}

	@Override
	public String transcribe(AudioChunk chunk, String metadataId) throws IOException {
		boolean acquired = false;
		try {
			permits.acquire();
			acquired = true;
			return geminiService.callGeminiTranscriptionAPIWithFallback(chunk.path(),
					chunk.path().getFileName().toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Transcription interrupted while waiting for a Gemini permit", e);
		} finally {
			if (acquired) {
				permits.release();
			}
		}
	}
}
