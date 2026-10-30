package edu.cit.audioscholar.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.config.RabbitMQConfig;
import edu.cit.audioscholar.dto.MultiSourceJobMessage;
import edu.cit.audioscholar.exception.GeminiBudgetExceededException;
import edu.cit.audioscholar.exception.GeminiRateLimitException;
import edu.cit.audioscholar.exception.GeminiStageRequestLimitException;
import edu.cit.audioscholar.exception.MultiSourceProcessingException;
import edu.cit.audioscholar.exception.ProcessingGuardrailException;
import edu.cit.audioscholar.exception.StorageRetrievalException;
import edu.cit.audioscholar.model.Flashcard;
import edu.cit.audioscholar.model.KeyPoint;
import edu.cit.audioscholar.model.MergedSummary;
import edu.cit.audioscholar.model.MultiSourceJob;
import edu.cit.audioscholar.model.OutputType;
import edu.cit.audioscholar.model.ProcessingStatus;
import edu.cit.audioscholar.model.QualityReport;
import edu.cit.audioscholar.model.SourceAttribution;
import edu.cit.audioscholar.model.SourceFile;
import edu.cit.audioscholar.model.SourceKind;
import edu.cit.audioscholar.model.Summary;

@Service
public class MultiSourceJobService {
	private static final Logger log = LoggerFactory.getLogger(MultiSourceJobService.class);
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final Set<String> ALLOWED_MEDIA_TYPES = Set.of("audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav",
			"audio/aac", "audio/x-aac", "audio/ogg", "application/ogg", "audio/flac", "audio/x-flac", "audio/aiff",
			"audio/x-aiff", "audio/mp4", "audio/m4a", "video/mp4", "video/webm", "video/quicktime");
	private static final Set<String> ALLOWED_MEDIA_EXTENSIONS = Set.of("mp3", "wav", "aac", "ogg", "flac", "aiff",
			"m4a", "mp4", "webm", "mov");
	private static final Set<String> ALLOWED_DOCUMENT_TYPES = Set.of("application/pdf", "application/msword",
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-powerpoint",
			"application/vnd.openxmlformats-officedocument.presentationml.presentation");
	private static final Set<String> ALLOWED_DOCUMENT_EXTENSIONS = Set.of("pdf", "ppt", "pptx", "doc", "docx");

	private final GeminiService geminiService;
	private final QualityReportService qualityReportService;
	private final SummaryService summaryService;
	private final DeduplicationService deduplicationService;
	private final SourceAttributionService sourceAttributionService;
	private final SourceFileService sourceFileService;
	private final SourceTranscriptService sourceTranscriptService;
	private final DocumentTextExtractionService documentTextExtractionService;
	private final MergedSummaryRepository mergedSummaryRepository;
	private final MultiSourceJobRepository multiSourceJobRepository;
	private final AudioProcessingGuardrailService guardrailService;
	private final Path tempDir;
	private final String maxFileSizeValue;
	private final ConfirmedRabbitPublisher publisher;
	private final ValidationEventService events;
	private final TimedTranscriptSegmentService timedSegments;
	private final SemanticEvidenceService semanticEvidence;
	private final GeminiRequestGovernor requestGovernor;
	private final Duration processingLeaseDuration;
	private final Duration contentionRetryDelay;
	private final String workerIdentity;

	public MultiSourceJobService(GeminiService geminiService, QualityReportService qualityReportService,
			SummaryService summaryService, DeduplicationService deduplicationService,
			SourceAttributionService sourceAttributionService, SourceFileService sourceFileService,
			SourceTranscriptService sourceTranscriptService,
			DocumentTextExtractionService documentTextExtractionService,
			MergedSummaryRepository mergedSummaryRepository, MultiSourceJobRepository multiSourceJobRepository,
			AudioProcessingGuardrailService guardrailService, @Value("${app.temp-file-dir}") String tempDirStr,
			@Value("${spring.servlet.multipart.max-file-size}") String maxFileSizeValue,
			ConfirmedRabbitPublisher publisher, ValidationEventService events,
			TimedTranscriptSegmentService timedSegments, SemanticEvidenceService semanticEvidence,
			GeminiRequestGovernor requestGovernor,
			@Value("${multi-source.single-job.lease-duration:35m}") Duration processingLeaseDuration,
			@Value("${multi-source.single-job.retry-delay:15s}") Duration contentionRetryDelay,
			@Value("${multi-source.worker-identity:multi-source-worker}") String workerIdentity) throws IOException {
		this.geminiService = geminiService;
		this.qualityReportService = qualityReportService;
		this.summaryService = summaryService;
		this.deduplicationService = deduplicationService;
		this.sourceAttributionService = sourceAttributionService;
		this.sourceFileService = sourceFileService;
		this.sourceTranscriptService = sourceTranscriptService;
		this.documentTextExtractionService = documentTextExtractionService;
		this.mergedSummaryRepository = mergedSummaryRepository;
		this.multiSourceJobRepository = multiSourceJobRepository;
		this.guardrailService = guardrailService;
		this.tempDir = Path.of(tempDirStr);
		this.maxFileSizeValue = maxFileSizeValue;
		this.publisher = publisher;
		this.events = events;
		this.timedSegments = timedSegments;
		this.semanticEvidence = semanticEvidence;
		this.requestGovernor = requestGovernor;
		this.processingLeaseDuration = processingLeaseDuration;
		this.contentionRetryDelay = contentionRetryDelay;
		this.workerIdentity = workerIdentity;
		Files.createDirectories(this.tempDir);
	}

	public MultiSourceJob createAndProcess(String userId, List<MultipartFile> mediaFiles,
			List<MultipartFile> documentFiles, String title, String description, String outputTypeValue)
			throws Exception {
		return createAndProcess(userId, mediaFiles, documentFiles, title, description, outputTypeValue, null);
	}

	public MultiSourceJob createAndProcess(String userId, List<MultipartFile> mediaFiles,
			List<MultipartFile> documentFiles, String title, String description, String outputTypeValue,
			String clientHeader) throws Exception {
		return createAndProcess(userId, mediaFiles, documentFiles, title, description, outputTypeValue, clientHeader,
				null);
	}

	public MultiSourceJob createAndProcess(String userId, List<MultipartFile> mediaFiles,
			List<MultipartFile> documentFiles, String title, String description, String outputTypeValue,
			String clientHeader, String idempotencyKey) throws Exception {
		OutputType outputType = OutputType.fromValue(outputTypeValue);
		List<MultipartFile> normalizedMediaFiles = normalizeFiles(mediaFiles);
		List<MultipartFile> normalizedDocumentFiles = normalizeFiles(documentFiles);
		validateFiles(normalizedMediaFiles, normalizedDocumentFiles);
		guardrailService.validateFileCount(normalizedMediaFiles, normalizedDocumentFiles);
		guardrailService.validateUploadBytes(normalizedMediaFiles, normalizedDocumentFiles);

		MultiSourceJob job = new MultiSourceJob();
		if (StringUtils.hasText(idempotencyKey)) {
			if (idempotencyKey.length() > 128)
				throw new IllegalArgumentException("Idempotency key must not exceed 128 characters.");
			String deterministicJobId = UUID
					.nameUUIDFromBytes(
							("multi-source:" + userId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8))
					.toString();
			Map<String, Object> existing = multiSourceJobRepository.findById(deterministicJobId);
			if (existing != null) {
				if (!userId.equals(existing.get("userId")))
					throw new IllegalArgumentException("Idempotency key does not belong to this user.");
				return hydrateJob(existing);
			}
			job.setJobId(deterministicJobId);
		}
		job.setUserId(userId);
		job.setTitle(StringUtils.hasText(title) ? title : "Merged Lecture Summary");
		job.setDescription(description);
		job.setOutputType(outputType.name());
		job.setStatus(ProcessingStatus.ACCEPTED.name());
		job.setSemanticEnrichmentStatus("PENDING");
		job.setSourceCount(normalizedMediaFiles.size() + normalizedDocumentFiles.size());
		int estimatedGeminiRequests = normalizedMediaFiles.size() + 3;
		job.setEstimatedGeminiRequests(estimatedGeminiRequests);
		job.setClientSource(ValidationEventService.clientSource(clientHeader));
		job.setAcceptedAt(new Date());
		List<Path> tempFiles = new ArrayList<>();
		List<SourceFile> uploadedSources = new ArrayList<>();
		try {
			save(job);
			event(job, "BATCH_SUBMITTED", List.of(9), "initial",
					Map.of("sourceCount", job.getSourceCount(), "estimatedGeminiRequests", estimatedGeminiRequests));
			event(job, "CONFIG_CONFIRMED", List.of(2), "initial",
					Map.of("jobId", job.getJobId(), "outputType", outputType.name()));
			List<AudioProcessingGuardrailService.GuardrailResult> mediaGuardrails = new ArrayList<>();
			List<MultipartFile> allFiles = new ArrayList<>();
			allFiles.addAll(normalizedMediaFiles);
			allFiles.addAll(normalizedDocumentFiles);
			for (int i = 0; i < allFiles.size(); i++) {
				MultipartFile file = allFiles.get(i);
				String sourceLabel = "Source " + (char) ('A' + i);
				Path tempFile = saveTemp(file);
				tempFiles.add(tempFile);
				SourceKind sourceKind = i < normalizedMediaFiles.size() ? SourceKind.MEDIA : SourceKind.DOCUMENT;

				AudioProcessingGuardrailService.GuardrailResult guardrail = null;
				if (SourceKind.MEDIA == sourceKind) {
					guardrail = guardrailService.validateAudioFile(tempFile, file.getOriginalFilename());
				}
				SourceFile sourceFile = sourceFileService.createSourceFile(job.getJobId(), sourceLabel, sourceKind,
						file, tempFile);
				uploadedSources.add(sourceFile);
				if (guardrail != null) {
					sourceFile.setDurationSeconds(guardrail.durationSeconds());
					sourceFile.setEstimatedGeminiAudioTokens(guardrail.estimatedAudioTokens());
					sourceFile.setAudioFingerprint(guardrail.fingerprint());
					mediaGuardrails.add(guardrail);
				}
				sourceFileService.save(sourceFile);
				job.getSourceFiles().add(sourceFile);
				save(job);
				event(job, "SOURCE_UPLOADED", List.of(9), sourceFile.getSourceFileId(), Map.of("jobId", job.getJobId(),
						"sourceFileId", sourceFile.getSourceFileId(), "sourceKind", sourceKind.name()));
			}
			guardrailService.validateMultiSourceAggregate(mediaGuardrails);
			event(job, "BATCH_VALIDATED", List.of(9), "initial", Map.of("sourceCount", job.getSourceCount()));
			event(job, "JOB_ACCEPTED", List.of(2, 9), "initial", Map.of("jobId", job.getJobId()));
			event(job, "ALL_SOURCES_UPLOADED", List.of(9), "initial", Map.of("sourceCount", job.getSourceCount()));
			job.setStatus(ProcessingStatus.PROCESSING_QUEUED.name());
			job.setQueuedAt(new Date());
			save(job);
			publisher.publishToProcessingExchange(RabbitMQConfig.MULTI_SOURCE_ROUTING_KEY,
					new MultiSourceJobMessage(job.getJobId()));
			event(job, "JOB_QUEUED", List.of(9), "initial", Map.of("jobId", job.getJobId()));
			return job;
		} catch (Exception e) {
			for (SourceFile uploadedSource : uploadedSources) {
				try {
					sourceFileService.delete(uploadedSource);
				} catch (RuntimeException cleanupFailure) {
					log.error(
							"Could not clean up source after rejected multi-source submission jobId={} sourceFileId={}",
							job.getJobId(), uploadedSource.getSourceFileId(), cleanupFailure);
				}
			}
			job.setStatus(ProcessingStatus.FAILED.name());
			job.setFailureReason(sanitizeFailure(e.getMessage()));
			job.setFailureStage("SUBMISSION");
			job.setFailureCode(failureCode(e));
			job.setFailureDetail(sanitizeFailure(e.getMessage()));
			job.setFailedAt(new Date());
			job.setFinalAttemptCount(job.getProcessingAttempts());
			job.setUpdatedAt(new Date());
			try {
				terminalEvent(job, "JOB_FAILED", List.of(9), "initial", Map.of("jobId", job.getJobId(), "outcome",
						"FAILED", "failureStage", "SUBMISSION", "failureCode", job.getFailureCode()));
			} finally {
				releaseDailyAdmission(job.getJobId());
			}
			throw e;
		} finally {
			for (Path tempFile : tempFiles) {
				try {
					Files.deleteIfExists(tempFile);
				} catch (IOException ignored) {
				}
			}
		}
	}

	public void processQueuedJob(String jobId) throws Exception {
		if (deferUntilQuotaWindow(jobId))
			return;
		String leaseOwner = workerIdentity + ":" + jobId + ":" + UUID.randomUUID();
		if (!multiSourceJobRepository.tryAcquireProcessingLease(leaseOwner,
				Date.from(Instant.now().plus(processingLeaseDuration)))) {
			log.info(
					"Deferring multi-source job because another job owns the global processing lease jobId={} configuredDelay={} fixedBucketDelayMs={}",
					jobId, contentionRetryDelay, RabbitMQConfig.MULTI_SOURCE_RETRY_TTL_MS);
			publisher.publishToProcessingExchange(RabbitMQConfig.MULTI_SOURCE_RETRY_ROUTING_KEY,
					new MultiSourceJobMessage(jobId));
			return;
		}
		try {
			processQueuedJobUnderLease(jobId);
		} finally {
			multiSourceJobRepository.releaseProcessingLease(leaseOwner);
		}
	}

	private boolean deferUntilQuotaWindow(String jobId) {
		Map<String, Object> row = multiSourceJobRepository.findById(jobId);
		if (row == null || !ProcessingStatus.PROCESSING_QUEUED.name().equals(row.get("status")))
			return false;
		Date retryAt = date(row.get("quotaRetryAt"));
		if (retryAt == null || !retryAt.toInstant().isAfter(Instant.now()))
			return false;
		publisher.publishToProcessingExchange(RabbitMQConfig.MULTI_SOURCE_QUOTA_RETRY_ROUTING_KEY,
				new MultiSourceJobMessage(jobId));
		log.info("Keeping quota-deferred multi-source job in fixed retry bucket jobId={} retryAt={}", jobId,
				retryAt.toInstant());
		return true;
	}

	private void processQueuedJobUnderLease(String jobId) throws Exception {
		if (!multiSourceJobRepository.claim(jobId))
			return;
		MultiSourceJob job = hydrateJob(multiSourceJobRepository.findById(jobId));
		job.setStatus("PROCESSING");
		job.setQuotaRetryAt(null);
		job.setFailureReason(null);
		job.setFailureStage(null);
		job.setFailureCode(null);
		job.setFailureDetail(null);
		job.setProcessingStartedAt(new Date());
		job.setProcessingAttempts(job.getProcessingAttempts() + 1);
		if (!saveWorkerProgress(job))
			return;
		event(job, "PROCESSING_STARTED", List.of(2, 9), "initial", Map.of("jobId", jobId));
		List<Path> localFiles = new ArrayList<>();
		String stage = "SOURCE_RETRIEVAL";
		try {
			List<SourceFile> sources = new ArrayList<>();
			List<Map<String, Object>> sourceRows = sourceFileService.findByJobId(jobId);
			validatePersistedSources(job, sourceRows);
			for (Map<String, Object> row : sourceRows) {
				stage = "SOURCE_RETRIEVAL";
				SourceFile source = hydrateSource(row);
				if (source.getTranscriptionCompletedAt() != null) {
					String checkpoint = sourceTranscriptService.findTranscriptText(jobId, source.getSourceFileId());
					if (StringUtils.hasText(checkpoint))
						source.setTranscriptText(checkpoint);
				}
				if (source.getTranscriptionCompletedAt() != null && StringUtils.hasText(source.getTranscriptText())) {
					sources.add(source);
					log.info("Reusing completed source checkpoint jobId={} sourceFileId={}", jobId,
							source.getSourceFileId());
					continue;
				}
				Path local = downloadSource(source);
				localFiles.add(local);
				source.setRetrievedAt(new Date());
				sourceFileService.save(source);
				job.setUpdatedAt(new Date());
				if (!saveWorkerProgress(job))
					return;
				event(job, "SOURCE_RETRIEVED", List.of(9), source.getSourceFileId(),
						Map.of("jobId", jobId, "sourceFileId", source.getSourceFileId(), "storageObjectId",
								source.getStorageObjectId(), "attempt", job.getProcessingAttempts()));
				stage = "SOURCE_TRANSCRIPTION";
				SourceKind kind = SourceKind.valueOf(source.getSourceKind());
				if (kind == SourceKind.MEDIA)
					source.setQualityReport(qualityReportService.analyzeAndSave(source.getSourceFileId(), local, jobId,
							"MULTI_SOURCE", job.getClientSource(), "multiSourceJobs"));
				String transcript = kind == SourceKind.MEDIA
						? geminiService.callGeminiTranscriptionAPIWithFallback(local, source.getFileName(), jobId)
						: extractDocumentText(local, source);
				rejectGeminiErrorTranscript(transcript);
				guardrailService.validateSummaryInput(transcript, 0);
				source.setTranscriptText(transcript);
				sourceTranscriptService.saveTranscript(jobId, source);
				timedSegments.persist(source);
				// completedAt is the durable checkpoint: all derived transcript records must
				// exist before a later attempt is allowed to skip this source.
				source.setTranscriptionCompletedAt(new Date());
				sourceFileService.save(source);
				sources.add(source);
				job.setUpdatedAt(new Date());
				if (!saveWorkerProgress(job))
					return;
				event(job, "SOURCE_TRANSCRIPTION_COMPLETED", List.of(12), source.getSourceFileId(),
						Map.of("jobId", jobId, "sourceFileId", source.getSourceFileId()));
			}
			job.setSourceFiles(sources);
			job.setFinalTranscriptionCompletedAt(new Date());
			event(job, "ALL_TRANSCRIPTIONS_COMPLETED", List.of(9, 12), "initial",
					Map.of("jobId", jobId, "sourceCount", sources.size()));
			job.setStatus(ProcessingStatus.SUMMARIZING.name());
			job.setUpdatedAt(new Date());
			if (!saveWorkerProgress(job))
				return;
			stage = "MERGE";
			job.setMergeStartedAt(new Date());
			job.setUpdatedAt(new Date());
			if (!saveWorkerProgress(job))
				return;
			Summary summary = job.getMergedSummary();
			if (summary == null) {
				summary = parseMergedSummary(job, geminiService
						.generateTranscriptOnlySummary(buildMergedTranscript(sources), jobId, job.getOutputType()));
				job.setMergedSummary(summary);
				job.setUpdatedAt(new Date());
				if (!saveWorkerProgress(job))
					return;
				event(job, "MERGE_SUMMARY_CHECKPOINTED", List.of(9, 12), summary.getSummaryId(),
						Map.of("jobId", jobId, "summaryId", summary.getSummaryId()));
			} else {
				log.info("Resuming multi-source job from merged-summary checkpoint jobId={} summaryId={}", jobId,
						summary.getSummaryId());
			}
			stage = "DEDUPLICATION";
			List<SourceAttribution> attributions;
			try {
				attributions = semanticEvidence.evaluate(job, summary);
				job.setSemanticEnrichmentStatus("COMPLETE");
				job.setSemanticEnrichmentFailure(null);
				event(job, "DEDUPLICATION_COMPLETED", List.of(10, 11), "initial", Map.of("jobId", jobId, "outputCount",
						summary.getKeyPoints().size(), "algorithmVersion", "semantic-clustering-batch-v2"));
			} catch (Exception enrichmentFailure) {
				// Quota failures are resumable job state, not degraded semantic output. Let
				// the outer handler persist quotaRetryAt and requeue the durable job.
				if (quotaRetryAt(enrichmentFailure) != null)
					throw enrichmentFailure;
				// The user-facing merged summary is already durable. Attribution is useful
				// evidence, but it must not discard an otherwise valid result.
				attributions = List.of();
				job.setMeasurementIncomplete(true);
				job.setSemanticEnrichmentStatus("DEGRADED");
				job.setSemanticEnrichmentFailure(sanitizeFailure(enrichmentFailure.getMessage()));
				try {
					semanticEvidence.persistFailedRun(job, stage, failureCode(enrichmentFailure),
							job.getProcessingAttempts());
				} catch (RuntimeException evidenceFailure) {
					log.warn("Could not persist degraded semantic evidence jobId={}", jobId, evidenceFailure);
				}
				log.warn("Completing multi-source job with degraded semantic enrichment jobId={} reason={}", jobId,
						job.getSemanticEnrichmentFailure());
				event(job, "SEMANTIC_ENRICHMENT_DEGRADED", List.of(10, 11, 12), "initial",
						Map.of("jobId", jobId, "failureCode", failureCode(enrichmentFailure)));
			}
			summaryService.createSummary(summary);
			event(job, "OUTPUT_GENERATED", List.of(1), summary.getSummaryId(),
					Map.of("jobId", jobId, "summaryId", summary.getSummaryId(), "outputType", summary.getOutputType(),
							"summaryStatus", summary.getStatus(), "keyPointCount", summary.getKeyPoints().size(),
							"topicCount", summary.getTopics().size(), "glossaryCount", summary.getGlossary().size(),
							"flashcardCount", summary.getFlashcards().size()));
			MergedSummary merged = buildMergedSummaryRecord(job, summary, attributions);
			mergedSummaryRepository.save(merged);
			job.setMergedSummary(summary);
			job.setMergedSummaryAvailableAt(new Date());
			job.setMergeLatencyMs(
					job.getMergedSummaryAvailableAt().getTime() - job.getFinalTranscriptionCompletedAt().getTime());
			event(job, "MERGED_SUMMARY_AVAILABLE", List.of(12), summary.getSummaryId(),
					Map.of("jobId", jobId, "summaryId", summary.getSummaryId()));
			job.setStatus(ProcessingStatus.COMPLETE.name());
			job.setFinalAttemptCount(job.getProcessingAttempts());
			job.setUpdatedAt(new Date());
			applyGeminiUsage(job, true);
			releaseDailyAdmission(job.getJobId());
			terminalEvent(job, "JOB_COMPLETED", List.of(9), "initial", Map.of("jobId", jobId, "outcome", "COMPLETE"));
		} catch (Exception e) {
			log.error("Multi-source processing failed jobId={} stage={} attempt={}", jobId, stage,
					job.getProcessingAttempts(), e);
			job.setFailureReason(sanitizeFailure(e.getMessage()));
			job.setFailureStage(stage);
			job.setFailureCode(failureCode(e));
			job.setFailureDetail(sanitizeFailure(e.getMessage()));
			job.setUpdatedAt(new Date());
			try {
				semanticEvidence.persistFailedRun(job, stage, job.getFailureCode(), job.getProcessingAttempts());
			} catch (RuntimeException evidenceFailure) {
				job.setMeasurementIncomplete(true);
			}
			Instant quotaRetryAt = quotaRetryAt(e);
			if (quotaRetryAt != null) {
				applyGeminiUsage(job, false);
				job.setStatus(ProcessingStatus.PROCESSING_QUEUED.name());
				job.setQuotaRetryAt(Date.from(quotaRetryAt));
				job.setUpdatedAt(new Date());
				if (saveWorkerProgress(job)) {
					publisher.publishToProcessingExchange(RabbitMQConfig.MULTI_SOURCE_QUOTA_RETRY_ROUTING_KEY,
							new MultiSourceJobMessage(jobId));
					event(job, "JOB_QUOTA_DEFERRED", List.of(9, 12), "attempt-" + job.getProcessingAttempts(),
							Map.of("jobId", jobId, "retryAt", quotaRetryAt.toString(), "failureStage", stage));
				}
				return;
			}
			boolean permanent = isPermanent(e);
			if (!permanent && job.getProcessingAttempts() < 3) {
				applyGeminiUsage(job, false);
				job.setStatus(ProcessingStatus.PROCESSING_QUEUED.name());
				if (saveWorkerProgress(job)) {
					publisher.publishToProcessingExchange(RabbitMQConfig.MULTI_SOURCE_RETRY_ROUTING_KEY,
							new MultiSourceJobMessage(jobId));
					event(job, "JOB_RETRY_QUEUED", List.of(9, 12), "attempt-" + job.getProcessingAttempts(),
							Map.of("jobId", jobId, "outcome", "RETRY_QUEUED", "attempt", job.getProcessingAttempts()));
				}
			} else {
				job.setStatus(ProcessingStatus.FAILED.name());
				job.setFailedAt(new Date());
				job.setFinalAttemptCount(job.getProcessingAttempts());
				applyGeminiUsage(job, true);
				releaseDailyAdmission(job.getJobId());
				terminalEvent(job, "JOB_FAILED", List.of(9, 12), "initial",
						Map.of("jobId", jobId, "outcome", "FAILED", "attempts", job.getProcessingAttempts(),
								"failureStage", stage, "failureCode", job.getFailureCode()));
			}
		} finally {
			for (Path file : localFiles)
				Files.deleteIfExists(file);
		}
	}

	private void releaseDailyAdmission(String jobId) {
		try {
			requestGovernor.releaseDailyAdmission(jobId);
		} catch (RuntimeException releaseFailure) {
			log.error("Could not release unused Gemini daily admission jobId={}", jobId, releaseFailure);
		}
	}

	private void applyGeminiUsage(MultiSourceJob job, boolean clear) {
		GeminiRequestGovernor.UsageSnapshot usage = clear
				? requestGovernor.snapshotAndClear(job.getJobId())
				: requestGovernor.snapshot(job.getJobId());
		if (usage.requestAttempts() == 0 && job.getActualGeminiRequests() != null)
			return;
		job.setActualGeminiRequests(usage.requestAttempts());
		job.setRetryGeminiRequests(usage.retryRequests());
		job.setTopUpGeminiRequests(usage.topUpRequests());
		job.setLastGeminiAttemptOperation(usage.lastAttemptOperation());
		job.setGeminiPacingWaitMs(usage.pacingWaitMs());
		job.setGeminiFirstRequestAt(usage.firstRequestAt() == null ? null : Date.from(usage.firstRequestAt()));
		job.setGeminiLastRequestAt(usage.lastRequestAt() == null ? null : Date.from(usage.lastRequestAt()));
		long requestWindowMs = usage.firstRequestAt() == null || usage.lastRequestAt() == null
				? 0
				: Duration.between(usage.firstRequestAt(), usage.lastRequestAt()).toMillis();
		Map<String, Object> payload = new HashMap<>();
		payload.put("jobId", job.getJobId());
		payload.put("estimatedGeminiRequests", job.getEstimatedGeminiRequests());
		payload.put("actualGeminiRequests", usage.requestAttempts());
		payload.put("retryGeminiRequests", usage.retryRequests());
		payload.put("topUpGeminiRequests", usage.topUpRequests());
		payload.put("lastAttemptOperation",
				usage.lastAttemptOperation() == null ? "NONE" : usage.lastAttemptOperation());
		payload.put("pacingWaitMs", usage.pacingWaitMs());
		payload.put("requestWindowMs", requestWindowMs);
		event(job, "GEMINI_USAGE_RECORDED", List.of(9, 12), "attempt-" + job.getProcessingAttempts(), payload);
		log.info(
				"Multi-source Gemini usage jobId={} estimatedRequests={} actualRequests={} pacingWaitMs={} requestWindowMs={}",
				job.getJobId(), job.getEstimatedGeminiRequests(), usage.requestAttempts(), usage.pacingWaitMs(),
				requestWindowMs);
	}

	public java.util.Map<String, Object> getJobMap(String jobId) {
		return multiSourceJobRepository.findById(jobId);
	}

	public Map<String, Object> getJobMapForClient(String jobId) {
		return clientView(multiSourceJobRepository.findById(jobId));
	}

	public List<Map<String, Object>> getJobsForUser(String userId) {
		return multiSourceJobRepository.findByUserId(userId).stream().map(this::clientView)
				.sorted(Comparator.comparingLong(this::jobTimestamp).reversed()).limit(25).map(this::listView).toList();
	}

	private Map<String, Object> listView(Map<String, Object> job) {
		Map<String, Object> result = new HashMap<>(job);
		result.remove("mergedSummary");
		result.remove("sourceFiles");
		return result;
	}

	private long jobTimestamp(Map<String, Object> job) {
		Date value = date(job.get("updatedAt"));
		if (value == null)
			value = date(job.get("acceptedAt"));
		return value == null ? 0L : value.getTime();
	}

	private Map<String, Object> clientView(Map<String, Object> row) {
		if (row == null)
			return null;
		Map<String, Object> result = new HashMap<>(row);
		result.remove("failureDetail");
		if (row.get("sourceFiles") instanceof List<?> rawSources) {
			List<Map<String, Object>> compact = new ArrayList<>();
			for (Object raw : rawSources) {
				if (!(raw instanceof Map<?, ?> source))
					continue;
				Map<String, Object> item = new HashMap<>();
				for (String key : List.of("sourceFileId", "sourceLabel", "sourceKind", "fileName", "contentType",
						"fileSize", "durationSeconds", "uploadStatus", "uploadCompletedAt", "transcriptionCompletedAt"))
					if (source.containsKey(key))
						item.put(key, source.get(key));
				compact.add(item);
			}
			result.put("sourceFiles", compact);
		}
		return result;
	}

	private String extractDocumentText(Path local, SourceFile source) {
		try {
			return documentTextExtractionService.extractText(local, source.getFileName(), source.getContentType());
		} catch (IOException | IllegalArgumentException e) {
			throw new MultiSourceProcessingException("Document text extraction failed for " + source.getFileName(),
					"DOCUMENT_EXTRACTION_FAILED", false, e);
		}
	}

	private void rejectGeminiErrorTranscript(String transcript) throws IOException {
		if (!StringUtils.hasText(transcript)) {
			throw new IOException("Gemini transcription returned no text");
		}
		try {
			JsonNode root = OBJECT_MAPPER.readTree(transcript);
			if (root.has("error")) {
				String details = root.path("details").asText(root.toString());
				throw new IOException("Gemini transcription failed: " + details);
			}
		} catch (JsonProcessingException ignored) {
			// Normal transcripts are plain text, not JSON.
		}
	}

	private Summary parseMergedSummary(MultiSourceJob job, String summaryJson) throws IOException {
		com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(summaryJson);
		if (root.has("candidates")) {
			String text = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
			root = OBJECT_MAPPER.readTree(text);
		}
		if (root.has("error")) {
			throw new IOException("Gemini summarization failed: " + root.path("details").asText(root.toString()));
		}
		if (!root.has("summaryText") || root.path("summaryText").asText().isBlank()) {
			throw new IOException("Gemini summarization response did not contain summaryText");
		}
		Summary summary = new Summary();
		summary.setSummaryId(ValidationEventService.digest(job.getJobId() + ":merged-summary"));
		summary.setRecordingId(job.getJobId());
		summary.setUserId(job.getUserId());
		summary.setOutputType(job.getOutputType());
		summary.setStatus(ProcessingStatus.COMPLETE.name());
		summary.setFormattedSummaryText(root.path("summaryText").asText());
		List<String> keyPoints = new ArrayList<>();
		if (root.path("keyPoints").isArray()) {
			root.path("keyPoints").forEach(node -> keyPoints.add(node.asText()));
		}
		summary.setKeyPoints(deduplicationService.removeDuplicateText(keyPoints));
		List<String> topics = new ArrayList<>();
		if (root.path("topics").isArray()) {
			root.path("topics").forEach(node -> topics.add(node.asText()));
		}
		summary.setTopics(topics);
		List<Map<String, String>> glossary = new ArrayList<>();
		if (root.path("glossary").isArray()) {
			for (com.fasterxml.jackson.databind.JsonNode glossaryItem : root.path("glossary")) {
				String term = glossaryItem.path("term").asText("").trim();
				String definition = glossaryItem.path("definition").asText("").trim();
				if (!term.isEmpty() && !definition.isEmpty()) {
					Map<String, String> item = new HashMap<>();
					item.put("term", term);
					item.put("definition", definition);
					glossary.add(item);
				}
			}
		}
		summary.setGlossary(glossary);
		List<Flashcard> flashcards = parseFlashcards(root);
		if (OutputType.REVIEW_MATERIAL.name().equals(job.getOutputType()) && flashcards.isEmpty()) {
			flashcards = buildFallbackFlashcards(glossary);
		}
		summary.setFlashcards(deduplicationService.removeDuplicateFlashcards(flashcards));
		return summary;
	}

	private List<Flashcard> parseFlashcards(com.fasterxml.jackson.databind.JsonNode root) {
		List<Flashcard> flashcards = new ArrayList<>();
		if (!root.path("flashcards").isArray()) {
			return flashcards;
		}
		for (com.fasterxml.jackson.databind.JsonNode cardNode : root.path("flashcards")) {
			String front = cardNode.path("front").asText("").trim();
			String back = cardNode.path("back").asText("").trim();
			if (front.isEmpty() || back.isEmpty()) {
				continue;
			}
			Flashcard flashcard = new Flashcard(front, back);
			String sourceStartTime = cardNode.path("sourceStartTime").asText("").trim();
			String sourceEndTime = cardNode.path("sourceEndTime").asText("").trim();
			flashcard.setSourceStartTime(sourceStartTime.isEmpty() ? null : sourceStartTime);
			flashcard.setSourceEndTime(sourceEndTime.isEmpty() ? null : sourceEndTime);
			flashcards.add(flashcard);
		}
		return flashcards;
	}

	private List<Flashcard> buildFallbackFlashcards(List<Map<String, String>> glossary) {
		List<Flashcard> flashcards = new ArrayList<>();
		for (Map<String, String> item : glossary) {
			String term = item.get("term");
			String definition = item.get("definition");
			if (StringUtils.hasText(term) && StringUtils.hasText(definition)) {
				flashcards.add(new Flashcard(term.trim(), definition.trim()));
			}
		}
		return flashcards;
	}

	private String buildMergedTranscript(List<SourceFile> sourceFiles) {
		StringBuilder builder = new StringBuilder();
		builder.append("Create one unified, deduplicated summary from these labeled lecture sources. ");
		builder.append(
				"Preserve unique points and mention source labels when content appears to come from only one source.\n\n");
		for (SourceFile sourceFile : sourceFiles) {
			builder.append("[").append(sourceFile.getSourceLabel()).append("]\n");
			builder.append(sourceFile.getTranscriptText()).append("\n\n");
		}
		return builder.toString();
	}

	private MergedSummary buildMergedSummaryRecord(MultiSourceJob job, Summary summary) {
		MergedSummary mergedSummary = new MergedSummary();
		mergedSummary.setJobId(job.getJobId());
		mergedSummary.setUserId(job.getUserId());
		mergedSummary.setContent(summary.getFormattedSummaryText());
		mergedSummary.setFlashcards(summary.getFlashcards());
		mergedSummary.setStatus(ProcessingStatus.COMPLETE.name());

		List<KeyPoint> keyPoints = new ArrayList<>();
		for (String keyPointText : summary.getKeyPoints()) {
			keyPoints.add(new KeyPoint(keyPointText, detectSourceLabel(keyPointText, job.getSourceFiles())));
		}
		List<SourceAttribution> attributions = deduplicationService.removeDuplicateKeyPoints(keyPoints).stream()
				.map(sourceAttributionService::assignAttribution).toList();
		attributions
				.forEach(attribution -> sourceAttributionService.save(mergedSummary.getMergedSummaryId(), attribution));
		mergedSummary.setSourceAttributions(attributions);
		return mergedSummary;
	}

	private MergedSummary buildMergedSummaryRecord(MultiSourceJob job, Summary summary,
			List<SourceAttribution> attributions) {
		MergedSummary merged = new MergedSummary();
		merged.setMergedSummaryId(ValidationEventService.digest(job.getJobId() + ":merged-summary-record"));
		merged.setJobId(job.getJobId());
		merged.setUserId(job.getUserId());
		merged.setContent(summary.getFormattedSummaryText());
		merged.setFlashcards(summary.getFlashcards());
		merged.setStatus(ProcessingStatus.COMPLETE.name());
		for (SourceAttribution attribution : attributions)
			sourceAttributionService.save(merged.getMergedSummaryId(), attribution);
		merged.setSourceAttributions(attributions);
		return merged;
	}

	private void event(MultiSourceJob job, String type, List<Integer> objectives, String key, Map<String, ?> payload) {
		if (!events.emit(job.getJobId(), "MULTI_SOURCE", job.getClientSource(), type, objectives, key, payload,
				"multiSourceJobs"))
			job.setMeasurementIncomplete(true);
	}

	private boolean saveWorkerProgress(MultiSourceJob job) {
		return multiSourceJobRepository.saveIfNonterminal(job);
	}

	private void terminalEvent(MultiSourceJob job, String type, List<Integer> objectives, String key,
			Map<String, Object> payload) {
		if (!multiSourceJobRepository.transitionTerminal(job, type, key, objectives, payload))
			return;
		if (events.emit(job.getJobId(), "MULTI_SOURCE", job.getClientSource(), type, objectives, key, payload,
				"multiSourceJobs")) {
			try {
				multiSourceJobRepository.markTerminalEventPublished(job.getJobId());
			} catch (RuntimeException ignored) {
				// The pending marker intentionally remains true for scheduled redelivery.
			}
		} else
			job.setMeasurementIncomplete(true);
	}

	private MultiSourceJob hydrateJob(Map<String, Object> row) {
		if (row == null)
			throw new IllegalArgumentException("Multi-source job not found");
		MultiSourceJob job = new MultiSourceJob();
		job.setJobId((String) row.get("jobId"));
		job.setUserId((String) row.get("userId"));
		job.setTitle((String) row.get("title"));
		job.setDescription((String) row.get("description"));
		job.setOutputType((String) row.get("outputType"));
		job.setStatus((String) row.get("status"));
		job.setFailureReason((String) row.get("failureReason"));
		job.setClientSource((String) row.getOrDefault("clientSource", "UNKNOWN"));
		job.setSourceCount(row.get("sourceCount") instanceof Number n ? n.intValue() : 0);
		job.setMeasurementIncomplete(Boolean.TRUE.equals(row.get("measurementIncomplete")));
		job.setProcessingAttempts(row.get("processingAttempts") instanceof Number n ? n.intValue() : 0);
		if (row.get("finalAttemptCount") instanceof Number n)
			job.setFinalAttemptCount(n.intValue());
		if (row.get("estimatedGeminiRequests") instanceof Number n)
			job.setEstimatedGeminiRequests(n.intValue());
		if (row.get("actualGeminiRequests") instanceof Number n)
			job.setActualGeminiRequests(n.intValue());
		if (row.get("retryGeminiRequests") instanceof Number n)
			job.setRetryGeminiRequests(n.intValue());
		if (row.get("topUpGeminiRequests") instanceof Number n)
			job.setTopUpGeminiRequests(n.intValue());
		job.setLastGeminiAttemptOperation((String) row.get("lastGeminiAttemptOperation"));
		if (row.get("geminiPacingWaitMs") instanceof Number n)
			job.setGeminiPacingWaitMs(n.longValue());
		job.setGeminiFirstRequestAt(date(row.get("geminiFirstRequestAt")));
		job.setGeminiLastRequestAt(date(row.get("geminiLastRequestAt")));
		job.setQuotaRetryAt(date(row.get("quotaRetryAt")));
		job.setCreatedAt(date(row.get("createdAt")));
		job.setUpdatedAt(date(row.get("updatedAt")));
		job.setSemanticEnrichmentStatus((String) row.get("semanticEnrichmentStatus"));
		job.setSemanticEnrichmentFailure((String) row.get("semanticEnrichmentFailure"));
		job.setFailureStage((String) row.get("failureStage"));
		job.setFailureCode((String) row.get("failureCode"));
		job.setFailureDetail((String) row.get("failureDetail"));
		if (row.get("sourceFiles") instanceof List<?> rawSources) {
			List<SourceFile> sources = new ArrayList<>();
			for (Object raw : rawSources)
				if (raw instanceof Map<?, ?> map) {
					Map<String, Object> typed = new HashMap<>();
					map.forEach((key, value) -> typed.put(String.valueOf(key), value));
					sources.add(hydrateSource(typed));
				}
			job.setSourceFiles(sources);
		}
		if (row.get("mergedSummary") instanceof Map<?, ?> rawSummary) {
			Map<String, Object> summary = new HashMap<>();
			rawSummary.forEach((key, value) -> summary.put(String.valueOf(key), value));
			job.setMergedSummary(Summary.fromMap(summary));
		}
		job.setAcceptedAt(date(row.get("acceptedAt")));
		job.setQueuedAt(date(row.get("queuedAt")));
		job.setProcessingStartedAt(date(row.get("processingStartedAt")));
		job.setFinalTranscriptionCompletedAt(date(row.get("finalTranscriptionCompletedAt")));
		job.setMergeStartedAt(date(row.get("mergeStartedAt")));
		job.setMergedSummaryAvailableAt(date(row.get("mergedSummaryAvailableAt")));
		job.setFailedAt(date(row.get("failedAt")));
		if (row.get("mergeLatencyMs") instanceof Number n)
			job.setMergeLatencyMs(n.longValue());
		return job;
	}

	private SourceFile hydrateSource(Map<String, Object> row) {
		SourceFile source = new SourceFile();
		source.setSourceFileId((String) row.get("sourceFileId"));
		source.setJobId((String) row.get("jobId"));
		source.setSourceLabel((String) row.get("sourceLabel"));
		source.setSourceKind((String) row.get("sourceKind"));
		source.setFileUrl((String) row.get("fileUrl"));
		source.setStorageObjectId((String) row.get("storageObjectId"));
		source.setFileName((String) row.get("fileName"));
		source.setContentType((String) row.get("contentType"));
		source.setFileType((String) row.get("fileType"));
		source.setUploadStatus((String) row.get("uploadStatus"));
		source.setTranscriptText((String) row.get("transcriptText"));
		if (row.get("fileSize") instanceof Number n)
			source.setFileSize(n.longValue());
		if (row.get("durationSeconds") instanceof Number n)
			source.setDurationSeconds(n.longValue());
		if (row.get("estimatedGeminiAudioTokens") instanceof Number n)
			source.setEstimatedGeminiAudioTokens(n.longValue());
		source.setAudioFingerprint((String) row.get("audioFingerprint"));
		if (row.get("qualityReport") instanceof Map<?, ?> rawQuality) {
			Map<String, Object> quality = new HashMap<>();
			rawQuality.forEach((key, value) -> quality.put(String.valueOf(key), value));
			source.setQualityReport(QualityReport.fromMap(quality));
		}
		source.setCreatedAt(date(row.get("createdAt")));
		source.setUploadCompletedAt(date(row.get("uploadCompletedAt")));
		source.setRetrievedAt(date(row.get("retrievedAt")));
		source.setTranscriptionCompletedAt(date(row.get("transcriptionCompletedAt")));
		return source;
	}

	private Date date(Object value) {
		if (value instanceof Date date)
			return date;
		if (value instanceof com.google.cloud.Timestamp timestamp)
			return timestamp.toDate();
		return null;
	}

	private Path downloadSource(SourceFile source) throws Exception {
		String extension = StringUtils.getFilenameExtension(source.getFileName());
		Path target = tempDir.resolve("multi-worker-" + UUID.randomUUID() + (extension == null ? "" : "." + extension));
		if (!StringUtils.hasText(source.getStorageObjectId()))
			throw new StorageRetrievalException("Source has no storage object ID.", "STORAGE_INVALID_IDENTIFIER", 0,
					false, null);
		log.info("Multi-source retrieval jobId={} sourceFileId={} objectId={} processingStage=SOURCE_RETRIEVAL",
				jobIdFor(source), source.getSourceFileId(), source.getStorageObjectId());
		sourceFileService.retrieveToPath(source.getStorageObjectId(), target);
		return target;
	}

	private String jobIdFor(SourceFile source) {
		return StringUtils.hasText(source.getJobId()) ? source.getJobId() : "unknown";
	}

	private String failureCode(Exception failure) {
		if (failure instanceof GeminiStageRequestLimitException)
			return "GEMINI_STAGE_REQUEST_LIMIT";
		if (failure instanceof StorageRetrievalException storage)
			return storage.getFailureCode();
		if (failure instanceof MultiSourceProcessingException processing)
			return processing.getFailureCode();
		return "MULTI_SOURCE_PROCESSING_FAILED";
	}

	private boolean isPermanent(Exception failure) {
		if (failure instanceof GeminiStageRequestLimitException)
			return true;
		if (failure instanceof ProcessingGuardrailException || failure instanceof IllegalArgumentException)
			return true;
		if (failure instanceof StorageRetrievalException storage)
			return !storage.isRetryable();
		if (failure instanceof MultiSourceProcessingException processing)
			return !processing.isRetryable();
		return false;
	}

	private Instant quotaRetryAt(Throwable failure) {
		if (failure == null)
			return null;
		if (failure instanceof GeminiBudgetExceededException exceeded)
			return exceeded.getRetryAt();
		if (failure instanceof GeminiRateLimitException rateLimited)
			return rateLimited.getRetryAt();
		Throwable cause = failure.getCause();
		return cause == failure ? null : quotaRetryAt(cause);
	}

	private void validatePersistedSources(MultiSourceJob job, List<Map<String, Object>> sourceRows) {
		int expected = job.getSourceCount() == null ? -1 : job.getSourceCount();
		if (expected < 2 || expected > 5 || sourceRows.size() != expected)
			throw new MultiSourceProcessingException(
					"Persisted sources do not match the accepted two-to-five-file batch.", "SOURCE_COUNT_MISMATCH",
					false);
	}

	private String sanitizeFailure(String detail) {
		if (!StringUtils.hasText(detail))
			return "Processing failed without provider detail.";
		return detail.replaceAll("(?i)(https?://[^?\\s]+)\\?[^\\s]+", "$1?[REDACTED]")
				.replaceAll("(?i)(x-hasura-admin-secret|authorization)[:=]\\s*[^,\\s]+", "$1=[REDACTED]");
	}

	private String detectSourceLabel(String text, List<SourceFile> sourceFiles) {
		if (text == null || sourceFiles == null) {
			return null;
		}
		for (SourceFile sourceFile : sourceFiles) {
			if (sourceFile.getSourceLabel() != null && text.contains(sourceFile.getSourceLabel())) {
				return sourceFile.getSourceLabel();
			}
		}
		return null;
	}

	private List<MultipartFile> normalizeFiles(List<MultipartFile> files) {
		if (files == null) {
			return List.of();
		}
		return files;
	}

	private void validateFiles(List<MultipartFile> mediaFiles, List<MultipartFile> documentFiles) {
		if (mediaFiles.size() + documentFiles.size() < 2) {
			throw new IllegalArgumentException("Select at least two sources.");
		}
		if (mediaFiles.size() + documentFiles.size() > 5) {
			throw new IllegalArgumentException("Select no more than five sources.");
		}
		long maxBytes = DataSize.parse(maxFileSizeValue).toBytes();
		for (MultipartFile file : mediaFiles) {
			if (file == null || file.isEmpty()) {
				throw new IllegalArgumentException("One of the selected media source files is empty.");
			}
			if (!isAllowedMedia(file)) {
				throw new IllegalArgumentException("Unsupported media source file type: " + describeType(file));
			}
			if (file.getSize() > maxBytes) {
				throw new IllegalArgumentException("A media source file exceeds the maximum allowed size.");
			}
		}
		for (MultipartFile file : documentFiles) {
			if (file == null || file.isEmpty()) {
				throw new IllegalArgumentException("One of the selected document source files is empty.");
			}
			if (!isAllowedDocument(file)) {
				throw new IllegalArgumentException("Unsupported document source file type: " + describeType(file));
			}
			if (file.getSize() > maxBytes) {
				throw new IllegalArgumentException("A document source file exceeds the maximum allowed size.");
			}
		}
	}

	private boolean isAllowedMedia(MultipartFile file) {
		String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
		return ALLOWED_MEDIA_TYPES.contains(type) || ALLOWED_MEDIA_EXTENSIONS.contains(extensionOf(file));
	}

	private boolean isAllowedDocument(MultipartFile file) {
		String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
		return ALLOWED_DOCUMENT_TYPES.contains(type) || ALLOWED_DOCUMENT_EXTENSIONS.contains(extensionOf(file));
	}

	private String extensionOf(MultipartFile file) {
		String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());
		return extension != null ? extension.toLowerCase() : "";
	}

	private String describeType(MultipartFile file) {
		String type = file.getContentType();
		String extension = extensionOf(file);
		if (StringUtils.hasText(type) && StringUtils.hasText(extension)) {
			return type + " (." + extension + ")";
		}
		if (StringUtils.hasText(type)) {
			return type;
		}
		return StringUtils.hasText(extension) ? "." + extension : "unknown";
	}

	private Path saveTemp(MultipartFile file) throws IOException {
		String original = StringUtils
				.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "source");
		String ext = StringUtils.getFilenameExtension(original);
		Path target = tempDir.resolve("multi-source-" + UUID.randomUUID() + (ext != null ? "." + ext : ""));
		try (InputStream input = file.getInputStream()) {
			Files.copy(input, target);
		}
		return target;
	}

	private void save(MultiSourceJob job) throws Exception {
		multiSourceJobRepository.save(job);
	}

	private record PendingSource(MultipartFile file, Path tempFile, SourceKind sourceKind, SourceFile sourceFile,
			String sourceLabel) {
	}
}
