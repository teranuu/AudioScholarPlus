package edu.cit.audioscholar.service;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class MultiSourceOperationsMetrics {
	private final AtomicLong candidates = new AtomicLong();
	private final AtomicLong durationMs = new AtomicLong();
	private final Counter timeoutCounter;
	private final Counter terminalEventCounter;
	private final Counter skippedRunCounter;

	public MultiSourceOperationsMetrics(MeterRegistry registry) {
		this.timeoutCounter = Counter.builder("audioscholar.multi_source.processing_timeout_total").register(registry);
		this.terminalEventCounter = Counter.builder("audioscholar.multi_source.terminal_event_republished_total")
				.register(registry);
		this.skippedRunCounter = Counter.builder("audioscholar.multi_source.reconciliation_skipped_total")
				.register(registry);
		Gauge.builder("audioscholar.multi_source.reconciliation_candidates", candidates, AtomicLong::get)
				.register(registry);
		Gauge.builder("audioscholar.multi_source.reconciliation_duration_ms", durationMs, AtomicLong::get)
				.register(registry);
	}

	public void recordRun(long candidateCount, long timedOutCount, long terminalEventCount, long runDurationMs) {
		candidates.set(candidateCount);
		durationMs.set(runDurationMs);
		timeoutCounter.increment(timedOutCount);
		terminalEventCounter.increment(terminalEventCount);
	}

	public void recordSkippedRun() {
		skippedRunCounter.increment();
	}
}
