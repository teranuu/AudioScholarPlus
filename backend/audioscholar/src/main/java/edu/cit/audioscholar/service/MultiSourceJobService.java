package edu.cit.audioscholar.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import edu.cit.audioscholar.model.Flashcard;
import edu.cit.audioscholar.model.KeyPoint;
import edu.cit.audioscholar.model.MergedSummary;
import edu.cit.audioscholar.model.MultiSourceJob;
import edu.cit.audioscholar.model.OutputType;
import edu.cit.audioscholar.model.ProcessingStatus;
import edu.cit.audioscholar.model.SourceAttribution;
import edu.cit.audioscholar.model.SourceFile;
import edu.cit.audioscholar.model.SourceKind;
import edu.cit.audioscholar.model.Summary;

@Service
public class MultiSourceJobService {
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

	public MultiSourceJobService(GeminiService geminiService, QualityReportService qualityReportService,
			SummaryService summaryService, DeduplicationService deduplicationService,
			SourceAttributionService sourceAttributionService, SourceFileService sourceFileService,
			SourceTranscriptService sourceTranscriptService,
			DocumentTextExtractionService documentTextExtractionService,
			MergedSummaryRepository mergedSummaryRepository, MultiSourceJobRepository multiSourceJobRepository,
			AudioProcessingGuardrailService guardrailService, @Value("${app.temp-file-dir}") String tempDirStr,
			@Value("${spring.servlet.multipart.max-file-size}") String maxFileSizeValue,
			ConfirmedRabbitPublisher publisher, ValidationEventService events,
			TimedTranscriptSegmentService timedSegments, SemanticEvidenceService semanticEvidence) throws IOException {
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
		OutputType outputType = OutputType.fromValue(outputTypeValue);
		List<MultipartFile> normalizedMediaFiles = normalizeFiles(mediaFiles);
		List<MultipartFile> normalizedDocumentFiles = normalizeFiles(documentFiles);
		validateFiles(normalizedMediaFiles, normalizedDocumentFiles);
		guardrailService.validateFileCount(normalizedMediaFiles, normalizedDocumentFiles);
		guardrailService.validateUploadBytes(normalizedMediaFiles, normalizedDocumentFiles);

		MultiSourceJob job = new MultiSourceJob();
		job.setUserId(userId);
		job.setTitle(StringUtils.hasText(title) ? title : "Merged Lecture Summary");
		job.setDescription(description);
		job.setOutputType(outputType.name());
		job.setStatus(ProcessingStatus.PROCESSING_QUEUED.name());
		job.setSourceCount(normalizedMediaFiles.size() + normalizedDocumentFiles.size());
		job.setClientSource(ValidationEventService.clientSource(clientHeader));
		job.setAcceptedAt(new Date());
		save(job);
		event(job, "BATCH_VALIDATED", List.of(9), "initial", Map.of("sourceCount", job.getSourceCount()));
		event(job, "CONFIG_CONFIRMED", List.of(2), "initial",
				Map.of("jobId", job.getJobId(), "outputType", outputType.name()));
		event(job, "JOB_ACCEPTED", List.of(2, 9), "initial", Map.of("jobId", job.getJobId()));

		List<Path> tempFiles = new ArrayList<>();
		try {
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

				SourceFile sourceFile = sourceFileService.createSourceFile(job.getJobId(), sourceLabel, sourceKind,
						file, tempFile);
				AudioProcessingGuardrailService.GuardrailResult guardrail = null;
				if (SourceKind.MEDIA == sourceKind) {
					guardrail = guardrailService.validateAudioFile(tempFile, file.getOriginalFilename());
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
			event(job, "ALL_SOURCES_UPLOADED", List.of(9), "initial", Map.of("sourceCount", job.getSourceCount()));
			job.setQueuedAt(new Date());
			save(job);
			publisher.publishToProcessingExchange(RabbitMQConfig.MULTI_SOURCE_ROUTING_KEY,
					new MultiSourceJobMessage(job.getJobId()));
			event(job, "JOB_QUEUED", List.of(9), "initial", Map.of("jobId", job.getJobId()));
			return job;
		} catch (Exception e) {
			job.setStatus(ProcessingStatus.FAILED.name());
			job.setFailureReason(e.getMessage());
			job.setUpdatedAt(new Date());
			save(job);
			event(job, "JOB_FAILED", List.of(9), "submission", Map.of("jobId", job.getJobId(), "outcome", "FAILED"));
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
		if (!multiSourceJobRepository.claim(jobId))
			return;
		MultiSourceJob job = hydrateJob(multiSourceJobRepository.findById(jobId));
		job.setStatus("PROCESSING");
		job.setProcessingStartedAt(new Date());
		job.setProcessingAttempts(job.getProcessingAttempts() + 1);
		save(job);
		event(job, "PROCESSING_STARTED", List.of(2, 9), "initial", Map.of("jobId", jobId));
		List<Path> localFiles = new ArrayList<>();
		try {
			List<SourceFile> sources = new ArrayList<>();
			for (Map<String, Object> row : sourceFileService.findByJobId(jobId)) {
				SourceFile source = hydrateSource(row);
				Path local = downloadSource(source);
				localFiles.add(local);
				SourceKind kind = SourceKind.valueOf(source.getSourceKind());
				if (kind == SourceKind.MEDIA)
					source.setQualityReport(qualityReportService.analyzeAndSave(source.getSourceFileId(), local, jobId,
							"MULTI_SOURCE", job.getClientSource(), "multiSourceJobs"));
				String transcript = kind == SourceKind.MEDIA
						? geminiService.callGeminiTranscriptionAPIWithFallback(local, source.getFileName())
						: documentTextExtractionService.extractText(local, source.getFileName(),
								source.getContentType());
				rejectGeminiErrorTranscript(transcript);
				source.setTranscriptText(transcript);
				source.setTranscriptionCompletedAt(new Date());
				sourceFileService.save(source);
				sourceTranscriptService.saveTranscript(jobId, source);
				timedSegments.persist(source);
				sources.add(source);
				event(job, "SOURCE_TRANSCRIPTION_COMPLETED", List.of(12), source.getSourceFileId(),
						Map.of("jobId", jobId, "sourceFileId", source.getSourceFileId()));
			}
			job.setSourceFiles(sources);
			job.setFinalTranscriptionCompletedAt(new Date());
			job.setStatus(ProcessingStatus.SUMMARIZING.name());
			save(job);
			Summary summary = parseMergedSummary(job, geminiService
					.generateTranscriptOnlySummary(buildMergedTranscript(sources), jobId, job.getOutputType()));
			List<SourceAttribution> attributions = semanticEvidence.evaluate(job, summary);
			summaryService.createSummary(summary);
			MergedSummary merged = buildMergedSummaryRecord(job, summary, attributions);
			mergedSummaryRepository.save(merged);
			job.setMergedSummary(summary);
			job.setMergedSummaryAvailableAt(new Date());
			event(job, "MERGED_SUMMARY_AVAILABLE", List.of(12), summary.getSummaryId(),
					Map.of("jobId", jobId, "summaryId", summary.getSummaryId()));
			job.setStatus(ProcessingStatus.COMPLETE.name());
			job.setUpdatedAt(new Date());
			save(job);
			event(job, "JOB_COMPLETED", List.of(9), "initial", Map.of("jobId", jobId));
		} catch (Exception e) {
			job.setFailureReason(e.getMessage());
			job.setUpdatedAt(new Date());
			if (job.getProcessingAttempts() < 3) {
				job.setStatus(ProcessingStatus.PROCESSING_QUEUED.name());
				save(job);
				publisher.publishToProcessingExchange(RabbitMQConfig.MULTI_SOURCE_ROUTING_KEY,
						new MultiSourceJobMessage(jobId),
						java.time.Duration.ofSeconds(job.getProcessingAttempts() * 5L));
				event(job, "JOB_RETRY_QUEUED", List.of(9, 12), "attempt-" + job.getProcessingAttempts(),
						Map.of("jobId", jobId, "outcome", "RETRY_QUEUED", "attempt", job.getProcessingAttempts()));
			} else {
				job.setStatus(ProcessingStatus.FAILED.name());
				save(job);
				event(job, "JOB_FAILED", List.of(9, 12), "initial",
						Map.of("jobId", jobId, "outcome", "FAILED", "attempts", job.getProcessingAttempts()));
			}
		} finally {
			for (Path file : localFiles)
				Files.deleteIfExists(file);
		}
	}

	public java.util.Map<String, Object> getJobMap(String jobId) {
		return multiSourceJobRepository.findById(jobId);
	}

	private void rejectGeminiErrorTranscript(String transcript) throws IOException {
		if (!StringUtils.hasText(transcript)) {
			return;
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
		summary.setSummaryId(UUID.randomUUID().toString());
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
		job.setClientSource((String) row.getOrDefault("clientSource", "UNKNOWN"));
		job.setSourceCount(row.get("sourceCount") instanceof Number n ? n.intValue() : 0);
		job.setMeasurementIncomplete(Boolean.TRUE.equals(row.get("measurementIncomplete")));
		job.setProcessingAttempts(row.get("processingAttempts") instanceof Number n ? n.intValue() : 0);
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
		job.setAcceptedAt(date(row.get("acceptedAt")));
		job.setQueuedAt(date(row.get("queuedAt")));
		job.setProcessingStartedAt(date(row.get("processingStartedAt")));
		return job;
	}

	private SourceFile hydrateSource(Map<String, Object> row) {
		SourceFile source = new SourceFile();
		source.setSourceFileId((String) row.get("sourceFileId"));
		source.setJobId((String) row.get("jobId"));
		source.setSourceLabel((String) row.get("sourceLabel"));
		source.setSourceKind((String) row.get("sourceKind"));
		source.setFileUrl((String) row.get("fileUrl"));
		source.setFileName((String) row.get("fileName"));
		source.setContentType((String) row.get("contentType"));
		source.setFileType((String) row.get("fileType"));
		source.setUploadStatus((String) row.get("uploadStatus"));
		if (row.get("durationSeconds") instanceof Number n)
			source.setDurationSeconds(n.longValue());
		source.setUploadCompletedAt(date(row.get("uploadCompletedAt")));
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
		try (InputStream input = URI.create(source.getFileUrl()).toURL().openStream()) {
			Files.copy(input, target);
		}
		return target;
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
		if (mediaFiles.size() < 2) {
			throw new IllegalArgumentException("Select at least two audio or video sources.");
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
