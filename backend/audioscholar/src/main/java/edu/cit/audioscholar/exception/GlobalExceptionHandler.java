package edu.cit.audioscholar.exception;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		Map<String, Object> body = new HashMap<>();
		body.put("timestamp", System.currentTimeMillis());
		body.put("status", status.value());

		List<String> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(x -> x.getField() + ": " + x.getDefaultMessage()).collect(Collectors.toList());

		body.put("errors", errors);
		body.put("message", "Validation failed");

		log.warn("Validation failed for request [{}]: {}", request.getDescription(false), errors);

		return new ResponseEntity<>(body, headers, status);
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<Object> handleAccessDeniedException(AccessDeniedException ex, WebRequest request) {
		Map<String, Object> body = new HashMap<>();
		body.put("timestamp", System.currentTimeMillis());
		body.put("status", HttpStatus.FORBIDDEN.value());
		body.put("error", "Forbidden");
		body.put("message", "You do not have permission to access this resource.");

		log.warn("Access denied for request [{}]: {}", request.getDescription(false), ex.getMessage());

		return new ResponseEntity<>(body, HttpStatus.FORBIDDEN);
	}

	@ExceptionHandler(InvalidAudioFileException.class)
	public ResponseEntity<Object> handleInvalidAudioFileException(InvalidAudioFileException ex, WebRequest request) {
		return badRequest(ex, request, "Invalid audio file detected");
	}

	@ExceptionHandler(ProcessingGuardrailException.class)
	public ResponseEntity<Object> handleProcessingGuardrailException(ProcessingGuardrailException ex,
			WebRequest request) {
		return badRequest(ex, request, "Processing guardrail rejected request");
	}

	@ExceptionHandler(GeminiBudgetExceededException.class)
	public ResponseEntity<Object> handleGeminiBudgetExceededException(GeminiBudgetExceededException ex,
			WebRequest request) {
		Map<String, Object> body = new HashMap<>();
		body.put("timestamp", System.currentTimeMillis());
		body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
		body.put("error", "Too Many Requests");
		body.put("message", ex.getMessage());
		if (ex.getRetryAt() != null) {
			body.put("retryAt", ex.getRetryAt().toString());
		}
		HttpHeaders headers = new HttpHeaders();
		long retryAfter = ex.getRetryAt() == null
				? 60
				: Math.max(1, Duration.between(Instant.now(), ex.getRetryAt()).toSeconds());
		headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
		log.warn("Gemini quota admission rejected for request [{}]: {} retryAt={}", request.getDescription(false),
				ex.getMessage(), ex.getRetryAt());
		return new ResponseEntity<>(body, headers, HttpStatus.TOO_MANY_REQUESTS);
	}

	private ResponseEntity<Object> badRequest(Exception ex, WebRequest request, String logMessage) {
		Map<String, Object> body = new HashMap<>();
		body.put("timestamp", System.currentTimeMillis());
		body.put("status", HttpStatus.BAD_REQUEST.value());
		body.put("error", "Bad Request");
		body.put("message", ex.getMessage());

		log.warn("{} for request [{}]: {}", logMessage, request.getDescription(false), ex.getMessage());

		return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
	}

	@Override
	protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Map<String, Object> body = new HashMap<>();
		body.put("timestamp", System.currentTimeMillis());
		body.put("status", HttpStatus.PAYLOAD_TOO_LARGE.value());
		body.put("error", "Payload Too Large");
		body.put("message", "Audio files must not exceed 100 MB.");

		log.warn("Upload exceeded the configured size limit for request [{}]", request.getDescription(false));
		return new ResponseEntity<>(body, headers, HttpStatus.PAYLOAD_TOO_LARGE);
	}

	@ExceptionHandler(FirestoreInteractionException.class)
	public ResponseEntity<Object> handleFirestoreInteractionException(FirestoreInteractionException ex,
			WebRequest request) {
		Map<String, Object> body = new HashMap<>();
		body.put("timestamp", System.currentTimeMillis());
		body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
		body.put("error", "Database Interaction Error");
		body.put("message", ex.getMessage());

		log.error("Firestore interaction error for request [{}]: {}", request.getDescription(false), ex.getMessage());

		return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Object> handleAllUncaughtException(Exception ex, WebRequest request) {
		if (this.handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR,
				request) != null) {
			log.debug("Exception handled by ResponseEntityExceptionHandler base class: {}", ex.getClass().getName());
		}

		Map<String, Object> body = new HashMap<>();
		body.put("timestamp", System.currentTimeMillis());
		body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
		body.put("error", "Internal Server Error");
		body.put("message", "An unexpected error occurred. Please try again later.");

		log.error("Unhandled exception for request [{}]:", request.getDescription(false), ex);

		return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
	}
}
