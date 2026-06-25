package edu.cit.audioscholar.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.TagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.google.cloud.Timestamp;

import edu.cit.audioscholar.config.RabbitMQConfig;
import edu.cit.audioscholar.dto.AudioProcessingMessage;
import edu.cit.audioscholar.exception.FirestoreInteractionException;
import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;
import edu.cit.audioscholar.util.RobustTaskExecutor;

@Service
public class AudioTranscriptionListenerService {
	private static final Logger log = LoggerFactory.getLogger(AudioTranscriptionListenerService.class);
	private static final String CACHE_METADATA_BY_USER = "audioMetadataByUser";

	private final FirebaseService firebaseService;
	private final NhostStorageService nhostStorageService;
	private final TranscriptionOrchestrator transcriptionOrchestrator;
	private final QualityReportService qualityReportService;
	private final CacheManager cacheManager;
	private final Path tempFileDir;
	private final RabbitTemplate rabbitTemplate;
	private final RobustTaskExecutor robustTaskExecutor;
	private final AudioProcessingGuardrailService guardrailService;
	private final Map<String, ReentrantLock> metadataLocks = new ConcurrentHashMap<>();

	@Value("${gemini.transcription.max-attempts:3}")
	private int transcriptionMaxAttempts;

	@Value("${gemini.transcription.retry-delay-ms:2000}")
	private long transcriptionRetryDelayMs;

	public AudioTranscriptionListenerService(FirebaseService firebaseService, NhostStorageService nhostStorageService,
			TranscriptionOrchestrator transcriptionOrchestrator, QualityReportService qualityReportService,
			CacheManager cacheManager, @Value("${app.temp-file-dir}") String tempFileDirStr,
			RabbitTemplate rabbitTemplate, RobustTaskExecutor robustTaskExecutor,
			AudioProcessingGuardrailService guardrailService) {
		this.firebaseService = firebaseService;
		this.nhostStorageService = nhostStorageService;
		this.transcriptionOrchestrator = transcriptionOrchestrator;
		this.qualityReportService = qualityReportService;
		this.cacheManager = cacheManager;
		this.tempFileDir = Paths.get(tempFileDirStr);
		this.rabbitTemplate = rabbitTemplate;
		this.robustTaskExecutor = robustTaskExecutor;
		this.guardrailService = guardrailService;
		try {
			Files.createDirectories(this.tempFileDir);
		} catch (IOException e) {
			log.error("Could not create temporary directory for listener: {}", this.tempFileDir.toAbsolutePath(), e);
		}
	}

	@RabbitListener(queues = RabbitMQConfig.TRANSCRIPTION_QUEUE_NAME, containerFactory = "transcriptionContainerFactory")
	public void handleAudioTranscriptionRequest(AudioProcessingMessage message) {
		String metadataId = message.getMetadataId();
		String userId = message.getUserId();
		log.info("[{}] Received transcription request for metadata ID from queue. Initial User ID: {}", metadataId,
				userId);

		if (metadataId == null || metadataId.isEmpty()) {
			log.error("Invalid transcription message: metadataId is null or empty. Aborting.");
			return;
		}

		Lock metadataLock = metadataLocks.computeIfAbsent(metadataId, id -> new ReentrantLock());
		if (!metadataLock.tryLock()) {
			log.info("[{}] Another thread is already processing this metadata ID. Skipping duplicate processing.",
					metadataId);
			return;
		}

		AtomicReference<Path> downloadedAudioPath = new AtomicReference<>();
		AtomicReference<Path> chunkWorkDirectory = new AtomicReference<>();
		try {
			robustTaskExecutor.executeWithRetry(metadataId, "transcribing audio", transcriptionMaxAttempts,
					transcriptionRetryDelayMs, () -> {
						try {
							log.debug("[{}] Fetching AudioMetadata document...", metadataId);
							Map<String, Object> metadataMap = firebaseService
									.getData(firebaseService.getAudioMetadataCollectionName(), metadataId);

							AudioMetadata metadata = AudioMetadata.fromMap(metadataMap);
							log.info("[{}] Found metadata. Current status: {}, User: {}", metadataId,
									metadata.getStatus(), userId);

							ProcessingStatus currentStatus = metadata.getStatus();

							if (metadata.isTranscriptionComplete()) {
								log.info("[{}] Skipping transcription as it is already marked as complete.",
										metadataId);

								checkCompletionAndTriggerSummarization(metadataId, userId);
								return;
							}

							// Parallel Processing: Allow execution if status indicates parallel activity or
							// retries
							if (currentStatus != ProcessingStatus.UPLOAD_IN_PROGRESS
									&& currentStatus != ProcessingStatus.PROCESSING_QUEUED
									&& currentStatus != ProcessingStatus.PDF_CONVERTING
									&& currentStatus != ProcessingStatus.PDF_CONVERTING_API
									&& currentStatus != ProcessingStatus.PDF_CONVERSION_COMPLETE
									&& currentStatus != ProcessingStatus.TRANSCRIBING) {
								log.info(
										"[{}] Skipping transcription as metadata is already in status: {}. Transcription has likely been processed already.",
										metadataId, currentStatus);
								return;
							}

							updateMetadataStatus(metadataId, userId, ProcessingStatus.TRANSCRIBING, null);

							String originalFileName = metadata.getFileName() != null
									? metadata.getFileName()
									: "audio.aac";
							String nhostFileId = StringUtils.hasText(message.getNhostFileId())
									? message.getNhostFileId()
									: metadata.getNhostFileId();
							Path tempFilePath = downloadAudioToFile(nhostFileId, originalFileName, metadataId, userId);
							if (tempFilePath == null) {
								throw new RuntimeException(
										"Failed to download audio file (downloadAudioToFile returned null)");
							}
							downloadedAudioPath.set(tempFilePath);
							AudioProcessingGuardrailService.GuardrailResult guardrail = guardrailService
									.validateAudioFile(tempFilePath, originalFileName);
							Map<String, Object> guardrailUpdates = new HashMap<>();
							guardrailUpdates.put("durationSeconds", Math.toIntExact(guardrail.durationSeconds()));
							guardrailUpdates.put("estimatedGeminiAudioTokens", guardrail.estimatedAudioTokens());
							guardrailUpdates.put("audioFingerprint", guardrail.fingerprint());
							guardrailUpdates.put("lastUpdated", Timestamp.now());
							firebaseService.updateDataWithMap(firebaseService.getAudioMetadataCollectionName(),
									metadataId, guardrailUpdates);

							Integer durationSeconds = metadata.getDurationSeconds();
							if (durationSeconds == null || durationSeconds <= 0) {
								durationSeconds = calculateAudioDuration(tempFilePath, metadataId);
								if (durationSeconds != null && durationSeconds > 0) {
									Map<String, Object> updates = new HashMap<>();
									updates.put("durationSeconds", durationSeconds);
									updates.put("lastUpdated", Timestamp.now());
									firebaseService.updateDataWithMap(firebaseService.getAudioMetadataCollectionName(),
											metadataId, updates);
									log.info("[{}] Successfully updated durationSeconds ({}) in metadata.", metadataId,
											durationSeconds);
								}
							}

							try {
								var qualityReport = qualityReportService.analyzeAndSave(metadataId, tempFilePath);
								Map<String, Object> qualityUpdates = new HashMap<>();
								qualityUpdates.put("qualityReport", qualityReport.toMap());
								qualityUpdates.put("lastUpdated", Timestamp.now());
								firebaseService.updateDataWithMap(firebaseService.getAudioMetadataCollectionName(),
										metadataId, qualityUpdates);
								log.info("[{}] Quality report generated with status {} and {} issue(s).", metadataId,
										qualityReport.getStatus(), qualityReport.getIssues().size());
							} catch (Exception e) {
								log.warn("[{}] Quality report generation failed without blocking transcription: {}",
										metadataId, e.getMessage());
							}

							metadataMap = firebaseService.getData(firebaseService.getAudioMetadataCollectionName(),
									metadataId);
							metadata = AudioMetadata.fromMap(metadataMap);
							if (metadata.isTranscriptionComplete()) {
								log.info(
										"[{}] Transcription was completed by another process while we were preparing. Skipping API call.",
										metadataId);
								checkCompletionAndTriggerSummarization(metadataId, userId);
								return;
							}

							Path workDirectory = tempFileDir.resolve(metadataId + "_transcription_chunks");
							chunkWorkDirectory.set(workDirectory);
							log.info("[{}] Preparing and transcribing audio chunks for {}.", metadataId,
									originalFileName);
							String transcript = transcriptionOrchestrator.transcribe(metadataId, tempFilePath,
									workDirectory);
							if (isGeminiErrorResponse(transcript)) {
								throw new RuntimeException("Gemini returned an error payload instead of a transcript");
							}

							metadataMap = firebaseService.getData(firebaseService.getAudioMetadataCollectionName(),
									metadataId);
							metadata = AudioMetadata.fromMap(metadataMap);
							if (metadata.isTranscriptionComplete()) {
								log.info(
										"[{}] Transcription was completed by another process while we were transcribing. Skipping update.",
										metadataId);
								return;
							}

							log.info(
									"[{}] Transcription completed successfully. Saving transcript and updating status.",
									metadataId);
							Map<String, Object> updates = new HashMap<>();
							updates.put("transcriptText", transcript);
							updates.put("transcriptionComplete", true);
							updates.put("status", ProcessingStatus.TRANSCRIPTION_COMPLETE.name());
							updates.put("processingStage", "TRANSCRIPTION_COMPLETE");
							updates.put("lastUpdated", Timestamp.now());

							log.info("[{}] Saving transcript with size: {} characters", metadataId,
									transcript != null ? transcript.length() : 0);

							firebaseService.updateDataWithMap(firebaseService.getAudioMetadataCollectionName(),
									metadataId, updates);
							log.info(
									"[{}] Successfully saved transcript, set transcriptionComplete=true, and status=TRANSCRIPTION_COMPLETE.",
									metadataId);
							invalidateCache(userId);

							try {
								log.debug(
										"[{}] Adding a short delay to ensure Firestore consistency before summarization...",
										metadataId);
								Thread.sleep(3000);
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								log.warn("[{}] Delay before summarization was interrupted", metadataId);
							}

							checkCompletionAndTriggerSummarization(metadataId, userId);

						} catch (Exception e) {
							// Wrap any checked exceptions or rethrow RuntimeExceptions to trigger retry
							if (e instanceof RuntimeException) {
								throw (RuntimeException) e;
							} else {
								throw new RuntimeException("Error during transcription process: " + e.getMessage(), e);
							}
						}
					});
		} catch (RuntimeException e) {
			log.error("[{}] Transcription failed after {} attempt(s): {}", metadataId, transcriptionMaxAttempts,
					e.getMessage(), e);
			updateMetadataStatusToFailed(metadataId, userId, failureReason(e));
		} finally {
			Path tempFilePath = downloadedAudioPath.get();
			if (tempFilePath != null) {
				try {
					Files.deleteIfExists(tempFilePath);
					log.debug("[{}] Deleted temporary audio file: {}", metadataId, tempFilePath);
				} catch (IOException e) {
					log.warn("[{}] Failed to delete temporary audio file: {}", metadataId, e.getMessage());
				}
			}
			deleteDirectory(chunkWorkDirectory.get(), metadataId);
			metadataLock.unlock();
			if (metadataLock instanceof ReentrantLock) {
				ReentrantLock reentrantLock = (ReentrantLock) metadataLock;
				if (!reentrantLock.isLocked() && !reentrantLock.hasQueuedThreads()) {
					metadataLocks.remove(metadataId);
				}
			}
		}
	}

	private String failureReason(Throwable failure) {
		Throwable root = failure;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
		String normalized = message.toLowerCase(java.util.Locale.ROOT);
		String code;
		if (normalized.contains("media_runtime_unavailable") || normalized.contains("createprocess error=2")) {
			code = "MEDIA_RUNTIME_UNAVAILABLE";
		} else if (normalized.contains("ffmpeg") || normalized.contains("media_preparation_failed")) {
			code = "MEDIA_PREPARATION_FAILED";
		} else if (normalized.contains("quota did not recover")) {
			code = "GEMINI_QUOTA_TIMEOUT";
		} else if (normalized.contains("429") || normalized.contains("too many requests")
				|| normalized.contains("all api keys") || normalized.contains("currently in cooldown")) {
			code = "GEMINI_RATE_LIMITED";
		} else if (normalized.contains("timed out") || normalized.contains("deadline")) {
			code = "TRANSCRIPTION_TIMEOUT";
		} else if (normalized.contains("gemini") || normalized.contains("403") || normalized.contains("400")) {
			code = "GEMINI_REJECTED";
		} else {
			code = "TRANSCRIPTION_FAILED";
		}
		return code + ": " + message;
	}

	private void deleteDirectory(Path directory, String metadataId) {
		if (directory == null || !Files.exists(directory)) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					log.warn("[{}] Could not delete chunk file {}: {}", metadataId, path, e.getMessage());
				}
			});
		} catch (IOException e) {
			log.warn("[{}] Could not clean chunk directory {}: {}", metadataId, directory, e.getMessage());
		}
	}

	private boolean isGeminiErrorResponse(String transcript) {
		if (transcript == null || transcript.isBlank()) {
			return true;
		}
		String normalized = transcript.stripLeading();
		return normalized.startsWith("{\"error\"") || normalized.startsWith("{\n  \"error\"");
	}

	private Path downloadAudioToFile(String nhostId, String fileName, String metadataId, String userId) {
		if (!StringUtils.hasText(nhostId)) {
			log.error("[{}] Transcription message and metadata have no Nhost file ID.", metadataId);
			updateMetadataStatusToFailed(metadataId, userId, "SOURCE_DOWNLOAD_FAILED: Missing Nhost file ID.");
			return null;
		}
		if (fileName == null || fileName.trim().isEmpty()) {
			fileName = "audio.mp3";
			log.info("[{}] No filename found in recording. Using default name: {}", metadataId, fileName);
		}

		Path tempFilePath = tempFileDir.resolve(metadataId + "_" + fileName);

		try {
			log.info("[{}] Downloading audio file with Nhost ID: {} to {}", metadataId, nhostId,
					tempFilePath.getFileName());
			nhostStorageService.downloadFileToPath(nhostId, tempFilePath);
			log.info("[{}] Audio downloaded successfully.", metadataId);
			return tempFilePath;
		} catch (IOException e) {
			log.error("[{}] Failed to download audio file from Nhost (ID: {}). Error: {}", metadataId, nhostId,
					e.getMessage(), e);
			updateMetadataStatusToFailed(metadataId, userId, "SOURCE_DOWNLOAD_FAILED: " + e.getMessage());
			return null;
		} catch (Exception e) {
			log.error("[{}] Unexpected error downloading audio file from Nhost (ID: {}). Error: {}", metadataId,
					nhostId, e.getMessage(), e);
			updateMetadataStatusToFailed(metadataId, userId, "SOURCE_DOWNLOAD_FAILED: " + e.getMessage());
			return null;
		}
	}

	private Integer calculateAudioDuration(Path audioFilePath, String metadataId) {
		Integer durationSec = null;
		try {
			File audioFile = audioFilePath.toFile();
			String fileName = audioFilePath.getFileName().toString().toLowerCase();

			if (fileName.endsWith(".aac") || fileName.endsWith(".m4a")) {
				log.info(
						"[{}] AAC file detected: {}. JAudioTagger cannot reliably read AAC duration. Setting a default duration.",
						metadataId, audioFilePath.getFileName());
				return 600;
			}

			if (audioFile.exists() && audioFile.length() > 0) {
				AudioFile f = AudioFileIO.read(audioFile);
				AudioHeader header = f.getAudioHeader();
				if (header != null) {
					durationSec = header.getTrackLength();
					log.info("[{}] Calculated audio duration using JAudioTagger: {} seconds.", metadataId, durationSec);
					return durationSec;
				} else {
					log.warn("[{}] JAudioTagger could not read audio header for {}. Duration not calculated.",
							metadataId, audioFilePath.getFileName());
				}
			} else {
				log.warn("[{}] Temporary audio file {} does not exist or is empty. Cannot calculate duration.",
						metadataId, audioFilePath.toAbsolutePath());
			}
		} catch (CannotReadException | IOException | TagException | ReadOnlyFileException
				| InvalidAudioFrameException e) {
			log.warn(
					"[{}] JAudioTagger failed to read audio file {} for duration calculation. Error: {}. Proceeding without duration.",
					metadataId, audioFilePath.getFileName(), e.getMessage());
			log.debug("JAudioTagger exception details:", e);
		} catch (Exception e) {
			log.error("[{}] Unexpected error calculating duration using JAudioTagger for {}. Error: {}", metadataId,
					audioFilePath.getFileName(), e.getMessage(), e);
		}
		return null;
	}

	private void checkCompletionAndTriggerSummarization(String metadataId, @Nullable String userId) {
		Lock lock = null;
		try {
			lock = metadataLocks.computeIfAbsent(metadataId, k -> new ReentrantLock());
			lock.lock();
			log.debug("[{}] Acquired lock for trigger summarization check", metadataId);

			log.info("[{}] Checking if both transcription and PDF conversion are complete to trigger summarization...",
					metadataId);

			Map<String, Object> latestMetadataMap = firebaseService
					.getData(firebaseService.getAudioMetadataCollectionName(), metadataId);

			if (latestMetadataMap == null) {
				log.error(
						"[{}] Failed to re-fetch metadata after transcription update. Cannot check for summarization trigger.",
						metadataId);
				return;
			}

			AudioMetadata latestMetadata = AudioMetadata.fromMap(latestMetadataMap);

			ProcessingStatus currentStatus = latestMetadata.getStatus();
			if (currentStatus == ProcessingStatus.SUMMARIZATION_QUEUED || currentStatus == ProcessingStatus.SUMMARIZING
					|| currentStatus == ProcessingStatus.SUMMARY_COMPLETE
					|| currentStatus == ProcessingStatus.RECOMMENDATIONS_QUEUED
					|| currentStatus == ProcessingStatus.GENERATING_RECOMMENDATIONS
					|| currentStatus == ProcessingStatus.COMPLETE || currentStatus == ProcessingStatus.FAILED) {

				log.info(
						"[{}] Summarization already triggered or completed (current status: {}). Skipping duplicate trigger.",
						metadataId, currentStatus);
				return;
			}

			boolean statusChangeInProgress = false;
			try {
				Map<String, Object> latestStatusCheck = firebaseService
						.getData(firebaseService.getAudioMetadataCollectionName(), metadataId);

				if (latestStatusCheck != null) {
					AudioMetadata checkMetadata = AudioMetadata.fromMap(latestStatusCheck);
					ProcessingStatus checkStatus = checkMetadata.getStatus();

					if (checkStatus != currentStatus) {
						log.info(
								"[{}] Status changed from {} to {} while processing. Likely another service triggered summarization. Skipping.",
								metadataId, currentStatus, checkStatus);
						statusChangeInProgress = true;
					}
				}
			} catch (Exception e) {
				log.warn("[{}] Error during additional status check: {}. Proceeding with normal flow.", metadataId,
						e.getMessage());
			}

			if (statusChangeInProgress) {
				return;
			}

			boolean transcriptionDone = latestMetadata.isTranscriptionComplete();
			boolean pdfDone = latestMetadata.isPdfConversionComplete();
			boolean isAudioOnly = latestMetadata.isAudioOnly();
			boolean hasPptx = StringUtils.hasText(latestMetadata.getNhostPptxFileId());

			log.debug(
					"[{}] Completion status check: TranscriptionDone={}, PdfConversionDone={}, AudioOnly={}, HasPptx={}",
					metadataId, transcriptionDone, pdfDone, isAudioOnly, hasPptx);

			boolean readyForSummarization = transcriptionDone && (pdfDone || isAudioOnly || !hasPptx);

			if (readyForSummarization) {
				log.info(
						"[{}] Conditions met for summarization: AudioOnly={}, TranscriptionDone={}, PdfDone={}, HasPptx={}. Sending message to summarization queue.",
						metadataId, isAudioOnly, transcriptionDone, pdfDone, hasPptx);

				boolean statusUpdated = updateMetadataStatus(metadataId, userId, ProcessingStatus.SUMMARIZATION_QUEUED,
						null);

				if (!statusUpdated) {
					log.warn(
							"[{}] Failed to update status to SUMMARIZATION_QUEUED. Another process may have already claimed this task.",
							metadataId);
					return;
				}

				String messageId = UUID.randomUUID().toString();
				Map<String, String> messagePayload = new HashMap<>();
				messagePayload.put("metadataId", metadataId);
				messagePayload.put("messageId", messageId);

				rabbitTemplate.convertAndSend(RabbitMQConfig.PROCESSING_EXCHANGE_NAME,
						RabbitMQConfig.SUMMARIZATION_ROUTING_KEY, messagePayload);

				log.info("[{}] Message (ID: {}) sent successfully to queue '{}' with routing key '{}'.", metadataId,
						messageId, RabbitMQConfig.SUMMARIZATION_QUEUE_NAME, RabbitMQConfig.SUMMARIZATION_ROUTING_KEY);

			} else {
				log.info(
						"[{}] Conditions not yet met for final summarization (Transcription: {}, PDF: {}, Audio-only: {}, Has PPTX: {}). Waiting for other process.",
						metadataId, transcriptionDone, pdfDone, isAudioOnly, hasPptx);
			}

		} catch (FirestoreInteractionException e) {
			log.error("[{}] Failed to re-fetch metadata or update status for summarization check. Error: {}",
					metadataId, e.getMessage(), e);
		} finally {
			if (lock != null) {
				lock.unlock();
				log.debug("[{}] Released lock for trigger summarization check", metadataId);
			}
		}
	}

	private void updateMetadataStatusToFailed(String metadataId, @Nullable String userId, String reason) {
		updateMetadataStatus(metadataId, userId, ProcessingStatus.FAILED, reason);
		log.error("[{}] Processing failed. Reason: {}", metadataId, reason);
	}

	private boolean updateMetadataStatus(String metadataId, @Nullable String userId, ProcessingStatus status,
			@Nullable String reason) {
		try {
			Map<String, Object> updates = new HashMap<>();
			updates.put("status", status.name());
			if (status == ProcessingStatus.FAILED) {
				updates.put("processingStage", "TRANSCRIPTION_FAILED");
			}
			updates.put("lastUpdated", Timestamp.now());
			if (reason != null) {
				updates.put("failureReason", reason);
			}

			firebaseService.updateDataWithMap(firebaseService.getAudioMetadataCollectionName(), metadataId, updates);
			log.info("[{}] Metadata status updated to {}.", metadataId, status);
			if (status == ProcessingStatus.FAILED || status == ProcessingStatus.TRANSCRIPTION_COMPLETE
					|| status == ProcessingStatus.SUMMARIZATION_QUEUED) {
				invalidateCache(userId);
			}
			return true;
		} catch (FirestoreInteractionException e) {
			log.error("[{}] CRITICAL: Failed to update metadata status to {}. Error: {}", metadataId, status,
					e.getMessage(), e);
			return false;
		}
	}

	private void invalidateCache(@Nullable String userId) {
		if (userId != null) {
			Cache cache = cacheManager.getCache(CACHE_METADATA_BY_USER);
			if (cache != null) {
				cache.evict(userId);
				log.debug("Invalidated cache '{}' for userId: {}", CACHE_METADATA_BY_USER, userId);
			} else {
				log.warn("Cache '{}' not found. Could not invalidate for userId: {}", CACHE_METADATA_BY_USER, userId);
			}
		} else {
			log.warn("Cannot invalidate cache because userId is null.");
		}
	}
}
