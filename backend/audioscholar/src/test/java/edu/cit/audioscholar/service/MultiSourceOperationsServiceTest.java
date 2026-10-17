package edu.cit.audioscholar.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MultiSourceOperationsServiceTest {
	@Test
	void historicalTerminalJobWithoutPendingMarkerIsNotReplayed() {
		MultiSourceJobRepository jobs = mock(MultiSourceJobRepository.class);
		FirebaseService firebase = mock(FirebaseService.class);
		ValidationEventService events = mock(ValidationEventService.class);
		Map<String, Object> historical = Map.of("jobId", "historical-job", "status", "FAILED", "clientSource", "WEB",
				"sourceCount", 0);
		when(jobs.findAll()).thenReturn(List.of(historical));
		when(firebase.getAllData(anyString())).thenReturn(List.of());
		MultiSourceOperationsService service = new MultiSourceOperationsService(jobs, firebase, events,
				new SimpleMeterRegistry(), Duration.ofMinutes(30));

		service.reconcileAndMeasure();

		verify(events, never()).emit(anyString(), anyString(), anyString(), anyString(), anyList(), anyString(),
				anyMap(), anyString());
	}

	@Test
	void staleJobIsFailedAndItsPendingTerminalEventIsPublished() {
		MultiSourceJobRepository jobs = mock(MultiSourceJobRepository.class);
		FirebaseService firebase = mock(FirebaseService.class);
		ValidationEventService events = mock(ValidationEventService.class);
		Map<String, Object> stale = Map.of("jobId", "job-1", "status", "PROCESSING", "clientSource", "WEB",
				"sourceCount", 0, "updatedAt", Date.from(Instant.now().minus(Duration.ofHours(1))));
		Map<String, Object> failed = Map.of("jobId", "job-1", "status", "FAILED", "clientSource", "WEB", "sourceCount",
				0, "terminalEventPending", false);
		when(jobs.findAll()).thenReturn(List.of(stale), List.of(failed));
		when(jobs.failIfNonterminalAndStale(eq("job-1"), any())).thenReturn(true);
		when(events.emit(eq("job-1"), eq("MULTI_SOURCE"), eq("WEB"), eq("JOB_FAILED"), anyList(), eq("initial"),
				anyMap(), eq("multiSourceJobs"))).thenReturn(true);
		when(firebase.getAllData(anyString())).thenAnswer(invocation -> {
			String collection = invocation.getArgument(0);
			if ("validationEvents".equals(collection))
				return List.of(Map.of("workflowId", "job-1", "eventType", "JOB_FAILED"));
			return List.of();
		});
		MultiSourceOperationsService service = new MultiSourceOperationsService(jobs, firebase, events,
				new SimpleMeterRegistry(), Duration.ofMinutes(30));

		service.reconcileAndMeasure();

		verify(jobs).failIfNonterminalAndStale(eq("job-1"), any());
		verify(jobs).markTerminalEventPublished("job-1");
	}
}
