package edu.cit.audioscholar.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MultiSourceOperationsServiceTest {
	private MultiSourceJobRepository jobs;
	private ValidationEventService events;
	private MultiSourceOperationsMetrics metrics;
	private GeminiRequestGovernor requestGovernor;
	private MultiSourceOperationsService service;

	@BeforeEach
	void setUp() {
		jobs = mock(MultiSourceJobRepository.class);
		events = mock(ValidationEventService.class);
		metrics = mock(MultiSourceOperationsMetrics.class);
		requestGovernor = mock(GeminiRequestGovernor.class);
		service = new MultiSourceOperationsService(jobs, events, metrics, requestGovernor, Duration.ofMinutes(30),
				Duration.ofMinutes(14), 25, "test-worker");
		when(jobs.tryAcquireReconciliationLease(eq("test-worker"), any())).thenReturn(true);
		when(jobs.findStaleNonterminal(anyList(), any(), eq(25))).thenReturn(List.of());
		when(jobs.findPendingTerminalEvents(25)).thenReturn(List.of());
	}

	@Test
	void historicalTerminalJobWithoutPendingMarkerIsNotLoadedOrReplayed() {
		service.reconcile();

		verify(jobs).findStaleNonterminal(anyList(), any(), eq(25));
		verify(jobs).findPendingTerminalEvents(25);
		verify(events, never()).emit(anyString(), anyString(), anyString(), anyString(), anyList(), anyString(),
				anyMap(), anyString());
	}

	@Test
	void staleJobIsFailedAndItsTerminalEventIsPublished() {
		Map<String, Object> stale = Map.of("jobId", "job-1", "status", "PROCESSING", "clientSource", "WEB", "updatedAt",
				Date.from(Instant.now().minus(Duration.ofHours(1))));
		when(jobs.findStaleNonterminal(anyList(), any(), eq(25))).thenReturn(List.of(stale));
		when(jobs.failIfNonterminalAndStale(eq("job-1"), any())).thenReturn(true);
		when(events.emit(eq("job-1"), eq("MULTI_SOURCE"), eq("WEB"), eq("JOB_FAILED"), anyList(), eq("initial"),
				anyMap(), eq("multiSourceJobs"))).thenReturn(true);

		service.reconcile();

		verify(jobs).failIfNonterminalAndStale(eq("job-1"), any());
		verify(requestGovernor).releaseDailyAdmission("job-1");
		verify(jobs).markTerminalEventPublished("job-1");
		verify(metrics).recordRun(eq(1L), eq(1L), eq(0L), anyLong());
	}

	@Test
	void pendingTerminalEventIsRepublishedWithoutScanningHistoricalCollections() {
		Map<String, Object> pending = Map.of("jobId", "job-2", "status", "COMPLETE", "clientSource", "WEB",
				"terminalEventPending", true);
		when(jobs.findPendingTerminalEvents(25)).thenReturn(List.of(pending));
		when(events.emit(eq("job-2"), eq("MULTI_SOURCE"), eq("WEB"), eq("JOB_COMPLETED"), anyList(), eq("initial"),
				anyMap(), eq("multiSourceJobs"))).thenReturn(true);

		service.reconcile();

		verify(jobs).markTerminalEventPublished("job-2");
		verify(metrics).recordRun(eq(1L), eq(0L), eq(1L), anyLong());
	}

	@Test
	void leaseOwnedByAnotherInstanceSkipsAllFirestoreQueries() {
		when(jobs.tryAcquireReconciliationLease(eq("test-worker"), any())).thenReturn(false);

		service.reconcile();

		verify(jobs, never()).findStaleNonterminal(anyList(), any(), anyInt());
		verify(jobs, never()).findPendingTerminalEvents(anyInt());
		verify(metrics).recordSkippedRun();
	}

	@Test
	void configuredQueryLimitIsHardCappedAtFifty() {
		MultiSourceOperationsService capped = new MultiSourceOperationsService(jobs, events, metrics, requestGovernor,
				Duration.ofMinutes(30), Duration.ofMinutes(14), 500, "test-worker");

		capped.reconcile();

		verify(jobs).findStaleNonterminal(anyList(), any(), eq(50));
		verify(jobs).findPendingTerminalEvents(50);
	}
}
