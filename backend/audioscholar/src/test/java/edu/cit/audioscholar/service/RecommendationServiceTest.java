package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;

class RecommendationServiceTest {

	private FirebaseService firebaseService;
	private LearningMaterialRecommenderService recommenderService;
	private ProcessingStageClaimService stageClaimService;
	private RecommendationService service;

	@BeforeEach
	void setUp() {
		firebaseService = mock(FirebaseService.class);
		recommenderService = mock(LearningMaterialRecommenderService.class);
		stageClaimService = mock(ProcessingStageClaimService.class);
		service = new RecommendationService();
		ReflectionTestUtils.setField(service, "firebaseService", firebaseService);
		ReflectionTestUtils.setField(service, "learningMaterialRecommenderService", recommenderService);
		ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
		ReflectionTestUtils.setField(service, "stageClaimService", stageClaimService);
		when(firebaseService.getAudioMetadataCollectionName()).thenReturn("audio_metadata");
	}

	@Test
	void zeroGeneratedRecommendationsMarksCompletedWithWarnings() {
		AudioMetadata initial = metadata(ProcessingStatus.RECOMMENDATIONS_QUEUED);
		AudioMetadata claimed = metadata(ProcessingStatus.GENERATING_RECOMMENDATIONS);
		when(firebaseService.getAudioMetadataById("metadata-1")).thenReturn(initial, claimed);
		when(stageClaimService.claim(eq("metadata-1"), eq(ProcessingStatus.GENERATING_RECOMMENDATIONS), any(), any(),
				any())).thenReturn(ProcessingStageClaimService.ClaimResult.acquired(claimed));
		when(firebaseService.getData("summaries", "summary-1")).thenReturn(Map.of("formattedSummaryText", "Summary"));
		when(recommenderService.getRecommendationsByRecordingId("recording-1")).thenReturn(List.of());
		when(recommenderService.generateAndSaveRecommendations("user-1", "recording-1", "summary-1"))
				.thenReturn(List.of());

		RecommendationService.RecommendationResult result = service.recommendAndSaveResult("metadata-1", "user-1");

		assertFalse(result.success());
		assertEquals(0, result.count());
		verify(firebaseService).updateData(eq("audio_metadata"), eq("metadata-1"), any(Map.class));
	}

	private AudioMetadata metadata(ProcessingStatus status) {
		AudioMetadata metadata = new AudioMetadata();
		metadata.setId("metadata-1");
		metadata.setUserId("user-1");
		metadata.setRecordingId("recording-1");
		metadata.setSummaryId("summary-1");
		metadata.setStatus(status);
		metadata.setAudioOnly(true);
		return metadata;
	}
}
