package edu.cit.audioscholar.service;

import java.util.EnumSet;
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

import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;

@Service
public class ProcessingStageClaimService {
	private final Firestore firestore;
	private final String metadataCollection;

	public ProcessingStageClaimService(Firestore firestore,
			@Value("${firebase.firestore.collection.audiometadata}") String metadataCollection) {
		this.firestore = firestore;
		this.metadataCollection = metadataCollection;
	}

	public ClaimResult claim(String metadataId, ProcessingStatus targetStatus, String processingStage,
			EnumSet<ProcessingStatus> acceptableStatuses, EnumSet<ProcessingStatus> terminalStatuses) {
		DocumentReference reference = firestore.collection(metadataCollection).document(metadataId);
		try {
			return firestore.runTransaction(transaction -> {
				DocumentSnapshot snapshot = transaction.get(reference).get();
				if (!snapshot.exists()) {
					return ClaimResult.notFound();
				}
				AudioMetadata metadata = AudioMetadata.fromMap(snapshot.getData());
				if (metadata == null) {
					return ClaimResult.notFound();
				}
				if (!StringUtils.hasText(metadata.getId())) {
					metadata.setId(snapshot.getId());
				}
				ProcessingStatus status = metadata.getStatus();
				if (terminalStatuses.contains(status)) {
					return ClaimResult.skipped(metadata, "stage is already complete or terminal");
				}
				if (!acceptableStatuses.contains(status)) {
					return ClaimResult.skipped(metadata, "status " + status + " is not claimable for this stage");
				}

				Map<String, Object> updates = new HashMap<>();
				updates.put("status", targetStatus.name());
				updates.put("processingStage", processingStage);
				updates.put("failureReason", null);
				updates.put("lastUpdated", Timestamp.now());
				transaction.update(reference, updates);

				metadata.setStatus(targetStatus);
				return ClaimResult.acquired(metadata);
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while claiming processing stage", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("Could not claim processing stage", cause);
		}
	}

	public record ClaimResult(boolean claimed, boolean missing, AudioMetadata metadata, String reason) {
		static ClaimResult acquired(AudioMetadata metadata) {
			return new ClaimResult(true, false, metadata, null);
		}

		static ClaimResult skipped(AudioMetadata metadata, String reason) {
			return new ClaimResult(false, false, metadata, reason);
		}

		static ClaimResult notFound() {
			return new ClaimResult(false, true, null, "metadata document is missing");
		}
	}
}
