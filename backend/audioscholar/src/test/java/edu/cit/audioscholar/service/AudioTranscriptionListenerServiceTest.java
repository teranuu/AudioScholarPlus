package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.cache.CacheManager;

import edu.cit.audioscholar.config.RabbitMQConfig;
import edu.cit.audioscholar.dto.AudioProcessingMessage;
import edu.cit.audioscholar.exception.FirestoreInteractionException;
import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;
import edu.cit.audioscholar.util.RobustTaskExecutor;

class AudioTranscriptionListenerServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void missingMetadataAcknowledgesStaleMessageWithoutProcessing() throws Exception {
		FirebaseService firebaseService = mock(FirebaseService.class);
		NhostStorageService storageService = mock(NhostStorageService.class);
		GeminiService geminiService = mock(GeminiService.class);
		RecordingService recordingService = mock(RecordingService.class);
		QualityReportService qualityReportService = mock(QualityReportService.class);
		RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
		when(firebaseService.getAudioMetadataById("metadata-1")).thenReturn(null);

		AudioTranscriptionListenerService service = service(firebaseService, storageService, geminiService,
				recordingService, qualityReportService, rabbitTemplate, 1);

		assertDoesNotThrow(() -> service.handleAudioTranscriptionRequest(message("metadata-1", "user-1")));

		verify(geminiService, never()).callGeminiTranscriptionAPIWithFallback(any(), any());
		verify(storageService, never()).downloadFileToPath(any(), any());
		verify(firebaseService, never()).updateDataWithMap(any(), any(), any());
	}

	@Test
	void missingRecordingMarksMetadataFailedWithoutTranscribing() throws Exception {
		FirebaseService firebaseService = mock(FirebaseService.class);
		NhostStorageService storageService = mock(NhostStorageService.class);
		GeminiService geminiService = mock(GeminiService.class);
		RecordingService recordingService = mock(RecordingService.class);
		QualityReportService qualityReportService = mock(QualityReportService.class);
		RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
		when(firebaseService.getAudioMetadataCollectionName()).thenReturn("audioMetadata");
		when(firebaseService.getAudioMetadataById("metadata-1")).thenReturn(activeMetadata("metadata-1", "user-1"));
		when(recordingService.getRecordingById("metadata-1")).thenReturn(null);

		AudioTranscriptionListenerService service = service(firebaseService, storageService, geminiService,
				recordingService, qualityReportService, rabbitTemplate, 1);

		assertDoesNotThrow(() -> service.handleAudioTranscriptionRequest(message("metadata-1", "user-1")));

		ArgumentCaptor<Map<String, Object>> updates = ArgumentCaptor.forClass(Map.class);
		verify(firebaseService, atLeastOnce()).updateDataWithMap(eq("audioMetadata"), eq("metadata-1"),
				updates.capture());
		Map<String, Object> failureUpdate = updates.getAllValues().stream()
				.filter(update -> ProcessingStatus.FAILED.name().equals(update.get("status"))).findFirst()
				.orElseThrow();
		org.junit.jupiter.api.Assertions.assertEquals("RECORDING_NOT_FOUND", failureUpdate.get("failureReason"));
		verify(geminiService, never()).callGeminiTranscriptionAPIWithFallback(any(), any());
		verify(storageService, never()).downloadFileToPath(any(), any());
	}

	@Test
	void completedMetadataTriggersSummarizationCheckWithoutRetranscribing() throws Exception {
		FirebaseService firebaseService = mock(FirebaseService.class);
		NhostStorageService storageService = mock(NhostStorageService.class);
		GeminiService geminiService = mock(GeminiService.class);
		RecordingService recordingService = mock(RecordingService.class);
		QualityReportService qualityReportService = mock(QualityReportService.class);
		RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
		when(firebaseService.getAudioMetadataCollectionName()).thenReturn("audioMetadata");
		AudioMetadata metadata = completeMetadata("metadata-1", "user-1");
		when(firebaseService.getAudioMetadataById("metadata-1")).thenReturn(metadata, metadata, metadata);

		AudioTranscriptionListenerService service = service(firebaseService, storageService, geminiService,
				recordingService, qualityReportService, rabbitTemplate, 1);

		assertDoesNotThrow(() -> service.handleAudioTranscriptionRequest(message("metadata-1", "user-1")));

		verify(geminiService, never()).callGeminiTranscriptionAPIWithFallback(any(), any());
		verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.PROCESSING_EXCHANGE_NAME),
				eq(RabbitMQConfig.SUMMARIZATION_ROUTING_KEY), any(Map.class));
	}

	@Test
	void transientMetadataLookupFailureStillRetries() throws Exception {
		FirebaseService firebaseService = mock(FirebaseService.class);
		NhostStorageService storageService = mock(NhostStorageService.class);
		GeminiService geminiService = mock(GeminiService.class);
		RecordingService recordingService = mock(RecordingService.class);
		QualityReportService qualityReportService = mock(QualityReportService.class);
		RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
		when(firebaseService.getAudioMetadataById("metadata-1"))
				.thenThrow(new FirestoreInteractionException("temporary Firestore failure"))
				.thenReturn(skippedMetadata("metadata-1", "user-1"));

		AudioTranscriptionListenerService service = service(firebaseService, storageService, geminiService,
				recordingService, qualityReportService, rabbitTemplate, 2);

		assertDoesNotThrow(() -> service.handleAudioTranscriptionRequest(message("metadata-1", "user-1")));

		verify(firebaseService, org.mockito.Mockito.times(2)).getAudioMetadataById("metadata-1");
		verify(geminiService, never()).callGeminiTranscriptionAPIWithFallback(any(), any());
	}

	private AudioTranscriptionListenerService service(FirebaseService firebaseService,
			NhostStorageService storageService, GeminiService geminiService, RecordingService recordingService,
			QualityReportService qualityReportService, RabbitTemplate rabbitTemplate, int robustAttempts) {
		return new AudioTranscriptionListenerService(firebaseService, storageService, geminiService, recordingService,
				qualityReportService, mock(AudioProcessingGuardrailService.class), mock(CacheManager.class),
				tempDir.toString(), rabbitTemplate, new RobustTaskExecutor(robustAttempts, 0, 0), 1, 0);
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
