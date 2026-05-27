package edu.cit.audioscholar.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.AudioQualityReport;
import edu.cit.audioscholar.service.AudioProcessingService;
import edu.cit.audioscholar.service.AudioQualityAnalyzerService;

@RestController
@RequestMapping("/api")
public class QualityReportController {

	private static final Logger log = LoggerFactory.getLogger(QualityReportController.class);

	private final AudioQualityAnalyzerService qualityAnalyzerService;
	private final AudioProcessingService audioProcessingService;

	public QualityReportController(AudioQualityAnalyzerService qualityAnalyzerService,
			AudioProcessingService audioProcessingService) {
		this.qualityAnalyzerService = qualityAnalyzerService;
		this.audioProcessingService = audioProcessingService;
	}

	/**
	 * GET /api/recordings/{recordingId}/quality-report Returns the audio quality
	 * report for the given recording. Returns 404 if the report has not been
	 * generated yet.
	 */
	@GetMapping("/recordings/{recordingId}/quality-report")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<AudioQualityReport> getQualityReport(@PathVariable String recordingId,
			Authentication authentication) {

		String currentUserId = getCurrentUserId(authentication);
		log.info("User {} requesting quality report for recordingId: {}", currentUserId, recordingId);

		// Verify recording exists and belongs to this user
		AudioMetadata metadata = audioProcessingService.getAudioMetadataById(recordingId);
		if (metadata == null) {
			log.warn("Recording not found for ID: {}", recordingId);
			return ResponseEntity.notFound().build();
		}

		if (!currentUserId.equals(metadata.getUserId())) {
			log.warn("User {} attempted to access quality report for recording {} owned by {}", currentUserId,
					recordingId, metadata.getUserId());
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"You do not have permission to access this recording's quality report.");
		}

		AudioQualityReport report = qualityAnalyzerService.getReportByRecordingId(recordingId);
		if (report == null) {
			log.info("Quality report not yet available for recordingId: {}", recordingId);
			return ResponseEntity.notFound().build();
		}

		return ResponseEntity.ok(report);
	}

	private String getCurrentUserId(Authentication authentication) {
		if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
			return jwt.getSubject();
		}
		log.warn("Could not extract user ID from Authentication object. Type: {}",
				authentication != null ? authentication.getClass().getName() : "null");
		throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User ID could not be determined from token.");
	}
}
