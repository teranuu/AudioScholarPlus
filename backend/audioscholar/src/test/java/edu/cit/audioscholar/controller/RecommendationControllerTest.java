package edu.cit.audioscholar.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.LearningRecommendation;
import edu.cit.audioscholar.model.Recording;
import edu.cit.audioscholar.service.FirebaseService;
import edu.cit.audioscholar.service.LearningMaterialRecommenderService;
import edu.cit.audioscholar.service.RecordingService;

class RecommendationControllerTest {

	private LearningMaterialRecommenderService recommenderService;
	private RecordingService recordingService;
	private FirebaseService firebaseService;
	private RecommendationController controller;
	private Authentication authentication;

	@BeforeEach
	void setUp() {
		recommenderService = mock(LearningMaterialRecommenderService.class);
		recordingService = mock(RecordingService.class);
		firebaseService = mock(FirebaseService.class);
		controller = new RecommendationController(recommenderService, recordingService, firebaseService);
		authentication = mock(Authentication.class);
		when(authentication.getName()).thenReturn("user-1");
	}

	@Test
	void returnsRecommendationsForOwnedRecording() throws Exception {
		Recording recording = ownedRecording("recording-1", "user-1");
		LearningRecommendation recommendation = new LearningRecommendation();
		when(recordingService.getRecordingById("recording-1")).thenReturn(recording);
		when(recommenderService.getRecommendationsByRecordingId("recording-1")).thenReturn(List.of(recommendation));

		ResponseEntity<List<LearningRecommendation>> response = controller.getRecommendationsForRecording("recording-1",
				authentication);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals(1, response.getBody().size());
	}

	@Test
	void returnsOkEmptyListForOwnedRecordingWithoutRecommendations() throws Exception {
		when(recordingService.getRecordingById("recording-1")).thenReturn(ownedRecording("recording-1", "user-1"));
		when(recommenderService.getRecommendationsByRecordingId("recording-1")).thenReturn(List.of());

		ResponseEntity<List<LearningRecommendation>> response = controller.getRecommendationsForRecording("recording-1",
				authentication);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals(List.of(), response.getBody());
	}

	@Test
	void returnsForbiddenForWrongOwner() throws Exception {
		when(recordingService.getRecordingById("recording-1")).thenReturn(ownedRecording("recording-1", "user-2"));

		ResponseStatusException error = assertThrows(ResponseStatusException.class,
				() -> controller.getRecommendationsForRecording("recording-1", authentication));

		assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
	}

	@Test
	void returnsNotFoundWhenRecordingAndMetadataAreMissing() throws Exception {
		when(recordingService.getRecordingById("recording-1")).thenReturn(null);
		when(firebaseService.getAudioMetadataByRecordingId("recording-1")).thenReturn(null);

		ResponseStatusException error = assertThrows(ResponseStatusException.class,
				() -> controller.getRecommendationsForRecording("recording-1", authentication));

		assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
	}

	@Test
	void allowsOwnedMetadataWhenRecordingDocumentIsMissing() throws Exception {
		AudioMetadata metadata = new AudioMetadata();
		metadata.setUserId("user-1");
		when(recordingService.getRecordingById("recording-1")).thenReturn(null);
		when(firebaseService.getAudioMetadataByRecordingId("recording-1")).thenReturn(metadata);
		when(recommenderService.getRecommendationsByRecordingId("recording-1")).thenReturn(List.of());

		ResponseEntity<List<LearningRecommendation>> response = controller.getRecommendationsForRecording("recording-1",
				authentication);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals(List.of(), response.getBody());
	}

	private Recording ownedRecording(String recordingId, String userId) {
		Recording recording = new Recording();
		recording.setRecordingId(recordingId);
		recording.setUserId(userId);
		return recording;
	}
}
