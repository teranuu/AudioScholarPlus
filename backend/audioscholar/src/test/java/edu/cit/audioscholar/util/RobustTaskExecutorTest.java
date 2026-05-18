package edu.cit.audioscholar.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class RobustTaskExecutorTest {

	@Test
	void executeWithInfiniteRetryStopsAfterConfiguredAttempts() {
		RobustTaskExecutor executor = new RobustTaskExecutor(3, 0, 0);
		AtomicInteger attempts = new AtomicInteger();

		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> executor.executeWithInfiniteRetry("ctx", "test task", (Supplier<String>) () -> {
					attempts.incrementAndGet();
					throw new RuntimeException("boom");
				}));

		assertEquals(3, attempts.get());
		assertTrue(exception.getMessage().contains("after 3 attempts"));
	}

	@Test
	void executeWithInfiniteRetryReturnsWhenRetryEventuallySucceeds() {
		RobustTaskExecutor executor = new RobustTaskExecutor(3, 0, 0);
		AtomicInteger attempts = new AtomicInteger();

		String result = executor.executeWithInfiniteRetry("ctx", "test task", () -> {
			int attempt = attempts.incrementAndGet();
			if (attempt < 3) {
				throw new RuntimeException("not yet");
			}
			return "success";
		});

		assertEquals("success", result);
		assertEquals(3, attempts.get());
	}
}
