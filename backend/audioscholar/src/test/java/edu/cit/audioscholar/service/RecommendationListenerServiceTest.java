package edu.cit.audioscholar.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.model.AudioMetadata;

class RecommendationListenerServiceTest {

	private RecommendationService recommendationService;
	private FirebaseService firebaseService;
	private RecommendationListenerService listener;

	@BeforeEach
	void setUp() {
		recommendationService = mock(RecommendationService.class);
		firebaseService = mock(FirebaseService.class);
		listener = new RecommendationListenerService(recommendationService, firebaseService, new ObjectMapper());
		when(recommendationService.recommendAndSaveResult("metadata-1", "user-1"))
				.thenReturn(RecommendationService.RecommendationResult.success(1, "ok"));
	}

	@Test
	void handlesMapPayload() {
		listener.handleRecommendationRequest(Map.of("metadataId", "metadata-1", "userId", "user-1"));

		verify(recommendationService).recommendAndSaveResult("metadata-1", "user-1");
	}

	@Test
	void handlesJsonStringPayload() {
		listener.handleRecommendationRequest("{\"metadataId\":\"metadata-1\",\"userId\":\"user-1\"}");

		verify(recommendationService).recommendAndSaveResult("metadata-1", "user-1");
	}

	@Test
	void handlesByteArrayPayloadFromRabbit() {
		byte[] payload = "{\"metadataId\":\"metadata-1\",\"userId\":\"user-1\"}".getBytes(StandardCharsets.UTF_8);

		listener.handleRecommendationRequest(payload);

		verify(recommendationService).recommendAndSaveResult("metadata-1", "user-1");
	}

	@Test
	void handlesAmqpMessagePayloadFromRabbit() {
		byte[] payload = "{\"metadataId\":\"metadata-1\",\"userId\":\"user-1\"}".getBytes(StandardCharsets.UTF_8);
		MessageProperties properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);

		listener.handleRecommendationRequest(new Message(payload, properties));

		verify(recommendationService).recommendAndSaveResult("metadata-1", "user-1");
	}

	@Test
	void fallsBackToMetadataUserWhenUserIdMissing() {
		AudioMetadata metadata = new AudioMetadata();
		metadata.setUserId("user-1");
		when(firebaseService.getAudioMetadataById("metadata-1")).thenReturn(metadata);

		listener.handleRecommendationRequest(Map.of("metadataId", "metadata-1"));

		verify(firebaseService).getAudioMetadataById("metadata-1");
		verify(recommendationService).recommendAndSaveResult(eq("metadata-1"), eq("user-1"));
	}
}
