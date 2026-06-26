package edu.cit.audioscholar.exception;

public class GeminiContentBlockedException extends NonRetryableTaskException {
	private final String finishReason;

	public GeminiContentBlockedException(String finishReason) {
		super("Gemini blocked content generation. Finish reason: " + finishReason);
		this.finishReason = finishReason;
	}

	public String getFinishReason() {
		return finishReason;
	}
}
