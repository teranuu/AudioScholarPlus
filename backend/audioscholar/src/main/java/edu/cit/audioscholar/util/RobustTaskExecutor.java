package edu.cit.audioscholar.util;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import edu.cit.audioscholar.exception.NonRetryableTaskException;

@Component
public class RobustTaskExecutor {

	private static final Logger log = LoggerFactory.getLogger(RobustTaskExecutor.class);

	private final int maxAttempts;

	private final long initialDelayMs;

	private final long maxDelayMs;

	public RobustTaskExecutor(@Value("${app.robust-task.max-attempts:10}") int maxAttempts,
			@Value("${app.robust-task.initial-delay-ms:2000}") long initialDelayMs,
			@Value("${app.robust-task.max-delay-ms:60000}") long maxDelayMs) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("app.robust-task.max-attempts must be at least 1");
		}
		if (initialDelayMs < 0) {
			throw new IllegalArgumentException("app.robust-task.initial-delay-ms must not be negative");
		}
		if (maxDelayMs < initialDelayMs) {
			throw new IllegalArgumentException(
					"app.robust-task.max-delay-ms must be greater than or equal to app.robust-task.initial-delay-ms");
		}

		this.maxAttempts = maxAttempts;
		this.initialDelayMs = initialDelayMs;
		this.maxDelayMs = maxDelayMs;
	}

	/**
	 * Executes a task with bounded retry attempts and exponential backoff. The
	 * legacy method name is retained to avoid changing listener call sites; retry
	 * is now bounded by app.robust-task.max-attempts.
	 */
	public <T> T executeWithInfiniteRetry(String contextId, String taskDescription, Supplier<T> task) {
		long delayMs = initialDelayMs;

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return task.get();
			} catch (Exception e) {
<<<<<<< HEAD
				if (containsNonRetryableFailure(e)) {
					log.error("[{}] Cannot retry {}. Error: {}", contextId, taskDescription, e.getMessage());
					throw e;
				}
				log.error("[{}] Failed to {}. Retrying in {}ms. Error: {}", contextId, taskDescription, delayMs,
						e.getMessage());

				// Optional: Add specific logic here if you want to break on IRRECOVERABLE
				// errors
				// (e.g., file completely deleted from DB), otherwise keep looping.

				try {
					Thread.sleep(delayMs);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new RuntimeException("Thread interrupted during robust retry", ie);
=======
				if (attempt == maxAttempts) {
					log.error("[{}] Failed to {} after {}/{} attempts. Giving up. Error: {}", contextId,
							taskDescription, attempt, maxAttempts, e.getMessage(), e);
					throw new IllegalStateException(
							"Failed to " + taskDescription + " after " + maxAttempts + " attempts", e);
>>>>>>> a991831ed45f0ec696832d73e09c89256a68cadd
				}

				log.warn("[{}] Failed to {} on attempt {}/{}. Retrying in {}ms. Error: {}", contextId, taskDescription,
						attempt, maxAttempts, delayMs, e.getMessage());
				sleepBeforeRetry(delayMs);
				delayMs = nextDelay(delayMs);
			}
		}

		throw new IllegalStateException("Retry loop exited unexpectedly for " + taskDescription);
	}

	public <T> T executeWithRetry(String contextId, String taskDescription, int maxAttempts, long initialDelayMs,
			Supplier<T> task) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be at least 1");
		}

		long delayMs = Math.max(0, initialDelayMs);
		RuntimeException lastFailure = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return task.get();
			} catch (RuntimeException e) {
				lastFailure = e;
				if (containsNonRetryableFailure(e)) {
					log.error("[{}] Cannot retry {}. Error: {}", contextId, taskDescription, e.getMessage());
					throw e;
				}
				if (attempt == maxAttempts) {
					break;
				}
				log.warn("[{}] Failed to {} (attempt {}/{}). Retrying in {}ms. Error: {}", contextId, taskDescription,
						attempt, maxAttempts, delayMs, e.getMessage());
				try {
					Thread.sleep(delayMs);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new RuntimeException("Thread interrupted during retry", ie);
				}
				delayMs = Math.min(Math.max(1, delayMs) * 2, 60000);
			}
		}

		throw lastFailure != null ? lastFailure : new IllegalStateException("Task failed without an exception");
	}

	private boolean containsNonRetryableFailure(Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof NonRetryableTaskException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	public void executeWithRetry(String contextId, String taskDescription, int maxAttempts, long initialDelayMs,
			Runnable task) {
		executeWithRetry(contextId, taskDescription, maxAttempts, initialDelayMs, () -> {
			task.run();
			return null;
		});
	}

	// Overload for void tasks
	public void executeWithInfiniteRetry(String contextId, String taskDescription, Runnable task) {
		executeWithInfiniteRetry(contextId, taskDescription, () -> {
			task.run();
			return null;
		});
	}

	private void sleepBeforeRetry(long delayMs) {
		try {
			Thread.sleep(delayMs);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Thread interrupted during robust retry", ie);
		}
	}

	private long nextDelay(long currentDelayMs) {
		if (currentDelayMs >= maxDelayMs) {
			return maxDelayMs;
		}

		long doubledDelay = currentDelayMs > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : currentDelayMs * 2;
		return Math.min(doubledDelay, maxDelayMs);
	}
}
