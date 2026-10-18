package edu.cit.audioscholar.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import edu.cit.audioscholar.dto.WarningIndicatorDTO;
import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.Recording;
import edu.cit.audioscholar.model.Summary;
import edu.cit.audioscholar.service.FirebaseService;
import edu.cit.audioscholar.service.RecordingService;
import edu.cit.audioscholar.service.SummaryService;
import edu.cit.audioscholar.service.WarningIndicatorService;

@RestController
@RequestMapping("/api/summaries")
public class WarningIndicatorController {
	private final WarningIndicatorService warnings;
	private final SummaryService summaries;
	private final RecordingService recordings;
	private final FirebaseService firebase;

	public WarningIndicatorController(WarningIndicatorService warnings, SummaryService summaries,
			RecordingService recordings, FirebaseService firebase) {
		this.warnings = warnings;
		this.summaries = summaries;
		this.recordings = recordings;
		this.firebase = firebase;
	}

	@GetMapping("/{summaryId}/warning-indicators")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<?> getWarningIndicators(@PathVariable String summaryId, Authentication authentication) {
		try {
			authorize(summaryId, authentication);
			List<WarningIndicatorDTO> result = warnings.getWarningIndicators(summaryId).stream()
					.filter(w -> !w.isStale() && "DISPLAYABLE".equals(w.getGenerationStatus()))
					.map(WarningIndicatorDTO::fromModel).toList();
			return ResponseEntity.ok(result);
		} catch (ResponseStatusException e) {
			throw e;
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(error(e.getMessage()));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(error("Warning indicators could not be retrieved."));
		}
	}

	@PostMapping("/{summaryId}/warning-indicators")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<?> generateWarningIndicators(@PathVariable String summaryId) {
		try {
			return ResponseEntity.ok(warnings.generateWarningIndicators(summaryId).stream()
					.map(WarningIndicatorDTO::fromModel).toList());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(error(e.getMessage()));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(error("Warning indicators could not be generated."));
		}
	}

	@PostMapping("/{summaryId}/warning-indicators/{warningId}/display")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<?> recordDisplay(@PathVariable String summaryId, @PathVariable String warningId,
			@RequestHeader("X-AudioScholar-Client") String client, @RequestBody Map<String, String> body,
			Authentication authentication) {
		if (!"WEB".equals(client))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "WEB client header required.");
		try {
			authorize(summaryId, authentication);
			return ResponseEntity.ok(warnings.recordDisplay(summaryId, warningId, body.get("displayState")));
		} catch (ResponseStatusException e) {
			throw e;
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(error(e.getMessage()));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(error("Display receipt could not be recorded."));
		}
	}

	private void authorize(String summaryId, Authentication authentication) throws Exception {
		Summary summary = summaries.getSummaryById(summaryId);
		if (summary == null)
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Summary not found.");
		String userId = authentication != null && authentication.getPrincipal() instanceof Jwt jwt
				? jwt.getSubject()
				: null;
		if (userId == null)
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User ID is unavailable.");
		Recording recording = recordings.getRecordingById(summary.getRecordingId());
		if (recording != null) {
			if (!userId.equals(recording.getUserId()))
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied.");
			return;
		}
		AudioMetadata metadata = firebase.getAudioMetadataByRecordingId(summary.getRecordingId());
		if (metadata == null || !userId.equals(metadata.getUserId()))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied.");
	}

	private Map<String, String> error(String message) {
		return Map.of("status", "FAILED", "message", message);
	}
}
