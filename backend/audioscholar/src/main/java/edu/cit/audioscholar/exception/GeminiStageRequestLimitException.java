package edu.cit.audioscholar.exception;

/**
 * Signals that one bounded multi-source stage exhausted its per-job attempt
 * allowance. This is a terminal job error, not daily quota exhaustion, so it
 * must not be deferred until the next quota day.
 */
public class GeminiStageRequestLimitException extends NonRetryableTaskException {
	private final String operation;
	private final long maxRequests;

	public GeminiStageRequestLimitException(String operation, long maxRequests) {
		super("Gemini multi-source stage request limit exceeded for " + operation + " (max=" + maxRequests + ")");
		this.operation = operation;
		this.maxRequests = maxRequests;
	}

	public String getOperation() {
		return operation;
	}

	public long getMaxRequests() {
		return maxRequests;
	}
}
