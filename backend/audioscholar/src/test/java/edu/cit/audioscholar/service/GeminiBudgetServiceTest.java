package edu.cit.audioscholar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.Transaction;

import edu.cit.audioscholar.exception.GeminiBudgetExceededException;
import edu.cit.audioscholar.exception.GeminiStageRequestLimitException;

class GeminiBudgetServiceTest {
	private final Map<String, DocumentReference> references = new HashMap<>();
	private final Map<DocumentReference, Map<String, Object>> documents = new IdentityHashMap<>();
	private Firestore firestore;

	@BeforeEach
	void setUp() {
		firestore = mock(Firestore.class);
		CollectionReference collection = mock(CollectionReference.class);
		Transaction transaction = mock(Transaction.class);
		when(firestore.collection("budgets")).thenReturn(collection);
		when(collection.document(any(String.class))).thenAnswer(invocation -> references
				.computeIfAbsent(invocation.getArgument(0), ignored -> mock(DocumentReference.class)));
		when(transaction.get(any(DocumentReference.class))).thenAnswer(invocation -> {
			DocumentSnapshot snapshot = mock(DocumentSnapshot.class);
			Map<String, Object> data = documents.get(invocation.getArgument(0));
			when(snapshot.getData()).thenReturn(data == null ? null : new HashMap<>(data));
			return ApiFutures.immediateFuture(snapshot);
		});
		when(transaction.set(any(DocumentReference.class), any())).thenAnswer(invocation -> {
			put(invocation.getArgument(0), invocation.getArgument(1), false);
			return transaction;
		});
		when(transaction.set(any(DocumentReference.class), any(), any(SetOptions.class))).thenAnswer(invocation -> {
			put(invocation.getArgument(0), invocation.getArgument(1), true);
			return transaction;
		});
		when(firestore.runTransaction(any())).thenAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			Transaction.Function<Object> function = invocation.getArgument(0);
			return ApiFutures.immediateFuture(function.updateCallback(transaction));
		});
	}

	@Test
	void topsUpExhaustedAdmissionAndRecordsEveryActualAttempt() {
		GeminiBudgetService service = service(18);
		service.requireDailyCapacity("multi-source.job-admission", 5, "job-1");

		GeminiBudgetService.Reservation sixth = null;
		for (int attempt = 1; attempt <= 6; attempt++)
			sixth = service.reserve("transcription.generateContent", 100, null, "job-1");

		Map<String, Object> admission = document("project-admission-");
		assertThat(sixth.retryRequest()).isFalse();
		assertThat(sixth.topUpRequest()).isTrue();
		assertThat(admission).containsEntry("initialReservedRequests", 5L).containsEntry("reservedRequests", 6L)
				.containsEntry("consumedRequests", 6L).containsEntry("actualRequests", 6L)
				.containsEntry("retryRequests", 0L).containsEntry("topUpRequests", 1L)
				.containsEntry("remainingRequests", 0L)
				.containsEntry("lastAttemptOperation", "transcription.generateContent");
		assertThat(document("project-day-")).containsEntry("requests", 6L).containsEntry("admittedRequests", 0L);
	}

	@Test
	void recordsRepeatedBoundedStageAsRetryAndFailsTerminallyAtStageLimit() {
		GeminiBudgetService service = service(18);
		service.requireDailyCapacity("multi-source.job-admission", 5, "job-1");

		GeminiBudgetService.Reservation first = service.reserve("multi-source.key-point-batch.generateContent", 100,
				null, "job-1");
		GeminiBudgetService.Reservation second = service.reserve("multi-source.key-point-batch.generateContent", 100,
				null, "job-1");

		assertThat(first.retryRequest()).isFalse();
		assertThat(second.retryRequest()).isTrue();
		assertThat(second.topUpRequest()).isFalse();
		assertThatThrownBy(() -> service.reserve("multi-source.key-point-batch.generateContent", 100, null, "job-1"))
				.isInstanceOf(GeminiStageRequestLimitException.class)
				.hasMessageContaining("multi-source.key-point-batch.generateContent");
		assertThat(document("project-admission-")).containsEntry("actualRequests", 2L)
				.containsEntry("retryRequests", 1L).containsEntry("topUpRequests", 0L);
	}

	@Test
	void refusesTopUpWhenProjectDayHasNoCapacity() {
		GeminiBudgetService service = service(5);
		service.requireDailyCapacity("multi-source.job-admission", 5, "job-1");
		for (int attempt = 1; attempt <= 5; attempt++)
			service.reserve("transcription.generateContent", 100, null, "job-1");

		assertThatThrownBy(() -> service.reserve("transcription.generateContent", 100, null, "job-1"))
				.isInstanceOf(GeminiBudgetExceededException.class).hasMessageContaining("project RPD");
		assertThat(document("project-admission-")).containsEntry("actualRequests", 5L).containsEntry("reservedRequests",
				5L);
	}

	private GeminiBudgetService service(long projectRpd) {
		return new GeminiBudgetService(firestore, true, 100, 1_000_000, projectRpd, 0, 0, 2, "budgets",
				"America/Los_Angeles");
	}

	private Map<String, Object> document(String prefix) {
		return references.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
				.map(entry -> documents.get(entry.getValue())).findFirst().orElseThrow();
	}

	private void put(DocumentReference reference, Object value, boolean merge) {
		@SuppressWarnings("unchecked")
		Map<String, Object> update = new HashMap<>((Map<String, Object>) value);
		if (merge)
			documents.computeIfAbsent(reference, ignored -> new HashMap<>()).putAll(update);
		else
			documents.put(reference, update);
	}
}
