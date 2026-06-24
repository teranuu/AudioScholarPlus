package edu.cit.audioscholar.service;

import java.io.IOException;

import edu.cit.audioscholar.model.AudioChunk;

public interface TranscriptionProvider {
	String name();
	String transcribe(AudioChunk chunk, String metadataId) throws IOException;
}
