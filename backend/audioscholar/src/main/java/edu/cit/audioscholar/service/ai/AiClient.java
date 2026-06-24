package edu.cit.audioscholar.service.ai;

import java.nio.file.Path;

public interface AiClient {

	String transcribeAudio(Path audioFilePath, String fileName);

	String summarizeTranscript(String transcriptText, String presentationContext, String metadataId);
}
