package edu.cit.audioscholar.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.LearningRecommendation;
import edu.cit.audioscholar.model.Recording;
import edu.cit.audioscholar.service.FirebaseService;
import edu.cit.audioscholar.service.LearningMaterialRecommenderService;
import edu.cit.audioscholar.service.RecordingService;

@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {

	private static final Logger log = LoggerFactory.getLogger(RecommendationController.class);

	private final LearningMaterialRecommenderService recommenderService;
	private final RecordingService recordingService;
	private final FirebaseService firebaseService;

	public RecommendationController(LearningMaterialRecommenderService recommenderService,
			RecordingService recordingService, FirebaseService firebaseService) {
		this.recommenderService = recommenderService;
		this.recordingService = recordingService;
		this.firebaseService = firebaseService;
	}

	@GetMapping("/recording/{recordingId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<List<LearningRecommendation>> getRecommendationsForRecording(@PathVariable String recordingId,
			Authentication authentication) {

		log.info("Received request to get recommendations for recording ID: {}", recordingId);

		try {
			Recording recording = recordingService.getRecordingById(recordingId);
			String ownerId;
			if (recording != null) {
				ownerId = recording.getUserId();
			} else {
				AudioMetadata metadata = firebaseService.getAudioMetadataByRecordingId(recordingId);
				if (metadata == null) {
					throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recording not found");
				}
				ownerId = metadata.getUserId();
			}
			if (authentication == null || !authentication.getName().equals(ownerId)) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Recording belongs to another user");
			}
			List<LearningRecommendation> recommendations = recommenderService
					.getRecommendationsByRecordingId(recordingId);
			log.info("Returning {} recommendations for recording ID: {}", recommendations.size(), recordingId);
			return ResponseEntity.ok(recommendations);
		} catch (ResponseStatusException e) {
			throw e;
		} catch (Exception e) {
			log.error("Internal server error while retrieving recommendations for recording ID: {}", recordingId, e);
			return ResponseEntity.internalServerError().build();
		}
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Void> deleteRecommendation(@PathVariable String id, Authentication authentication) {
		if (id == null || id.isBlank()) {
			log.warn("Delete request received with blank ID.");
			return ResponseEntity.badRequest().build();
		}

		log.info("Received request to delete recommendation ID: {} by user {}", id, authentication.getName());

		try {
			boolean success = recommenderService.deleteRecommendation(id);
			if (success) {
				log.info("Successfully deleted recommendation ID: {}", id);
				return ResponseEntity.noContent().build();
			} else {
				log.warn("Recommendation ID: {} not found or failed to delete.", id);
				return ResponseEntity.notFound().build();
			}
		} catch (Exception e) {
			log.error("Error deleting recommendation ID: {}", id, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}
}
