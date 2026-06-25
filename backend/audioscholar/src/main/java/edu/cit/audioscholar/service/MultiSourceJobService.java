package edu.cit.audioscholar.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

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

	public MultiSourceJobService(GeminiService geminiService, QualityReportService qualityReportService,
			SummaryService summaryService, DeduplicationService deduplicationService,
			SourceAttributionService sourceAttributionService, SourceFileService sourceFileService,
			SourceTranscriptService sourceTranscriptService,
			DocumentTextExtractionService documentTextExtractionService,
			MergedSummaryRepository mergedSummaryRepository, MultiSourceJobRepository multiSourceJobRepository,
			AudioProcessingGuardrailService guardrailService, @Value("${app.temp-file-dir}") String tempDirStr,
			@Value("${spring.servlet.multipart.max-file-size}") String maxFileSizeValue) throws IOException {
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
		Files.createDirectories(this.tempDir);
	}

	public MultiSourceJob createAndProcess(String userId, List<MultipartFile> mediaFiles,
			List<MultipartFile> documentFiles, String title, String description, String outputTypeValue)
			throws Exception {
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
		save(job);

		List<Path> tempFiles = new ArrayList<>();
		try {
			List<PendingSource> pendingSources = new ArrayList<>();
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
				pendingSources.add(new PendingSource(file, tempFile, sourceKind, sourceFile, sourceLabel));
			}
			guardrailService.validateMultiSourceAggregate(mediaGuardrails);

			List<SourceFile> sourceFiles = new ArrayList<>();
			for (PendingSource pending : pendingSources) {
				MultipartFile file = pending.file();
				Path tempFile = pending.tempFile();
				SourceKind sourceKind = pending.sourceKind();
				SourceFile sourceFile = pending.sourceFile();
				String sourceLabel = pending.sourceLabel();
				QualityReport report = SourceKind.MEDIA == sourceKind
						? qualityReportService.analyze(job.getJobId() + "-" + sourceLabel, tempFile)
						: QualityReport.unavailable(job.getJobId() + "-" + sourceLabel);
				sourceFile.setQualityReport(report);

				String transcript = SourceKind.MEDIA == sourceKind
						? geminiService.callGeminiTranscriptionAPIWithFallback(tempFile, file.getOriginalFilename())
						: documentTextExtractionService.extractText(tempFile, file.getOriginalFilename(),
								file.getContentType());
				sourceFile.setTranscriptText(transcript);
				sourceFiles.add(sourceFile);
				sourceFileService.save(sourceFile);
				sourceTranscriptService.saveTranscript(job.getJobId(), sourceFile);
			}

			job.setSourceFiles(sourceFiles);
			job.setStatus(ProcessingStatus.SUMMARIZING.name());
			job.setUpdatedAt(new Date());
			save(job);

			String mergedTranscript = buildMergedTranscript(sourceFiles);
			String summaryJson = geminiService.generateTranscriptOnlySummary(mergedTranscript, job.getJobId(),
					outputType.name());
			Summary summary = parseMergedSummary(job, summaryJson);
			summaryService.createSummary(summary);
			MergedSummary mergedSummary = buildMergedSummaryRecord(job, summary);
			mergedSummaryRepository.save(mergedSummary);
			job.setMergedSummary(summary);
			job.setStatus(ProcessingStatus.COMPLETE.name());
			job.setUpdatedAt(new Date());
			save(job);
			return job;
		} catch (Exception e) {
			job.setStatus(ProcessingStatus.FAILED.name());
			job.setFailureReason(e.getMessage());
			job.setUpdatedAt(new Date());
			save(job);
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

	public java.util.Map<String, Object> getJobMap(String jobId) {
		return multiSourceJobRepository.findById(jobId);
	}

	private Summary parseMergedSummary(MultiSourceJob job, String summaryJson) throws IOException {
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(summaryJson);
		if (root.has("candidates")) {
			String text = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
			root = mapper.readTree(text);
		}
		Summary summary = new Summary();
		summary.setSummaryId(UUID.randomUUID().toString());
		summary.setRecordingId(job.getJobId());
		summary.setUserId(job.getUserId());
		summary.setOutputType(job.getOutputType());
		summary.setStatus(ProcessingStatus.COMPLETE.name());
		summary.setFormattedSummaryText(root.path("summaryText").asText(summaryJson));
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
		return summary;
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
