package edu.cit.audioscholar.service;

import java.time.Instant;
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
import edu.cit.audioscholar.dto.ProcessingRetryResponse;
import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;

@Service
public class ProcessingRetryService {
	private final FirebaseService firebaseService;
	private final Firestore firestore;
	private final RabbitTemplate rabbitTemplate;
	private final String metadataCollection;

	public ProcessingRetryService(FirebaseService firebaseService, Firestore firestore, RabbitTemplate rabbitTemplate,
			@Value("${firebase.firestore.collection.audiometadata}") String metadataCollection) {
		this.firebaseService = firebaseService;
		this.firestore = firestore;
		this.rabbitTemplate = rabbitTemplate;
		this.metadataCollection = metadataCollection;
	}

	public ProcessingRetryResponse retry(String recordingId, String userId) {
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

		RetryJob job = reserveRetry(metadata.getId(), userId);
		try {
			publish(job);
			return new ProcessingRetryResponse(job.metadataId(), job.recordingId(), job.status().name(),
					job.stage().name(), job.message(), null);
		} catch (Exception e) {
			Map<String, Object> rollback = new HashMap<>();
			rollback.put("status", ProcessingStatus.FAILED.name());
			rollback.put("processingStage", job.stage().name() + "_RETRY_QUEUE_FAILED");
			rollback.put("failureReason", job.stage().name() + "_RETRY_QUEUE_FAILED: " + e.getMessage());
			rollback.put("lastUpdated", Timestamp.now());
			firebaseService.updateDataWithMap(metadataCollection, job.metadataId(), rollback);
			throw new IllegalStateException("Could not queue processing retry", e);
		}
	}

	private RetryJob reserveRetry(String metadataId, String userId) {
		DocumentReference reference = firestore.collection(metadataCollection).document(metadataId);
		try {
			return firestore.runTransaction(transaction -> {
				DocumentSnapshot snapshot = transaction.get(reference).get();
				if (!snapshot.exists()) {
					throw new IllegalArgumentException("Recording metadata was not found");
				}
				AudioMetadata current = AudioMetadata.fromMap(snapshot.getData());
				if (current == null) {
					throw new IllegalArgumentException("Recording metadata was not found");
				}
				if (!StringUtils.hasText(current.getId())) {
					current.setId(snapshot.getId());
				}
				validateRetryable(current, userId);
				RetryPlan plan = planRetry(current);
				Map<String, Object> updates = new HashMap<>();
				updates.put("status", plan.status().name());
				updates.put("processingStage", plan.processingStage());
				updates.put("failureReason", null);
				updates.put("quotaRetryAt", null);
				updates.put("lastUpdated", Timestamp.now());
				transaction.update(reference, updates);
				String durableRecordingId = StringUtils.hasText(current.getRecordingId())
						? current.getRecordingId()
						: current.getId();
				return new RetryJob(current.getId(), durableRecordingId, current.getUserId(), current.getNhostFileId(),
						plan.stage(), plan.status(), plan.message());
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Retry request was interrupted", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("Could not reserve processing retry", cause);
		}
	}

	private void validateRetryable(AudioMetadata metadata, String userId) {
		if (!userId.equals(metadata.getUserId())) {
			throw new SecurityException("Recording belongs to another user");
		}
		ProcessingStatus status = metadata.getStatus();
		if (status == ProcessingStatus.COMPLETE || status == ProcessingStatus.COMPLETED_WITH_WARNINGS
				|| status == ProcessingStatus.SUMMARY_COMPLETE) {
			throw new IllegalStateException("Processing is already complete");
		}
		if (status == ProcessingStatus.PROCESSING_HALTED_NO_SPEECH
				|| status == ProcessingStatus.PROCESSING_HALTED_UNSUITABLE_CONTENT) {
			throw new IllegalStateException("This processing failure is not retryable");
		}
		if (status != ProcessingStatus.FAILED && status != ProcessingStatus.SUMMARY_FAILED) {
			throw new IllegalStateException("Processing is active or already queued");
		}
		if (!StringUtils.hasText(metadata.getNhostFileId()) || !metadata.isAudioUploadComplete()) {
			throw new IllegalStateException("Recording has no durable uploaded audio source");
		}
		String failureReason = metadata.getFailureReason();
		if (StringUtils.hasText(failureReason)) {
			String normalizedReason = failureReason.toLowerCase(java.util.Locale.ROOT);
			if (normalizedReason.contains("duration exceeds") || normalizedReason.contains("input exceeds")
					|| normalizedReason.contains("guardrail") || normalizedReason.contains("unsupported")
					|| normalizedReason.contains("maximum allowed file size")
					|| normalizedReason.contains("estimated gemini")) {
				throw new IllegalStateException("This processing failure is not retryable");
			}
		}
		Timestamp quotaRetryAt = metadata.getQuotaRetryAt();
		if (quotaRetryAt != null) {
			Instant retryAt = Instant.ofEpochSecond(quotaRetryAt.getSeconds(), quotaRetryAt.getNanos());
			if (retryAt.isAfter(Instant.now())) {
				throw new RetryNotReadyException("Gemini quota is still cooling down", retryAt);
			}
		}
	}

	private RetryPlan planRetry(AudioMetadata metadata) {
		if (!metadata.isTranscriptionComplete()) {
			return new RetryPlan(RetryStage.TRANSCRIPTION, ProcessingStatus.PROCESSING_QUEUED,
					"TRANSCRIPTION_RETRY_QUEUED", "Transcription retry queued");
		}

		boolean hasPptx = StringUtils.hasText(metadata.getOriginalPptxFileName())
				|| StringUtils.hasText(metadata.getNhostPptxFileId());
		if (hasPptx && !metadata.isPdfConversionComplete()) {
			if (!StringUtils.hasText(metadata.getNhostPptxFileId())) {
				throw new IllegalStateException("Recording has no durable uploaded PowerPoint source");
			}
			return new RetryPlan(RetryStage.PDF_CONVERSION, ProcessingStatus.PROCESSING_QUEUED,
					"PDF_CONVERSION_RETRY_QUEUED", "Document conversion retry queued");
		}

		if (!StringUtils.hasText(metadata.getSummaryId()) || metadata.getStatus() == ProcessingStatus.SUMMARY_FAILED) {
			if (!StringUtils.hasText(metadata.getTranscriptText())) {
				throw new IllegalStateException("Recording has no transcript available for summarization");
			}
			return new RetryPlan(RetryStage.SUMMARIZATION, ProcessingStatus.SUMMARIZATION_QUEUED,
					"SUMMARIZATION_RETRY_QUEUED", "Summarization retry queued");
		}

		return new RetryPlan(RetryStage.RECOMMENDATIONS, ProcessingStatus.RECOMMENDATIONS_QUEUED,
				"RECOMMENDATIONS_RETRY_QUEUED", "Recommendations retry queued");
	}

	private void publish(RetryJob job) throws Exception {
		switch (job.stage()) {
			case TRANSCRIPTION -> {
				AudioProcessingMessage message = new AudioProcessingMessage();
				message.setMetadataId(job.metadataId());
				message.setUserId(job.userId());
				message.setNhostFileId(job.nhostFileId());
				publishConfirmed(RabbitMQConfig.TRANSCRIPTION_ROUTING_KEY, message);
			}
			case PDF_CONVERSION -> {
				AudioProcessingMessage message = new AudioProcessingMessage();
				message.setMetadataId(job.metadataId());
				message.setUserId(job.userId());
				publishConfirmed(RabbitMQConfig.PPTX_CONVERSION_ROUTING_KEY, message);
			}
			case SUMMARIZATION -> publishConfirmed(RabbitMQConfig.SUMMARIZATION_ROUTING_KEY,
					Map.of("metadataId", job.metadataId(), "messageId", UUID.randomUUID().toString()));
			case RECOMMENDATIONS -> publishConfirmed(RabbitMQConfig.RECOMMENDATIONS_ROUTING_KEY,
					Map.of("metadataId", job.metadataId(), "recordingId", job.recordingId(), "userId", job.userId(),
							"messageId", UUID.randomUUID().toString()));
		}
	}

	private void publishConfirmed(String routingKey, Object message) throws Exception {
		CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
		rabbitTemplate.convertAndSend(RabbitMQConfig.PROCESSING_EXCHANGE_NAME, routingKey, message, correlation);
		CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
		if (!confirm.isAck()) {
			throw new IllegalStateException("RabbitMQ rejected retry message: " + confirm.getReason());
		}
	}

	public static class RetryNotReadyException extends IllegalStateException {
		private final Instant retryAfter;

		public RetryNotReadyException(String message, Instant retryAfter) {
			super(message);
			this.retryAfter = retryAfter;
		}

		public Instant getRetryAfter() {
			return retryAfter;
		}
	}

	private enum RetryStage {
		TRANSCRIPTION, PDF_CONVERSION, SUMMARIZATION, RECOMMENDATIONS
	}

	private record RetryPlan(RetryStage stage, ProcessingStatus status, String processingStage, String message) {
	}

	private record RetryJob(String metadataId, String recordingId, String userId, String nhostFileId, RetryStage stage,
			ProcessingStatus status, String message) {
	}
}
