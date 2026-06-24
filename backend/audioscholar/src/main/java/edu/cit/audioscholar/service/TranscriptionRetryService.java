package edu.cit.audioscholar.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;

import edu.cit.audioscholar.config.RabbitMQConfig;
import edu.cit.audioscholar.dto.AudioProcessingMessage;
import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;

@Service
public class TranscriptionRetryService {
	private final FirebaseService firebaseService;
	private final Firestore firestore;
	private final RabbitTemplate rabbitTemplate;
	private final String metadataCollection;

	public TranscriptionRetryService(FirebaseService firebaseService, Firestore firestore,
			RabbitTemplate rabbitTemplate,
			@Value("${firebase.firestore.collection.audiometadata}") String metadataCollection) {
		this.firebaseService = firebaseService;
		this.firestore = firestore;
		this.rabbitTemplate = rabbitTemplate;
		this.metadataCollection = metadataCollection;
	}

	public String retry(String recordingId, String userId) {
		AudioMetadata metadata = firebaseService.getAudioMetadataByRecordingId(recordingId);
		if (metadata == null) {
			metadata = firebaseService.getAudioMetadataById(recordingId);
		}
		if (metadata == null) {
			throw new IllegalArgumentException("Recording was not found");
		}
		if (!userId.equals(metadata.getUserId())) {
			throw new SecurityException("Recording belongs to another user");
		}

		String metadataId = metadata.getId();
		DocumentReference reference = firestore.collection(metadataCollection).document(metadataId);
		RetryJob job;
		try {
			job = firestore.runTransaction(transaction -> {
				DocumentSnapshot snapshot = transaction.get(reference).get();
				AudioMetadata current = AudioMetadata.fromMap(snapshot.getData());
				if (current == null || !snapshot.exists()) {
					throw new IllegalArgumentException("Recording metadata was not found");
				}
				validateRetryable(current, userId);
				Map<String, Object> updates = new HashMap<>();
				updates.put("status", ProcessingStatus.PROCESSING_QUEUED.name());
				updates.put("processingStage", "PROCESSING_QUEUED");
				updates.put("failureReason", null);
				updates.put("quotaRetryAt", null);
				updates.put("lastUpdated", Timestamp.now());
				transaction.update(reference, updates);
				return new RetryJob(metadataId, current.getUserId(), current.getNhostFileId());
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Retry request was interrupted", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("Could not reopen transcription", cause);
		}

		try {
			AudioProcessingMessage message = new AudioProcessingMessage();
			message.setMetadataId(job.metadataId());
			message.setUserId(job.userId());
			message.setNhostFileId(job.nhostFileId());
			CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
			rabbitTemplate.convertAndSend(RabbitMQConfig.PROCESSING_EXCHANGE_NAME,
					RabbitMQConfig.TRANSCRIPTION_ROUTING_KEY, message, correlation);
			CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
			if (!confirm.isAck()) {
				throw new IllegalStateException("RabbitMQ rejected transcription retry: " + confirm.getReason());
			}
			return job.metadataId();
		} catch (Exception e) {
			Map<String, Object> rollback = new HashMap<>();
			rollback.put("status", ProcessingStatus.FAILED.name());
			rollback.put("processingStage", "TRANSCRIPTION_FAILED");
			rollback.put("failureReason", "TRANSCRIPTION_RETRY_QUEUE_FAILED: " + e.getMessage());
			rollback.put("lastUpdated", Timestamp.now());
			firebaseService.updateDataWithMap(metadataCollection, job.metadataId(), rollback);
			throw new IllegalStateException("Could not queue transcription retry", e);
		}
	}

	private void validateRetryable(AudioMetadata metadata, String userId) {
		if (!userId.equals(metadata.getUserId())) {
			throw new SecurityException("Recording belongs to another user");
		}
		if (metadata.getStatus() != ProcessingStatus.FAILED || metadata.isTranscriptionComplete()) {
			throw new IllegalStateException("Transcription is active or already complete");
		}
		if (!StringUtils.hasText(metadata.getNhostFileId())) {
			throw new IllegalStateException("Recording has no durable audio source");
		}
		String reason = metadata.getFailureReason();
		String normalized = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT);
		boolean retryable = normalized.startsWith("gemini_rate_limited")
				|| normalized.startsWith("gemini_quota_timeout") || normalized.startsWith("transcription_timeout")
				|| normalized.contains("all api keys for gemini are currently in cooldown");
		if (!retryable) {
			throw new IllegalStateException("This transcription failure is not retryable");
		}
	}

	private record RetryJob(String metadataId, String userId, String nhostFileId) {
	}
}
