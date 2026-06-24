package edu.cit.audioscholar.util;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import edu.cit.audioscholar.exception.NonRetryableTaskException;

@Component
public class RobustTaskExecutor {

	private static final Logger log = LoggerFactory.getLogger(RobustTaskExecutor.class);

	/**
	 * Executes a task indefinitely until it succeeds. Catches ALL exceptions to
	 * prevent the process from terminating.
	 */
	public <T> T executeWithInfiniteRetry(String contextId, String taskDescription, Supplier<T> task) {
		long delayMs = 2000; // Start with 2 seconds
		long maxDelayMs = 60000; // Max wait 1 minute

		while (true) {
			try {
				return task.get();
			} catch (Exception e) {
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
				}

				// Exponential backoff with cap
				delayMs = Math.min(delayMs * 2, maxDelayMs);
			}
		}
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
}
