package edu.cit.audioscholar.service.ai;

import java.nio.file.Path;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import edu.cit.audioscholar.service.GeminiService;

@Primary
@Service
public class GeminiAiClient implements AiClient {

	private final GeminiService geminiService;

	public GeminiAiClient(GeminiService geminiService) {
		this.geminiService = geminiService;
	}

	@Override
	public String transcribeAudio(Path audioFilePath, String fileName) {
		try {
			return geminiService.callGeminiTranscriptionAPIWithFallback(audioFilePath, fileName);
		} catch (Exception e) {
			throw new IllegalStateException("Gemini transcription failed", e);
		}
	}

	@Override
	public String summarizeTranscript(String transcriptText, String presentationContext, String metadataId) {
		if (StringUtils.hasText(presentationContext)) {
			String prompt = "Use this PowerPoint text as supporting lecture context. Do not invent details outside the transcript: "
					+ presentationContext;
			return geminiService.callGeminiSummarizationAPIWithFallback(prompt, transcriptText);
		}
		return geminiService.generateTranscriptOnlySummary(transcriptText, metadataId);
	}
}
