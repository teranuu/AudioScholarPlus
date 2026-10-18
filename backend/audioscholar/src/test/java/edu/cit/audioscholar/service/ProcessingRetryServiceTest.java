package edu.cit.audioscholar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;

import edu.cit.audioscholar.config.RabbitMQConfig;
import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;

class ProcessingRetryServiceTest {
	@Test
	void acceptedRetryAtomicallyIncrementsAttemptBeforeQueueing() throws Exception {
		Harness harness = harness();

		var response = harness.service.retry("recording-1", "owner-1");

		ArgumentCaptor<Map<String, Object>> updates = ArgumentCaptor.forClass(Map.class);
		verify(harness.transaction).update(eq(harness.reference), updates.capture());
		assertThat(updates.getValue()).containsEntry("processingAttempt", 2).containsEntry("attemptId",
				"recording-1:2");
		verify(harness.validationEvents).emit(eq("metadata-1"), eq("SINGLE_SOURCE"), eq("WEB"),
				eq("PROCESSING_STARTED"), eq(java.util.List.of(6)), eq("recording-1:2"), any(), eq("audio_metadata"));
		verify(harness.publisher).publishToProcessingExchange(eq(RabbitMQConfig.TRANSCRIPTION_ROUTING_KEY), any());
		assertThat(response.metadataId()).isEqualTo("metadata-1");
	}

	@Test
	void queueFailureEmitsOneTerminalEventForReservedAttempt() throws Exception {
		Harness harness = harness();
		org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable")).when(harness.publisher)
				.publishToProcessingExchange(eq(RabbitMQConfig.TRANSCRIPTION_ROUTING_KEY), any());

		assertThatThrownBy(() -> harness.service.retry("recording-1", "owner-1"))
				.isInstanceOf(IllegalStateException.class).hasMessage("Could not queue processing retry");

		verify(harness.validationEvents, times(1)).emitAttemptTerminal("metadata-1", ProcessingStatus.FAILED);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private Harness harness() throws Exception {
		FirebaseService firebase = mock(FirebaseService.class);
		Firestore firestore = mock(Firestore.class);
		CollectionReference collection = mock(CollectionReference.class);
		DocumentReference reference = mock(DocumentReference.class);
		DocumentSnapshot snapshot = mock(DocumentSnapshot.class);
		Transaction transaction = mock(Transaction.class);
		ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
		ValidationEventService validationEvents = mock(ValidationEventService.class);

		AudioMetadata metadata = new AudioMetadata();
		metadata.setId("metadata-1");
		metadata.setRecordingId("recording-1");
		metadata.setUserId("owner-1");
		metadata.setStatus(ProcessingStatus.FAILED);
		metadata.setAudioUploadComplete(true);
		metadata.setNhostFileId("file-1");
		metadata.setTranscriptionComplete(false);
		metadata.setProcessingAttempt(1);
		metadata.setAttemptId("recording-1:1");
		metadata.setClientSource("WEB");

		when(firebase.getAudioMetadataByRecordingId("recording-1")).thenReturn(metadata);
		when(firestore.collection("audio_metadata")).thenReturn(collection);
		when(collection.document("metadata-1")).thenReturn(reference);
		when(snapshot.exists()).thenReturn(true);
		when(snapshot.getId()).thenReturn("metadata-1");
		when(snapshot.getData()).thenReturn(metadata.toMap());
		when(transaction.get(reference)).thenReturn(ApiFutures.immediateFuture(snapshot));
		when(transaction.update(eq(reference), any(Map.class))).thenReturn(transaction);
		when(firestore.runTransaction(any())).thenAnswer(invocation -> {
			Transaction.Function callback = invocation.getArgument(0);
			return ApiFutures.immediateFuture(callback.updateCallback(transaction));
		});

		ProcessingRetryService service = new ProcessingRetryService(firebase, firestore, publisher, "audio_metadata",
				validationEvents, null, null);
		return new Harness(service, reference, transaction, publisher, validationEvents);
	}

	private record Harness(ProcessingRetryService service, DocumentReference reference, Transaction transaction,
			ConfirmedRabbitPublisher publisher, ValidationEventService validationEvents) {
	}
}
