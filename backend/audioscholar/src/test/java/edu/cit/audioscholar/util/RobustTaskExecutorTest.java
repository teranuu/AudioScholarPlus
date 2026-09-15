package edu.cit.audioscholar.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import edu.cit.audioscholar.exception.NonRetryableTaskException;

class RobustTaskExecutorTest {

	private final RobustTaskExecutor executor = new RobustTaskExecutor(3, 0, 0);

	@Test
	void boundedRetryReturnsAfterTransientFailure() {
		AtomicInteger attempts = new AtomicInteger();

		String result = executor.executeWithRetry("recording-1", "transcribe", 3, 0, () -> {
			if (attempts.incrementAndGet() < 3) {
				throw new RuntimeException("temporary failure");
			}
			return "done";
		});

		assertEquals("done", result);
		assertEquals(3, attempts.get());
	}

	@Test
	void boundedRetryPropagatesLastFailure() {
		AtomicInteger attempts = new AtomicInteger();

		RuntimeException failure = assertThrows(RuntimeException.class,
				() -> executor.executeWithRetry("recording-1", "transcribe", 2, 0, (Supplier<String>) () -> {
					attempts.incrementAndGet();
					throw new RuntimeException("permanent failure");
				}));

		assertEquals("permanent failure", failure.getMessage());
		assertEquals(2, attempts.get());
	}

	@Test
	void boundedRetryStopsImmediatelyForPermanentFailure() {
		AtomicInteger attempts = new AtomicInteger();

		assertThrows(NonRetryableTaskException.class,
				() -> executor.executeWithRetry("recording-1", "transcribe", 3, 0, (Supplier<String>) () -> {
					attempts.incrementAndGet();
					throw new NonRetryableTaskException("invalid request", null);
				}));

		assertEquals(1, attempts.get());
	}

	@Test
	void legacyRetryNamePropagatesLastFailure() {
		AtomicInteger attempts = new AtomicInteger();
		RobustTaskExecutor executor = new RobustTaskExecutor(3, 0, 0);

		RuntimeException failure = assertThrows(RuntimeException.class,
				() -> executor.executeWithInfiniteRetry("recording-1", "transcribe", (Supplier<String>) () -> {
					attempts.incrementAndGet();
					throw new RuntimeException("last failure");
				}));

		assertEquals("last failure", failure.getMessage());
		assertEquals(3, attempts.get());
	}

	@Test
	void legacyRetryNameDoesNotRetryAfterSuccessfulFinalAttempt() {
		AtomicInteger attempts = new AtomicInteger();
		RobustTaskExecutor executor = new RobustTaskExecutor(3, 0, 0);

		String result = executor.executeWithInfiniteRetry("recording-1", "transcribe", () -> {
			if (attempts.incrementAndGet() < 3) {
				throw new RuntimeException("temporary failure");
			}
			return "done";
		});

		assertEquals("done", result);
		assertEquals(3, attempts.get());
	}

	@Test
	void legacyRetryNameStopsImmediatelyForPermanentFailure() {
		AtomicInteger attempts = new AtomicInteger();
		RobustTaskExecutor executor = new RobustTaskExecutor(3, 0, 0);

		assertThrows(NonRetryableTaskException.class,
				() -> executor.executeWithInfiniteRetry("recording-1", "transcribe", (Supplier<String>) () -> {
					attempts.incrementAndGet();
					throw new NonRetryableTaskException("invalid request");
				}));

		assertEquals(1, attempts.get());
	}
}
