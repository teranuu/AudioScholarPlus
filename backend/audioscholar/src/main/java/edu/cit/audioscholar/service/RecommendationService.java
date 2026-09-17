package edu.cit.audioscholar.service;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;

import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.LearningRecommendation;
import edu.cit.audioscholar.model.ProcessingStatus;

@Service
public class RecommendationService {
	private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
	public record RecommendationResult(boolean success, int count, String message, String errorType) {
		public static RecommendationResult success(int count, String message) {
			return new RecommendationResult(true, count, message, null);
		}
		public static RecommendationResult warning(String message) {
			return new RecommendationResult(false, 0, message, "Recommendation Warning");
		}
		public static RecommendationResult error(String type, String message) {
			return new RecommendationResult(false, 0, message, type);
		}
	}

	@Autowired
	private FirebaseService firebaseService;

	@Autowired
	private LearningMaterialRecommenderService learningMaterialRecommenderService;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ProcessingStageClaimService stageClaimService;

	public String recommendAndSave(String metadataId, String userId) {
		RecommendationResult result = recommendAndSaveResult(metadataId, userId);
		return result.success()
				? successResponseToString(result.count(), result.message())
				: errorResponseToString(result.errorType(), result.message());
	}

	public RecommendationResult recommendAndSaveResult(String metadataId, String userId) {
		log.info("[{}] Starting learning materials recommendation for user {}...", metadataId, userId);
		try {
			AudioMetadata metadata = firebaseService.getAudioMetadataById(metadataId);
			if (metadata == null) {
				String errorMsg = "Audio metadata not found for ID: " + metadataId;
				log.error("[{}] {}", metadataId, errorMsg);
				return RecommendationResult.error("Metadata Not Found", errorMsg);
			}

			if (metadata.getStatus() != ProcessingStatus.SUMMARY_COMPLETE
					&& metadata.getStatus() != ProcessingStatus.RECOMMENDATIONS_QUEUED
					&& metadata.getStatus() != ProcessingStatus.GENERATING_RECOMMENDATIONS) {
				String errorMsg = "Cannot generate recommendations because recording status is " + metadata.getStatus()
						+ ", not ready for recommendations";
				log.error("[{}] {}", metadataId, errorMsg);
				return RecommendationResult.error("Invalid Status", errorMsg);
			}

			ProcessingStageClaimService.ClaimResult claim = stageClaimService.claim(metadataId,
					ProcessingStatus.GENERATING_RECOMMENDATIONS, "GENERATING_RECOMMENDATIONS",
					EnumSet.of(ProcessingStatus.SUMMARY_COMPLETE, ProcessingStatus.RECOMMENDATIONS_QUEUED),
					EnumSet.of(ProcessingStatus.COMPLETE, ProcessingStatus.COMPLETED_WITH_WARNINGS,
							ProcessingStatus.FAILED));
			if (!claim.claimed()) {
				log.info("[{}] Skipping recommendations because claim was not acquired: {}", metadataId,
						claim.reason());
				return RecommendationResult.success(0, "Recommendations already handled or not claimable");
			}
			metadata = claim.metadata();

			String recordingId = metadata.getRecordingId();
			if (recordingId == null || recordingId.isBlank()) {
				String errorMsg = "Recording ID is missing in metadata";
				log.error("[{}] {}", metadataId, errorMsg);
				markCompletedWithWarnings(metadataId, errorMsg);
				return RecommendationResult.error("Missing Recording", errorMsg);
			}

			List<LearningRecommendation> existingRecommendations = learningMaterialRecommenderService
					.getRecommendationsByRecordingId(recordingId);
			if (existingRecommendations != null && !existingRecommendations.isEmpty()) {
				log.info("[{}] Recording already has {} recommendations. Skipping generation to avoid duplicates.",
						metadataId, existingRecommendations.size());

				AudioMetadata latest = firebaseService.getAudioMetadataById(metadataId);
				if (latest == null || latest.getStatus() != ProcessingStatus.COMPLETED_WITH_WARNINGS) {
					Map<String, Object> completeUpdates = new HashMap<>();
					completeUpdates.put("status", ProcessingStatus.COMPLETE.name());
					completeUpdates.put("lastUpdated", Timestamp.now());
					firebaseService.updateData(firebaseService.getAudioMetadataCollectionName(), metadataId,
							completeUpdates);
					log.info("[{}] Updated metadata status to COMPLETE", metadataId);
				}

				return RecommendationResult.success(existingRecommendations.size(), "Used existing recommendations");
			}

			String summaryText = getSummaryText(metadata);
			if (summaryText == null || summaryText.isBlank()) {
				String errorMsg = "Summary text is not available";
				log.error("[{}] {}", metadataId, errorMsg);
				markCompletedWithWarnings(metadataId, errorMsg);
				return RecommendationResult.error("Missing Summary", errorMsg);
			}

			String transcriptText = metadata.getTranscriptText();
			if (transcriptText == null || transcriptText.isBlank()) {
				log.warn("[{}] Transcript text is not available, proceeding with summary only", metadataId);
			}

			String pdfText = null;
			if (!metadata.isAudioOnly()) {
				pdfText = getPdfText(metadata);
				if (pdfText == null || pdfText.isBlank()) {
					log.warn(
							"[{}] PDF text is not available for a non-audio-only recording, proceeding with summary and transcript only",
							metadataId);
				}
			} else {
				log.info("[{}] Audio-only recording detected, skipping PDF text retrieval", metadataId);
			}

			long generationStart = System.currentTimeMillis();
			List<LearningRecommendation> savedRecommendations = learningMaterialRecommenderService
					.generateAndSaveRecommendations(userId, recordingId, metadata.getSummaryId());
			log.info("[{}] Recommendation generation and save took {} ms", metadataId,
					System.currentTimeMillis() - generationStart);

			int savedCount = savedRecommendations != null ? savedRecommendations.size() : 0;
			log.info("[{}] Saved {} recommendations to database", metadataId, savedCount);

			AudioMetadata latest = firebaseService.getAudioMetadataById(metadataId);
			if (savedCount == 0) {
				String reason = "Recommendation generation completed without saved recommendations";
				if (latest == null || latest.getStatus() != ProcessingStatus.COMPLETED_WITH_WARNINGS) {
					markCompletedWithWarnings(metadataId, reason);
				}
				return RecommendationResult.warning(reason);
			}
			if (latest != null && latest.getStatus() == ProcessingStatus.COMPLETED_WITH_WARNINGS) {
				log.info("[{}] Preserving COMPLETED_WITH_WARNINGS status after recommendation generation", metadataId);
			} else {
				Map<String, Object> completeUpdates = new HashMap<>();
				completeUpdates.put("status", ProcessingStatus.COMPLETE.name());
				completeUpdates.put("lastUpdated", Timestamp.now());
				firebaseService.updateData(firebaseService.getAudioMetadataCollectionName(), metadataId,
						completeUpdates);
				log.info("[{}] Updated metadata status to COMPLETE and set lastUpdated timestamp", metadataId);
			}

			log.info("[{}] Recommendation process completed successfully", metadataId);
			return RecommendationResult.success(savedCount, "Successfully generated and saved recommendations");
		} catch (Exception e) {
			String errorMsg = "Unexpected error during recommendation: " + e.getMessage();
			log.error("[{}] {}", metadataId, errorMsg, e);
			markCompletedWithWarnings(metadataId, errorMsg);
			return RecommendationResult.error("Recommendation Error", errorMsg);
		}
	}

	private void markCompletedWithWarnings(String metadataId, String reason) {
		Map<String, Object> updates = new HashMap<>();
		updates.put("status", ProcessingStatus.COMPLETED_WITH_WARNINGS.name());
		updates.put("processingStage", "RECOMMENDATIONS_WARNING");
		updates.put("failureReason", reason);
		updates.put("lastUpdated", Timestamp.now());
		firebaseService.updateData(firebaseService.getAudioMetadataCollectionName(), metadataId, updates);
	}

	private String getSummaryText(AudioMetadata metadata) {
		if (metadata == null) {
			return null;
		}

		String summaryId = metadata.getSummaryId();
		if (summaryId != null && !summaryId.isBlank()) {
			try {
				Map<String, Object> summaryData = firebaseService.getData("summaries", summaryId);
				if (summaryData != null) {
					String formattedSummaryText = (String) summaryData.get("formattedSummaryText");
					if (formattedSummaryText != null && !formattedSummaryText.isBlank()) {
						log.info("[{}] Retrieved formattedSummaryText from summaries/{}", metadata.getId(), summaryId);
						return formattedSummaryText;
					}
				}
			} catch (Exception e) {
				log.warn("[{}] Error retrieving summary document: {}", metadata.getId(), e.getMessage());
			}
		}

		log.warn("[{}] No summary found. Falling back to transcript text.", metadata.getId());
		return metadata.getTranscriptText();
	}

	private String getPdfText(AudioMetadata metadata) {
		if (metadata == null) {
			return null;
		}

		String recordingId = metadata.getRecordingId();
		if (recordingId != null && !recordingId.isBlank()) {
			try {
				Map<String, Object> recordingData = firebaseService.getData("recordings", recordingId);
				if (recordingData != null) {
					String pdfText = (String) recordingData.get("pdfText");
					if (pdfText != null && !pdfText.isBlank()) {
						log.info("[{}] Retrieved pdfText from recordings/{}", metadata.getId(), recordingId);
						return pdfText;
					}
				}
			} catch (Exception e) {
				log.warn("[{}] Error retrieving PDF text: {}", metadata.getId(), e.getMessage());
			}
		}

		log.warn("[{}] No PDF text found.", metadata.getId());
		return "";
	}

	private String errorResponseToString(String errorType, String errorMessage) {
		try {
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("status", "error");
			errorResponse.put("type", errorType);
			errorResponse.put("message", errorMessage);
			return objectMapper.writeValueAsString(errorResponse);
		} catch (JsonProcessingException e) {
			log.error("Error creating error response JSON: {}", e.getMessage(), e);
			return "{\"status\":\"error\",\"message\":\"" + errorMessage + "\"}";
		}
	}

	private String successResponseToString(int count, String message) {
		try {
			Map<String, Object> response = new HashMap<>();
			response.put("status", "success");
			response.put("count", count);
			response.put("message", message);
			return objectMapper.writeValueAsString(response);
		} catch (JsonProcessingException e) {
			return "{\"status\":\"success\",\"count\":" + count + "}";
		}
	}
}
