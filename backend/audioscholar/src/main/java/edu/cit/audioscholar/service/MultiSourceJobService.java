package edu.cit.audioscholar.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import edu.cit.audioscholar.model.MultiSourceJob;
import edu.cit.audioscholar.model.OutputType;
import edu.cit.audioscholar.model.ProcessingStatus;
import edu.cit.audioscholar.model.QualityReport;
import edu.cit.audioscholar.model.SourceFile;
import edu.cit.audioscholar.model.Summary;

@Service
public class MultiSourceJobService {
	private static final String COLLECTION_NAME = "multiSourceJobs";
	private static final Set<String> ALLOWED_TYPES = Set.of("audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav",
			"audio/aac", "audio/x-aac", "audio/ogg", "application/ogg", "audio/flac", "audio/x-flac", "audio/aiff",
			"audio/x-aiff", "audio/mp4", "audio/m4a", "video/mp4", "video/webm", "video/quicktime");

	private final FirebaseService firebaseService;
	private final GeminiService geminiService;
	private final QualityReportService qualityReportService;
	private final SummaryService summaryService;
	private final Path tempDir;
	private final String maxFileSizeValue;

	public MultiSourceJobService(FirebaseService firebaseService, GeminiService geminiService,
			QualityReportService qualityReportService, SummaryService summaryService,
			@Value("${app.temp-file-dir}") String tempDirStr,
			@Value("${spring.servlet.multipart.max-file-size}") String maxFileSizeValue) throws IOException {
		this.firebaseService = firebaseService;
		this.geminiService = geminiService;
		this.qualityReportService = qualityReportService;
		this.summaryService = summaryService;
		this.tempDir = Path.of(tempDirStr);
		this.maxFileSizeValue = maxFileSizeValue;
		Files.createDirectories(this.tempDir);
	}

	public MultiSourceJob createAndProcess(String userId, List<MultipartFile> files, String title, String description,
			String outputTypeValue) throws Exception {
		OutputType outputType = OutputType.fromValue(outputTypeValue);
		validateFiles(files);

		MultiSourceJob job = new MultiSourceJob();
		job.setUserId(userId);
		job.setTitle(StringUtils.hasText(title) ? title : "Merged Lecture Summary");
		job.setDescription(description);
		job.setOutputType(outputType.name());
		job.setStatus(ProcessingStatus.PROCESSING_QUEUED.name());
		save(job);

		List<Path> tempFiles = new ArrayList<>();
		try {
			List<SourceFile> sourceFiles = new ArrayList<>();
			for (int i = 0; i < files.size(); i++) {
				MultipartFile file = files.get(i);
				String sourceLabel = "Source " + (char) ('A' + i);
				Path tempFile = saveTemp(file);
				tempFiles.add(tempFile);

				SourceFile sourceFile = new SourceFile();
				sourceFile.setSourceLabel(sourceLabel);
				sourceFile.setFileName(file.getOriginalFilename());
				sourceFile.setContentType(file.getContentType());
				sourceFile.setFileSize(file.getSize());

				QualityReport report = qualityReportService.analyze(job.getJobId() + "-" + sourceLabel, tempFile);
				sourceFile.setQualityReport(report);

				String transcript = geminiService.callGeminiTranscriptionAPIWithFallback(tempFile,
						file.getOriginalFilename());
				sourceFile.setTranscriptText(transcript);
				sourceFiles.add(sourceFile);
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

	public Map<String, Object> getJobMap(String jobId) {
		return firebaseService.getData(COLLECTION_NAME, jobId);
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
		summary.setOutputType(job.getOutputType());
		summary.setFormattedSummaryText(root.path("summaryText").asText(summaryJson));
		List<String> keyPoints = new ArrayList<>();
		if (root.path("keyPoints").isArray()) {
			root.path("keyPoints").forEach(node -> keyPoints.add(node.asText()));
		}
		summary.setKeyPoints(deduplicate(keyPoints));
		List<String> topics = new ArrayList<>();
		if (root.path("topics").isArray()) {
			root.path("topics").forEach(node -> topics.add(node.asText()));
		}
		summary.setTopics(topics);
		return summary;
	}

	private List<String> deduplicate(List<String> values) {
		Set<String> seen = new HashSet<>();
		List<String> deduped = new ArrayList<>();
		for (String value : values) {
			String key = value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
			if (!key.isBlank() && seen.add(key)) {
				deduped.add(value);
			}
		}
		return deduped;
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

	private void validateFiles(List<MultipartFile> files) {
		if (files == null || files.size() < 2) {
			throw new IllegalArgumentException("Select at least two audio or video sources.");
		}
		if (files.size() > 5) {
			throw new IllegalArgumentException("Select no more than five sources.");
		}
		long maxBytes = DataSize.parse(maxFileSizeValue).toBytes();
		for (MultipartFile file : files) {
			if (file == null || file.isEmpty()) {
				throw new IllegalArgumentException("One of the selected source files is empty.");
			}
			String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
			if (!ALLOWED_TYPES.contains(type)) {
				throw new IllegalArgumentException("Unsupported source file type: " + type);
			}
			if (file.getSize() > maxBytes) {
				throw new IllegalArgumentException("A source file exceeds the maximum allowed size.");
			}
		}
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
		firebaseService.saveData(COLLECTION_NAME, job.getJobId(), job.toMap());
	}
}
