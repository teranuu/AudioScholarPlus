package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.CacheManager;

import edu.cit.audioscholar.config.RabbitMQConfig;
import edu.cit.audioscholar.dto.AudioProcessingMessage;
import edu.cit.audioscholar.exception.FirestoreInteractionException;
import edu.cit.audioscholar.exception.GeminiRateLimitException;
import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;
import edu.cit.audioscholar.model.QualityReport;
import edu.cit.audioscholar.model.Recording;
import edu.cit.audioscholar.util.RobustTaskExecutor;

class AudioTranscriptionListenerServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void missingMetadataAcknowledgesStaleMessageWithoutProcessing() throws Exception {
		FirebaseService firebaseService = mock(FirebaseService.class);
		NhostStorageService storageService = mock(NhostStorageService.class);
		TranscriptionOrchestrator transcriptionOrchestrator = mock(TranscriptionOrchestrator.class);
		RecordingService recordingService = mock(RecordingService.class);
		QualityReportService qualityReportService = mock(QualityReportService.class);
		ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
		when(firebaseService.getAudioMetadataById("metadata-1")).thenReturn(null);

		AudioTranscriptionListenerService service = service(firebaseService, storageService, transcriptionOrchestrator,
				recordingService, qualityReportService, publisher, 1);

		assertDoesNotThrow(() -> service.handleAudioTranscriptionRequest(message("metadata-1", "user-1")));

		verify(transcriptionOrchestrator, never()).transcribe(any(), any(), any());
		verify(storageService, never()).downloadFileToPath(any(), any());
		verify(firebaseService, never()).updateDataWithMap(any(), any(), any());
	}

	@Test
	void missingRecordingMarksMetadataFailedWithoutTranscribing() throws Exception {
		FirebaseService firebaseService = mock(FirebaseService.class);
		NhostStorageService storageService = mock(NhostStorageService.class);
		TranscriptionOrchestrator transcriptionOrchestrator = mock(TranscriptionOrchestrator.class);
		RecordingService recordingService = mock(RecordingService.class);
		QualityReportService qualityReportService = mock(QualityReportService.class);
		ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
		when(firebaseService.getAudioMetadataCollectionName()).thenReturn("audioMetadata");
		when(firebaseService.getAudioMetadataById("metadata-1")).thenReturn(activeMetadata("metadata-1", "user-1"));
		when(recordingService.getRecordingById("metadata-1")).thenReturn(null);

		AudioTranscriptionListenerService service = service(firebaseService, storageService, transcriptionOrchestrator,
				recordingService, qualityReportService, publisher, 1);

		assertDoesNotThrow(() -> service.handleAudioTranscriptionRequest(message("metadata-1", "user-1")));

		ArgumentCaptor<Map<String, Object>> updates = ArgumentCaptor.forClass(Map.class);
		verify(firebaseService, atLeastOnce()).updateDataWithMap(eq("audioMetadata"), eq("metadata-1"),
				updates.capture());
		Map<String, Object> failureUpdate = updates.getAllValues().stream()
				.filter(update -> ProcessingStatus.FAILED.name().equals(update.get("status"))).findFirst()
				.orElseThrow();
		org.junit.jupiter.api.Assertions.assertEquals("RECORDING_NOT_FOUND", failureUpdate.get("failureReason"));
		verify(transcriptionOrchestrator, never()).transcribe(any(), any(), any());
		verify(storageService, never()).downloadFileToPath(any(), any());
	}

	@Test
	void completedMetadataTriggersSummarizationCheckWithoutRetranscribing() throws Exception {
		FirebaseService firebaseService = mock(FirebaseService.class);
		NhostStorageService storageService = mock(NhostStorageService.class);
		TranscriptionOrchestrator transcriptionOrchestrator = mock(TranscriptionOrchestrator.class);
		RecordingService recordingService = mock(RecordingService.class);
		QualityReportService qualityReportService = mock(QualityReportService.class);
		ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
		when(firebaseService.getAudioMetadataCollectionName()).thenReturn("audioMetadata");
		AudioMetadata metadata = completeMetadata("metadata-1", "user-1");
		when(firebaseService.getAudioMetadataById("metadata-1")).thenReturn(metadata, metadata, metadata);

		AudioTranscriptionListenerService service = service(firebaseService, storageService, transcriptionOrchestrator,
				recordingService, qualityReportService, publisher, 1);

		assertDoesNotThrow(() -> service.handleAudioTranscriptionRequest(message("metadata-1", "user-1")));

		verify(transcriptionOrchestrator, never()).transcribe(any(), any(), any());
		verify(publisher).publishToProcessingExchange(eq(RabbitMQConfig.SUMMARIZATION_ROUTING_KEY), any(Map.class));
	}

	@Test
	void transientMetadataLookupFailureStillRetries() throws Exception {
		FirebaseService firebaseService = mock(FirebaseService.class);
		NhostStorageService storageService = mock(NhostStorageService.class);
		TranscriptionOrchestrator transcriptionOrchestrator = mock(TranscriptionOrchestrator.class);
		RecordingService recordingService = mock(RecordingService.class);
		QualityReportService qualityReportService = mock(QualityReportService.class);
		ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
		when(firebaseService.getAudioMetadataById("metadata-1"))
				.thenThrow(new FirestoreInteractionException("temporary Firestore failure"))
				.thenReturn(skippedMetadata("metadata-1", "user-1"));

		AudioTranscriptionListenerService service = service(firebaseService, storageService, transcriptionOrchestrator,
				recordingService, qualityReportService, publisher, 2);

		assertDoesNotThrow(() -> service.handleAudioTranscriptionRequest(message("metadata-1", "user-1")));

		verify(firebaseService, org.mockito.Mockito.times(2)).getAudioMetadataById("metadata-1");
		verify(transcriptionOrchestrator, never()).transcribe(any(), any(), any());
	}

	@Test
	void exhaustedGeminiRateLimitMarksTranscriptionFailed() throws Exception {
		FirebaseService firebaseService = mock(FirebaseService.class);
		NhostStorageService storageService = mock(NhostStorageService.class);
		TranscriptionOrchestrator transcriptionOrchestrator = mock(TranscriptionOrchestrator.class);
		RecordingService recordingService = mock(RecordingService.class);
		QualityReportService qualityReportService = mock(QualityReportService.class);
		ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
		AudioMetadata metadata = activeMetadata("metadata-1", "user-1");
		when(firebaseService.getAudioMetadataCollectionName()).thenReturn("audioMetadata");
		when(firebaseService.getAudioMetadataById("metadata-1")).thenReturn(metadata, metadata, metadata);
		when(recordingService.getRecordingById("metadata-1")).thenReturn(recording("metadata-1", "user-1"));
		doAnswer(invocation -> {
			Path target = invocation.getArgument(1);
			java.nio.file.Files.writeString(target, "audio bytes");
			return null;
		}).when(storageService).downloadFileToPath(any(), any());
		when(qualityReportService.analyzeAndSave(eq("metadata-1"), any()))
				.thenReturn(QualityReport.allClear("metadata-1"));
		Instant retryAt = Instant.now().plusSeconds(30);
		when(transcriptionOrchestrator.transcribe(eq("metadata-1"), any(), any())).thenThrow(
				new GeminiRateLimitException("Gemini transcription service is temporarily unavailable", retryAt, null));

		AudioTranscriptionListenerService service = service(firebaseService, storageService, transcriptionOrchestrator,
				recordingService, qualityReportService, publisher, 1);

		assertDoesNotThrow(() -> service.handleAudioTranscriptionRequest(message("metadata-1", "user-1")));

		ArgumentCaptor<Map<String, Object>> updates = ArgumentCaptor.forClass(Map.class);
		verify(firebaseService, atLeastOnce()).updateDataWithMap(eq("audioMetadata"), eq("metadata-1"),
				updates.capture());
		Map<String, Object> failureUpdate = updates.getAllValues().stream()
				.filter(update -> "TRANSCRIPTION_FAILED".equals(update.get("processingStage"))).findFirst()
				.orElseThrow();
		org.junit.jupiter.api.Assertions.assertEquals(ProcessingStatus.FAILED.name(), failureUpdate.get("status"));
		org.junit.jupiter.api.Assertions.assertEquals(false, failureUpdate.get("transcriptionComplete"));
		org.junit.jupiter.api.Assertions.assertEquals("Gemini transcription service is temporarily unavailable",
				failureUpdate.get("failureReason"));
		org.junit.jupiter.api.Assertions.assertNotNull(failureUpdate.get("quotaRetryAt"));
	}

	@Test
	void successfulTranscriptionStillSavesTranscriptAndQueuesSummarization() throws Exception {
		FirebaseService firebaseService = mock(FirebaseService.class);
		NhostStorageService storageService = mock(NhostStorageService.class);
		TranscriptionOrchestrator transcriptionOrchestrator = mock(TranscriptionOrchestrator.class);
		RecordingService recordingService = mock(RecordingService.class);
		QualityReportService qualityReportService = mock(QualityReportService.class);
		ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
		AudioMetadata active = activeMetadata("metadata-1", "user-1");
		AudioMetadata complete = completeMetadata("metadata-1", "user-1");
		when(firebaseService.getAudioMetadataCollectionName()).thenReturn("audioMetadata");
		when(firebaseService.getAudioMetadataById("metadata-1")).thenReturn(active, active, active, complete, complete);
		when(recordingService.getRecordingById("metadata-1")).thenReturn(recording("metadata-1", "user-1"));
		doAnswer(invocation -> {
			Path target = invocation.getArgument(1);
			java.nio.file.Files.writeString(target, "audio bytes");
			return null;
		}).when(storageService).downloadFileToPath(any(), any());
		when(qualityReportService.analyzeAndSave(eq("metadata-1"), any()))
				.thenReturn(QualityReport.allClear("metadata-1"));
		when(transcriptionOrchestrator.transcribe(eq("metadata-1"), any(), any())).thenReturn("lecture transcript");

		AudioTranscriptionListenerService service = service(firebaseService, storageService, transcriptionOrchestrator,
				recordingService, qualityReportService, publisher, 1);

		assertDoesNotThrow(() -> service.handleAudioTranscriptionRequest(message("metadata-1", "user-1")));

		ArgumentCaptor<Map<String, Object>> updates = ArgumentCaptor.forClass(Map.class);
		verify(firebaseService, atLeastOnce()).updateDataWithMap(eq("audioMetadata"), eq("metadata-1"),
				updates.capture());
		Map<String, Object> successUpdate = updates.getAllValues().stream()
				.filter(update -> ProcessingStatus.TRANSCRIPTION_COMPLETE.name().equals(update.get("status")))
				.findFirst().orElseThrow();
		org.junit.jupiter.api.Assertions.assertEquals("lecture transcript", successUpdate.get("transcriptText"));
		org.junit.jupiter.api.Assertions.assertEquals(true, successUpdate.get("transcriptionComplete"));
		verify(publisher).publishToProcessingExchange(eq(RabbitMQConfig.SUMMARIZATION_ROUTING_KEY), any(Map.class));
	}

	private AudioTranscriptionListenerService service(FirebaseService firebaseService,
			NhostStorageService storageService, TranscriptionOrchestrator transcriptionOrchestrator,
			RecordingService recordingService, QualityReportService qualityReportService,
			ConfirmedRabbitPublisher publisher, int robustAttempts) {
		ProcessingStageClaimService stageClaimService = mock(ProcessingStageClaimService.class);
		when(stageClaimService.claim(any(), any(), any(), any(), any()))
				.thenReturn(ProcessingStageClaimService.ClaimResult.acquired(activeMetadata("metadata-1", "user-1")));
		return new AudioTranscriptionListenerService(firebaseService, storageService, transcriptionOrchestrator,
				recordingService, qualityReportService, mock(AudioProcessingGuardrailService.class),
				mock(CacheManager.class), tempDir.toString(), publisher, new RobustTaskExecutor(robustAttempts, 0, 0),
				stageClaimService, 1, 0);
	}

	private AudioProcessingMessage message(String metadataId, String userId) {
		AudioProcessingMessage message = new AudioProcessingMessage();
		message.setMetadataId(metadataId);
		message.setUserId(userId);
		message.setNhostFileId("nhost-file-1");
		return message;
	}

	private AudioMetadata activeMetadata(String metadataId, String userId) {
		AudioMetadata metadata = new AudioMetadata();
		metadata.setId(metadataId);
		metadata.setUserId(userId);
		metadata.setRecordingId(metadataId);
		metadata.setStatus(ProcessingStatus.PROCESSING_QUEUED);
		metadata.setTranscriptionComplete(false);
		return metadata;
	}

	private Recording recording(String metadataId, String userId) {
		Recording recording = new Recording(metadataId, userId, "Lecture",
				"https://storage.example.com/files/00000000-0000-0000-0000-000000000001");
		recording.setFileName("lecture.mp3");
		return recording;
	}

	private AudioMetadata completeMetadata(String metadataId, String userId) {
		AudioMetadata metadata = activeMetadata(metadataId, userId);
		metadata.setStatus(ProcessingStatus.TRANSCRIPTION_COMPLETE);
		metadata.setTranscriptionComplete(true);
		metadata.setAudioOnly(true);
		metadata.setPdfConversionComplete(true);
		return metadata;
	}

	private AudioMetadata skippedMetadata(String metadataId, String userId) {
		AudioMetadata metadata = activeMetadata(metadataId, userId);
		metadata.setStatus(ProcessingStatus.FAILED);
		return metadata;
	}
}
