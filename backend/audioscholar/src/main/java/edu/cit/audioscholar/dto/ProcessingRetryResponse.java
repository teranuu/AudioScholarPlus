package edu.cit.audioscholar.dto;

import java.time.Instant;

public record ProcessingRetryResponse(String metadataId, String recordingId, String status, String retryStage,
		String message, Instant retryAfter) {
}
