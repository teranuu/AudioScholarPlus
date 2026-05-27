package edu.cit.audioscholar.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.QualityReport;
import edu.cit.audioscholar.service.FirebaseService;
import edu.cit.audioscholar.service.QualityReportService;

@RestController
@RequestMapping("/api/recordings")
public class QualityReportController {
	private final QualityReportService qualityReportService;
	private final FirebaseService firebaseService;

	public QualityReportController(QualityReportService qualityReportService, FirebaseService firebaseService) {
		this.qualityReportService = qualityReportService;
		this.firebaseService = firebaseService;
	}

	@GetMapping("/{recordingId}/quality-report")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<?> getQualityReport(@PathVariable String recordingId, Authentication authentication) {
		String userId = authentication.getName();
		AudioMetadata metadata = firebaseService.getAudioMetadataByRecordingId(recordingId);
		if (metadata == null) {
			return ResponseEntity.notFound().build();
		}
		if (!userId.equals(metadata.getUserId())) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			QualityReport report = metadata.getQualityReport();
			if (report == null) {
				report = qualityReportService.getReport(recordingId);
			}
			if (report == null) {
				return ResponseEntity.status(HttpStatus.ACCEPTED)
						.body(Map.of("status", "UNAVAILABLE", "message", "Quality report is not available yet."));
			}
			return ResponseEntity.ok(report);
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Map.of("status", "UNAVAILABLE", "message", "Quality report could not be loaded."));
		}
	}
}
