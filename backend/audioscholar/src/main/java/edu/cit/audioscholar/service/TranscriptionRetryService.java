package edu.cit.audioscholar.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

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
	private final ConfirmedRabbitPublisher confirmedRabbitPublisher;
	private final String metadataCollection;
	private final ValidationEventService validationEvents;

	public TranscriptionRetryService(FirebaseService firebaseService, Firestore firestore,
			ConfirmedRabbitPublisher confirmedRabbitPublisher,
			@Value("${firebase.firestore.collection.audiometadata}") String metadataCollection) {
		this(firebaseService, firestore, confirmedRabbitPublisher, metadataCollection, null);
	}

	@org.springframework.beans.factory.annotation.Autowired
	public TranscriptionRetryService(FirebaseService firebaseService, Firestore firestore,
			ConfirmedRabbitPublisher confirmedRabbitPublisher,
			@Value("${firebase.firestore.collection.audiometadata}") String metadataCollection,
			ValidationEventService validationEvents) {
		this.firebaseService = firebaseService;
		this.firestore = firestore;
		this.confirmedRabbitPublisher = confirmedRabbitPublisher;
		this.metadataCollection = metadataCollection;
		this.validationEvents = validationEvents;
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
				int processingAttempt = current.getProcessingAttempt() + 1;
				String attemptId = current.getRecordingId() + ":" + processingAttempt;
				Map<String, Object> updates = new HashMap<>();
				updates.put("status", ProcessingStatus.PROCESSING_QUEUED.name());
				updates.put("processingStage", "PROCESSING_QUEUED");
				updates.put("failureReason", null);
				updates.put("quotaRetryAt", null);
				updates.put("lastUpdated", Timestamp.now());
				updates.put("processingAttempt", processingAttempt);
				updates.put("attemptId", attemptId);
				transaction.update(reference, updates);
				return new RetryJob(metadataId, current.getUserId(), current.getNhostFileId(), attemptId,
						current.getClientSource());
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

		emitStarted(job);
		try {
			AudioProcessingMessage message = new AudioProcessingMessage();
			message.setMetadataId(job.metadataId());
			message.setUserId(job.userId());
			message.setNhostFileId(job.nhostFileId());
			confirmedRabbitPublisher.publishToProcessingExchange(RabbitMQConfig.TRANSCRIPTION_ROUTING_KEY, message);
			return job.metadataId();
		} catch (RabbitPublishTimeoutException e) {
			return job.metadataId();
		} catch (Exception e) {
			Map<String, Object> rollback = new HashMap<>();
			rollback.put("status", ProcessingStatus.FAILED.name());
			rollback.put("processingStage", "TRANSCRIPTION_FAILED");
			rollback.put("failureReason", "TRANSCRIPTION_RETRY_QUEUE_FAILED: " + e.getMessage());
			rollback.put("lastUpdated", Timestamp.now());
			firebaseService.updateDataWithMap(metadataCollection, job.metadataId(), rollback);
			if (validationEvents != null)
				validationEvents.emitAttemptTerminal(job.metadataId(), ProcessingStatus.FAILED);
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

	private void emitStarted(RetryJob job) {
		if (validationEvents != null)
			validationEvents.emit(job.metadataId(), "SINGLE_SOURCE", job.clientSource(), "PROCESSING_STARTED",
					java.util.List.of(6), job.attemptId(),
					Map.of("recordingId", job.metadataId(), "attemptId", job.attemptId()), metadataCollection);
	}

	private record RetryJob(String metadataId, String userId, String nhostFileId, String attemptId,
			String clientSource) {
	}
}
