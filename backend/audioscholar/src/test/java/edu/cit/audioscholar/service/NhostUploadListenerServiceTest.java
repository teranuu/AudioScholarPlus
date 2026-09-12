package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.dto.NhostUploadMessage;
import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;

class NhostUploadListenerServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void handleNhostUploadRequest_publishTimeoutDoesNotMarkFailed() throws Exception {
		FirebaseService firebaseService = mock(FirebaseService.class);
		NhostStorageService nhostStorageService = mock(NhostStorageService.class);
		RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
		ConfirmedRabbitPublisher confirmedRabbitPublisher = mock(ConfirmedRabbitPublisher.class);
		NhostUploadListenerService service = new NhostUploadListenerService(firebaseService, nhostStorageService,
				rabbitTemplate, confirmedRabbitPublisher, new ObjectMapper());

		Path audioFile = tempDir.resolve("lecture.mp3");
		Files.writeString(audioFile, "audio");

		AudioMetadata metadata = new AudioMetadata();
		metadata.setId("metadata-1");
		metadata.setUserId("user-1");
		metadata.setStatus(ProcessingStatus.UPLOAD_IN_PROGRESS);
		metadata.setFileName("lecture.mp3");

		when(firebaseService.getAudioMetadataById("metadata-1")).thenReturn(metadata);
		when(firebaseService.getAudioMetadataCollectionName()).thenReturn("audioMetadata");
		when(nhostStorageService.uploadFile(any(), eq("lecture.mp3"), eq("audio/mpeg"))).thenReturn("nhost-file-1");
		when(nhostStorageService.getPublicUrl("nhost-file-1")).thenReturn("https://storage.test/v1/files/nhost-file-1");
		doThrow(new RabbitPublishTimeoutException("confirm pending", null)).when(confirmedRabbitPublisher)
				.publishToProcessingExchange(eq("audio.transcription.key"), any());

		service.handleNhostUploadRequest(uploadMessage(audioFile), null);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> updatesCaptor = ArgumentCaptor.forClass(Map.class);
		verify(firebaseService, org.mockito.Mockito.atLeastOnce()).updateDataWithMap(eq("audioMetadata"),
				eq("metadata-1"), updatesCaptor.capture());

		assertTrue(updatesCaptor.getAllValues().stream()
				.noneMatch(updates -> ProcessingStatus.FAILED.name().equals(updates.get("status"))));
		assertTrue(updatesCaptor.getAllValues().stream()
				.anyMatch(updates -> ProcessingStatus.PROCESSING_QUEUED.name().equals(updates.get("status"))
						&& "TRANSCRIPTION_CONFIRM_TIMEOUT".equals(updates.get("processingStage"))));
	}

	@Test
	void handleNhostUploadRequest_publishRejectedRecordsQueueFailure() throws Exception {
		FirebaseService firebaseService = mock(FirebaseService.class);
		NhostStorageService nhostStorageService = mock(NhostStorageService.class);
		RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
		ConfirmedRabbitPublisher confirmedRabbitPublisher = mock(ConfirmedRabbitPublisher.class);
		NhostUploadListenerService service = new NhostUploadListenerService(firebaseService, nhostStorageService,
				rabbitTemplate, confirmedRabbitPublisher, new ObjectMapper());

		Path audioFile = tempDir.resolve("lecture-rejected.mp3");
		Files.writeString(audioFile, "audio");

		AudioMetadata metadata = new AudioMetadata();
		metadata.setId("metadata-1");
		metadata.setUserId("user-1");
		metadata.setStatus(ProcessingStatus.UPLOAD_IN_PROGRESS);
		metadata.setFileName("lecture.mp3");

		when(firebaseService.getAudioMetadataById("metadata-1")).thenReturn(metadata);
		when(firebaseService.getAudioMetadataCollectionName()).thenReturn("audioMetadata");
		when(nhostStorageService.uploadFile(any(), eq("lecture.mp3"), eq("audio/mpeg"))).thenReturn("nhost-file-1");
		when(nhostStorageService.getPublicUrl("nhost-file-1")).thenReturn("https://storage.test/v1/files/nhost-file-1");
		doThrow(new RabbitPublishRejectedException("NO_ROUTE")).when(confirmedRabbitPublisher)
				.publishToProcessingExchange(eq("audio.transcription.key"), any());

		service.handleNhostUploadRequest(uploadMessage(audioFile), null);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> updatesCaptor = ArgumentCaptor.forClass(Map.class);
		verify(firebaseService, org.mockito.Mockito.atLeastOnce()).updateDataWithMap(eq("audioMetadata"),
				eq("metadata-1"), updatesCaptor.capture());

		assertTrue(updatesCaptor.getAllValues().stream()
				.anyMatch(updates -> ProcessingStatus.FAILED.name().equals(updates.get("status"))
						&& "TRANSCRIPTION_QUEUE_REJECTED".equals(updates.get("processingStage"))));
	}

	private NhostUploadMessage uploadMessage(Path tempFile) {
		NhostUploadMessage message = new NhostUploadMessage();
		message.setMetadataId("metadata-1");
		message.setFileType("audio");
		message.setTempFilePath(tempFile.toString());
		message.setOriginalFilename("lecture.mp3");
		message.setOriginalContentType("audio/mpeg");
		return message;
	}
}
