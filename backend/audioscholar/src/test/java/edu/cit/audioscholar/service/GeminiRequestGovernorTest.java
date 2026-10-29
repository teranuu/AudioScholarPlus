package edu.cit.audioscholar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import edu.cit.audioscholar.exception.GeminiBudgetExceededException;

class GeminiRequestGovernorTest {

	@Test
	void recordsAnAcquiredRequestAgainstItsJobContext() {
		GeminiBudgetService budget = mock(GeminiBudgetService.class);
		when(budget.reservePacingSlot("semantic-batch", "job-1")).thenReturn(Instant.now());
		when(budget.reserve("semantic-batch", 120, null, "job-1"))
				.thenReturn(new GeminiBudgetService.Reservation("semantic-batch", 120, Instant.now(), false));
		GeminiRequestGovernor governor = new GeminiRequestGovernor(budget, Duration.ofMinutes(5));

		governor.acquire("semantic-batch", 120, "job-1");

		verify(budget).reserve("semantic-batch", 120, null, "job-1");
		assertThat(governor.snapshot("job-1").requestAttempts()).isEqualTo(1);
		assertThat(governor.snapshot("job-1").retryRequests()).isZero();
		assertThat(governor.snapshot("job-1").lastAttemptOperation()).isEqualTo("semantic-batch");
		assertThat(governor.snapshotAndClear("job-1").requestAttempts()).isEqualTo(1);
		assertThat(governor.snapshot("job-1").requestAttempts()).isZero();
	}

	@Test
	void rejectsAQueueBacklogBeyondTheConfiguredWait() {
		GeminiBudgetService budget = mock(GeminiBudgetService.class);
		when(budget.reservePacingSlot("summary", "job-1")).thenReturn(Instant.now().plusSeconds(61));
		GeminiRequestGovernor governor = new GeminiRequestGovernor(budget, Duration.ofMinutes(1));

		assertThatThrownBy(() -> governor.acquire("summary", 100, "job-1"))
				.isInstanceOf(GeminiBudgetExceededException.class).hasMessageContaining("pacing backlog");
		verify(budget, never()).reserve("summary", 100, null, "job-1");
	}

	@Test
	void forwardsDailyAdmissionAndReleaseToTheSharedBudget() {
		GeminiBudgetService budget = mock(GeminiBudgetService.class);
		Instant reset = Instant.now().plusSeconds(3600);
		when(budget.requireDailyCapacity("multi-source.job-admission", 8, "job-1"))
				.thenReturn(new GeminiBudgetService.DailyCapacity(3, 8, 7, 18, reset));
		GeminiRequestGovernor governor = new GeminiRequestGovernor(budget, Duration.ofMinutes(5));

		GeminiBudgetService.DailyCapacity capacity = governor.requireDailyCapacity("multi-source.job-admission", 8,
				"job-1");
		governor.releaseDailyAdmission("job-1");

		assertThat(capacity.admittedRequests()).isEqualTo(8);
		assertThat(capacity.remainingRequests()).isEqualTo(7);
		verify(budget).releaseDailyAdmission("job-1");
	}
}
