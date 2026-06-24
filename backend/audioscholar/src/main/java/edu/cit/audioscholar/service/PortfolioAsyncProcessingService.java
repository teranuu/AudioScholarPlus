package edu.cit.audioscholar.service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;

import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.ProcessingStatus;
import edu.cit.audioscholar.model.Summary;
import edu.cit.audioscholar.service.ai.AiClient;

@Service
public class PortfolioAsyncProcessingService {

	private static final Logger log = LoggerFactory.getLogger(PortfolioAsyncProcessingService.class);

	private final FirebaseService firebaseService;
	private final SupabaseStorageService storageService;
	private final PptxTextExtractorService pptxTextExtractorService;
	private final AiClient aiClient;
	private final SummaryService summaryService;
	private final LearningMaterialRecommenderService recommenderService;
	private final ObjectMapper objectMapper;

	public PortfolioAsyncProcessingService(FirebaseService firebaseService, SupabaseStorageService storageService,
			PptxTextExtractorService pptxTextExtractorService, AiClient aiClient, SummaryService summaryService,
			LearningMaterialRecommenderService recommenderService, ObjectMapper objectMapper) {
		this.firebaseService = firebaseService;
		this.storageService = storageService;
		this.pptxTextExtractorService = pptxTextExtractorService;
		this.aiClient = aiClient;
		this.summaryService = summaryService;
		this.recommenderService = recommenderService;
		this.objectMapper = objectMapper;
	}

	@Async("audioProcessingTaskExecutor")
	public void processUploadAsync(String metadataId, Path audioPath, Path pptxPath, String audioFilename,
			String audioContentType, String pptxFilename, String pptxContentType) {
		try {
			update(metadataId, Map.of("status", ProcessingStatus.UPLOAD_IN_PROGRESS.name(), "lastUpdated", Timestamp.now()));

			String audioObjectPath = storageService.uploadFile(audioPath.toFile(), audioFilename, audioContentType);
			Map<String, Object> uploadUpdates = new HashMap<>();
			uploadUpdates.put("nhostFileId", audioObjectPath);
			uploadUpdates.put("storageUrl", storageService.getPublicUrl(audioObjectPath));
			uploadUpdates.put("audioUrl", storageService.getPublicUrl(audioObjectPath));
			uploadUpdates.put("audioUploadComplete", true);
			uploadUpdates.put("status", ProcessingStatus.UPLOADED.name());
			uploadUpdates.put("lastUpdated", Timestamp.now());

			String pptxContext = "";
			if (pptxPath != null && Files.exists(pptxPath)) {
				String pptxObjectPath = storageService.uploadFile(pptxPath.toFile(), pptxFilename, pptxContentType);
				uploadUpdates.put("nhostPptxFileId", pptxObjectPath);
				uploadUpdates.put("pptxNhostUrl", storageService.getPublicUrl(pptxObjectPath));
				uploadUpdates.put("pdfConversionComplete", true);
				pptxContext = pptxTextExtractorService.extractText(pptxPath);
			} else {
				uploadUpdates.put("audioOnly", true);
				uploadUpdates.put("pdfConversionComplete", true);
			}
			update(metadataId, uploadUpdates);

			update(metadataId, Map.of("status", ProcessingStatus.TRANSCRIBING.name(), "lastUpdated", Timestamp.now()));
			String transcript = aiClient.transcribeAudio(audioPath, audioFilename);
			update(metadataId, Map.of("transcriptText", transcript, "transcriptionComplete", true, "status",
					ProcessingStatus.TRANSCRIPTION_COMPLETE.name(), "lastUpdated", Timestamp.now()));

			update(metadataId, Map.of("status", ProcessingStatus.SUMMARIZING.name(), "lastUpdated", Timestamp.now()));
			String summaryJson = aiClient.summarizeTranscript(transcript, pptxContext, metadataId);
			Summary summary = saveSummary(metadataId, summaryJson);
			update(metadataId, Map.of("summaryId", summary.getSummaryId(), "gptSummary", summary.getFormattedSummaryText(),
					"status", ProcessingStatus.SUMMARY_COMPLETE.name(), "lastUpdated", Timestamp.now()));

			try {
				recommenderService.generateAndSaveRecommendations(null, metadataId, summary.getSummaryId());
				update(metadataId, Map.of("status", ProcessingStatus.COMPLETE.name(), "lastUpdated", Timestamp.now()));
			} catch (Exception recommendationError) {
				log.warn("[{}] Recommendation generation failed; summary flow still completed.", metadataId,
					recommendationError);
				update(metadataId, Map.of("status", ProcessingStatus.COMPLETED_WITH_WARNINGS.name(), "failureReason",
						"Recommendations failed: " + recommendationError.getMessage(), "lastUpdated", Timestamp.now()));
			}
		} catch (Exception e) {
			log.error("[{}] Async demo processing failed", metadataId, e);
			Map<String, Object> failed = new HashMap<>();
			failed.put("status", ProcessingStatus.FAILED.name());
			failed.put("failureReason", e.getMessage());
			failed.put("lastUpdated", Timestamp.now());
			update(metadataId, failed);
		} finally {
			deleteQuietly(audioPath);
			deleteQuietly(pptxPath);
		}
	}

	private Summary saveSummary(String recordingId, String summaryJson) throws Exception {
		JsonNode root = objectMapper.readTree(summaryJson);
		if (root.has("error")) {
			throw new IllegalStateException(root.path("error").path("message").asText("AI summarization returned an error"));
		}
		Summary summary = new Summary();
		summary.setSummaryId(UUID.randomUUID().toString());
		summary.setRecordingId(recordingId);
		summary.setFormattedSummaryText(firstText(root, "summaryText", "formattedSummaryText", "summary"));
		summary.setKeyPoints(stringList(root.path("keyPoints")));
		summary.setTopics(stringList(root.path("topics")));
		summary.setGlossary(objectMapper.convertValue(root.path("glossary"),
				new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {
				}));
		summary.setCreatedAt(new Date());
		return summaryService.createSummary(summary);
	}

	private String firstText(JsonNode root, String... fields) {
		for (String field : fields) {
			if (StringUtils.hasText(root.path(field).asText(null))) {
				return root.path(field).asText();
			}
		}
		return root.toPrettyString();
	}

	private List<String> stringList(JsonNode node) {
		if (!node.isArray()) {
			return List.of();
		}
		return objectMapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
		});
	}

	private void update(String metadataId, Map<String, Object> updates) {
		firebaseService.updateDataWithMap(firebaseService.getAudioMetadataCollectionName(), metadataId, updates);
	}

	private void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (Exception ignored) {
		}
	}
}
