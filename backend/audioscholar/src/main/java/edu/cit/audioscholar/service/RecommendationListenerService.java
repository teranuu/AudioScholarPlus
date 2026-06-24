package edu.cit.audioscholar.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.config.RabbitMQConfig;
import edu.cit.audioscholar.model.AudioMetadata;

@Service
public class RecommendationListenerService {
	private static final Logger log = LoggerFactory.getLogger(RecommendationListenerService.class);

	private final RecommendationService recommendationService;
	private final FirebaseService firebaseService;
	private final ObjectMapper objectMapper;

	public RecommendationListenerService(RecommendationService recommendationService, FirebaseService firebaseService,
			ObjectMapper objectMapper) {
		this.recommendationService = recommendationService;
		this.firebaseService = firebaseService;
		this.objectMapper = objectMapper;
	}

	@RabbitListener(queues = RabbitMQConfig.RECOMMENDATIONS_QUEUE_NAME)
	public void handleRecommendationRequest(Object payload) {
		Map<String, Object> message = normalizePayload(payload);
		String metadataId = stringValue(message.get("metadataId"));
		if (!org.springframework.util.StringUtils.hasText(metadataId)) {
			log.error("[Recommendations] Received message without metadataId: {}", payload);
			return;
		}

		String userId = stringValue(message.get("userId"));
		if (!org.springframework.util.StringUtils.hasText(userId)) {
			AudioMetadata metadata = firebaseService.getAudioMetadataById(metadataId);
			if (metadata != null) {
				userId = metadata.getUserId();
			}
		}
		if (!org.springframework.util.StringUtils.hasText(userId)) {
			log.error("[{}] Cannot generate recommendations without a userId.", metadataId);
			return;
		}

		log.info("[{}] Processing recommendation queue message.", metadataId);
		recommendationService.recommendAndSave(metadataId, userId);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> normalizePayload(Object payload) {
		if (payload instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		if (payload instanceof String text) {
			try {
				return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {
				});
			} catch (Exception e) {
				throw new IllegalArgumentException("Invalid recommendation message JSON", e);
			}
		}
		return Map.of();
	}

	private String stringValue(Object value) {
		return value == null ? null : value.toString();
	}
}
