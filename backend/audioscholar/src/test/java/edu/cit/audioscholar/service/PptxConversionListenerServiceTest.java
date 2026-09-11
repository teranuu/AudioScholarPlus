package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.dto.AudioProcessingMessage;
import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;

class PptxConversionListenerServiceTest {

	@Test
	void handlePptxConversion_usesProviderAndStoresGeneratedNhostPdfFields() throws Exception {
		FirebaseService firebaseService = org.mockito.Mockito.mock(FirebaseService.class);
		NhostStorageService nhostStorageService = org.mockito.Mockito.mock(NhostStorageService.class);
		PptxConversionProvider conversionProvider = org.mockito.Mockito.mock(PptxConversionProvider.class);
		RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
		PptxConversionListenerService service = new PptxConversionListenerService(firebaseService, nhostStorageService,
				conversionProvider, rabbitTemplate, new ObjectMapper());

		AudioMetadata metadata = new AudioMetadata();
		metadata.setId("metadata-1");
		metadata.setUserId("user-1");
		metadata.setStatus(ProcessingStatus.UPLOAD_IN_PROGRESS);
		metadata.setNhostPptxFileId("pptx-file-id");
		metadata.setTranscriptionComplete(false);
		metadata.setPdfConversionComplete(false);

		AudioMetadata convertedMetadata = new AudioMetadata();
		convertedMetadata.setId("metadata-1");
		convertedMetadata.setUserId("user-1");
		convertedMetadata.setStatus(ProcessingStatus.PDF_CONVERSION_COMPLETE);
		convertedMetadata.setNhostPptxFileId("pptx-file-id");
		convertedMetadata.setGeneratedPdfNhostFileId("pdf-file-id");
		convertedMetadata.setGeneratedPdfUrl("https://storage.test/v1/files/pdf-file-id");
		convertedMetadata.setPdfConversionComplete(true);

		when(firebaseService.getAudioMetadataCollectionName()).thenReturn("audioMetadata");
		when(firebaseService.getData("audioMetadata", "metadata-1")).thenReturn(metadata.toMap())
				.thenReturn(metadata.toMap()).thenReturn(convertedMetadata.toMap());
		when(nhostStorageService.getPublicUrl("pptx-file-id")).thenReturn("https://storage.test/v1/files/pptx-file-id");
		when(conversionProvider.convert(any(AudioMetadata.class)))
				.thenReturn(new PptxConversionResult("pdf-file-id", "https://storage.test/v1/files/pdf-file-id",
						"local-poi-pdfbox"));

		AudioProcessingMessage message = new AudioProcessingMessage();
		message.setMetadataId("metadata-1");
		message.setUserId("user-1");

		service.handlePptxConversion(message);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> updatesCaptor = ArgumentCaptor.forClass(Map.class);
		verify(firebaseService, org.mockito.Mockito.atLeastOnce()).updateDataWithMap(eq("audioMetadata"),
				eq("metadata-1"), updatesCaptor.capture());

		assertTrue(updatesCaptor.getAllValues().stream()
				.anyMatch(updates -> "pdf-file-id".equals(updates.get("generatedPdfNhostFileId"))
						&& "https://storage.test/v1/files/pdf-file-id".equals(updates.get("generatedPdfUrl"))
						&& Boolean.TRUE.equals(updates.get("pdfConversionComplete"))));
		assertFalse(updatesCaptor.getAllValues().stream().anyMatch(updates -> updates.containsKey("convertApiPdfUrl")));
		verify(rabbitTemplate, never()).convertAndSend(eq("audio.exchange"), eq("summarization.process.key"),
				anyMap());
	}
}
