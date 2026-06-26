package edu.cit.audioscholar.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;

public class AudioMetadata {

	@DocumentId
	private String id;
	private String userId;
	private String fileName;
	private long fileSize;
	private String contentType;
	private String title;
	private String description;
	private String nhostFileId;
	private String storageUrl;
	private String audioUrl;
	private Timestamp uploadTimestamp;
	private ProcessingStatus status;
	private String recordingId;
	private String summaryId;
	private String outputType;
	private QualityReport qualityReport;
	private String transcriptText;
	private List<TranscriptSegment> transcriptSegments = new ArrayList<>();
	private String tempFilePath;
	private String failureReason;
	private Integer durationSeconds;
	private Long estimatedGeminiAudioTokens;
	private Long estimatedSummaryInputTokens;
	private String audioFingerprint;
	private String contextFingerprint;
	private Integer favoriteCount;
	private Timestamp lastUpdated;
	private String tempPptxFilePath;

	private String originalPptxFileName;
	private long pptxFileSize;
	private String pptxContentType;
	private String nhostPptxFileId;
	private String pptxNhostUrl;
	private String generatedPdfNhostFileId;
	private String generatedPdfUrl;
	private String googleFilesApiPdfUri;
	private String convertApiPdfUrl;

	private boolean transcriptionComplete = false;
	private boolean pdfConversionComplete = false;
	private boolean audioOnly = false;
	private boolean audioUploadComplete = false;
	private String processingStage;
	private Integer transcriptionChunksTotal;
	private Integer transcriptionChunksCompleted;
	private Timestamp transcriptionStartedAt;
	private Timestamp transcriptionDeadlineAt;
	private Timestamp quotaRetryAt;

	@JsonProperty("gptSummary")
	private String gptSummary;

	@JsonProperty("waitingForPdf")
	private Boolean waitingForPdf;

	private boolean isFavorite = false;

	public AudioMetadata() {
	}

	public AudioMetadata(String id, String userId, String fileName, long fileSize, String contentType, String title,
			String description, Timestamp uploadTimestamp, String recordingId, ProcessingStatus initialStatus,
			String tempFilePath) {
		this.id = id;
		this.userId = userId;
		this.fileName = fileName;
		this.fileSize = fileSize;
		this.contentType = contentType;
		this.title = title != null ? title : "";
		this.description = description != null ? description : "";
		this.uploadTimestamp = uploadTimestamp;
		this.recordingId = recordingId;
		this.status = initialStatus;
		this.tempFilePath = tempFilePath;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public long getFileSize() {
		return fileSize;
	}

	public void setFileSize(long fileSize) {
		this.fileSize = fileSize;
	}

	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getNhostFileId() {
		return nhostFileId;
	}

	public void setNhostFileId(String nhostFileId) {
		this.nhostFileId = nhostFileId;
	}

	public String getStorageUrl() {
		return storageUrl;
	}

	public void setStorageUrl(String storageUrl) {
		this.storageUrl = storageUrl;
	}

	public String getAudioUrl() {
		return audioUrl;
	}

	public void setAudioUrl(String audioUrl) {
		this.audioUrl = audioUrl;
	}

	public Timestamp getUploadTimestamp() {
		return uploadTimestamp;
	}

	public void setUploadTimestamp(Timestamp uploadTimestamp) {
		this.uploadTimestamp = uploadTimestamp;
	}

	public ProcessingStatus getStatus() {
		return status;
	}

	public void setStatus(ProcessingStatus status) {
		this.status = status;
	}

	public String getRecordingId() {
		return recordingId;
	}

	public void setRecordingId(String recordingId) {
		this.recordingId = recordingId;
	}

	public String getSummaryId() {
		return summaryId;
	}

	public void setSummaryId(String summaryId) {
		this.summaryId = summaryId;
	}

	public String getOutputType() {
		return outputType;
	}

	public void setOutputType(String outputType) {
		this.outputType = outputType;
	}

	public QualityReport getQualityReport() {
		return qualityReport;
	}

	public void setQualityReport(QualityReport qualityReport) {
		this.qualityReport = qualityReport;
	}

	public String getTranscriptText() {
		return transcriptText;
	}

	public void setTranscriptText(String transcriptText) {
		this.transcriptText = transcriptText;
	}

	public List<TranscriptSegment> getTranscriptSegments() {
		return transcriptSegments;
	}

	public void setTranscriptSegments(List<TranscriptSegment> transcriptSegments) {
		this.transcriptSegments = transcriptSegments != null ? new ArrayList<>(transcriptSegments) : new ArrayList<>();
	}

	public String getTempFilePath() {
		return tempFilePath;
	}

	public void setTempFilePath(String tempFilePath) {
		this.tempFilePath = tempFilePath;
	}

	public String getFailureReason() {
		return failureReason;
	}

	public void setFailureReason(String failureReason) {
		this.failureReason = failureReason;
	}

	public Integer getDurationSeconds() {
		return durationSeconds;
	}

	public void setDurationSeconds(Integer durationSeconds) {
		this.durationSeconds = durationSeconds;
	}

	public Long getEstimatedGeminiAudioTokens() {
		return estimatedGeminiAudioTokens;
	}

	public void setEstimatedGeminiAudioTokens(Long estimatedGeminiAudioTokens) {
		this.estimatedGeminiAudioTokens = estimatedGeminiAudioTokens;
	}

	public Long getEstimatedSummaryInputTokens() {
		return estimatedSummaryInputTokens;
	}

	public void setEstimatedSummaryInputTokens(Long estimatedSummaryInputTokens) {
		this.estimatedSummaryInputTokens = estimatedSummaryInputTokens;
	}

	public String getAudioFingerprint() {
		return audioFingerprint;
	}

	public void setAudioFingerprint(String audioFingerprint) {
		this.audioFingerprint = audioFingerprint;
	}

	public String getContextFingerprint() {
		return contextFingerprint;
	}

	public void setContextFingerprint(String contextFingerprint) {
		this.contextFingerprint = contextFingerprint;
	}

	public Integer getFavoriteCount() {
		return favoriteCount;
	}

	public void setFavoriteCount(Integer favoriteCount) {
		this.favoriteCount = favoriteCount;
	}

	public Timestamp getLastUpdated() {
		return lastUpdated;
	}

	public void setLastUpdated(Timestamp lastUpdated) {
		this.lastUpdated = lastUpdated;
	}

	public String getTempPptxFilePath() {
		return tempPptxFilePath;
	}

	public void setTempPptxFilePath(String tempPptxFilePath) {
		this.tempPptxFilePath = tempPptxFilePath;
	}

	public String getOriginalPptxFileName() {
		return originalPptxFileName;
	}

	public void setOriginalPptxFileName(String originalPptxFileName) {
		this.originalPptxFileName = originalPptxFileName;
	}

	public long getPptxFileSize() {
		return pptxFileSize;
	}

	public void setPptxFileSize(long pptxFileSize) {
		this.pptxFileSize = pptxFileSize;
	}

	public String getPptxContentType() {
		return pptxContentType;
	}

	public void setPptxContentType(String pptxContentType) {
		this.pptxContentType = pptxContentType;
	}

	public String getNhostPptxFileId() {
		return nhostPptxFileId;
	}

	public void setNhostPptxFileId(String nhostPptxFileId) {
		this.nhostPptxFileId = nhostPptxFileId;
	}

	public String getPptxNhostUrl() {
		return pptxNhostUrl;
	}

	public void setPptxNhostUrl(String pptxNhostUrl) {
		this.pptxNhostUrl = pptxNhostUrl;
	}

	public String getGeneratedPdfNhostFileId() {
		return generatedPdfNhostFileId;
	}

	public void setGeneratedPdfNhostFileId(String generatedPdfNhostFileId) {
		this.generatedPdfNhostFileId = generatedPdfNhostFileId;
	}

	public String getGeneratedPdfUrl() {
		return generatedPdfUrl;
	}

	public void setGeneratedPdfUrl(String generatedPdfUrl) {
		this.generatedPdfUrl = generatedPdfUrl;
	}

	public String getGoogleFilesApiPdfUri() {
		return googleFilesApiPdfUri;
	}

	public void setGoogleFilesApiPdfUri(String googleFilesApiPdfUri) {
		this.googleFilesApiPdfUri = googleFilesApiPdfUri;
	}

	public String getConvertApiPdfUrl() {
		return convertApiPdfUrl;
	}

	public void setConvertApiPdfUrl(String convertApiPdfUrl) {
		this.convertApiPdfUrl = convertApiPdfUrl;
	}

	public boolean isTranscriptionComplete() {
		return transcriptionComplete;
	}

	public void setTranscriptionComplete(boolean transcriptionComplete) {
		this.transcriptionComplete = transcriptionComplete;
	}

	public boolean isPdfConversionComplete() {
		return pdfConversionComplete;
	}

	public void setPdfConversionComplete(boolean pdfConversionComplete) {
		this.pdfConversionComplete = pdfConversionComplete;
	}

	public boolean isAudioOnly() {
		return audioOnly;
	}

	public void setAudioOnly(boolean audioOnly) {
		this.audioOnly = audioOnly;
	}

	public boolean isAudioUploadComplete() {
		return audioUploadComplete;
	}

	public void setAudioUploadComplete(boolean audioUploadComplete) {
		this.audioUploadComplete = audioUploadComplete;
	}

	public String getProcessingStage() {
		return processingStage;
	}

	public void setProcessingStage(String processingStage) {
		this.processingStage = processingStage;
	}

	public Integer getTranscriptionChunksTotal() {
		return transcriptionChunksTotal;
	}

	public void setTranscriptionChunksTotal(Integer transcriptionChunksTotal) {
		this.transcriptionChunksTotal = transcriptionChunksTotal;
	}

	public Integer getTranscriptionChunksCompleted() {
		return transcriptionChunksCompleted;
	}

	public void setTranscriptionChunksCompleted(Integer transcriptionChunksCompleted) {
		this.transcriptionChunksCompleted = transcriptionChunksCompleted;
	}

	public Timestamp getTranscriptionStartedAt() {
		return transcriptionStartedAt;
	}

	public void setTranscriptionStartedAt(Timestamp transcriptionStartedAt) {
		this.transcriptionStartedAt = transcriptionStartedAt;
	}

	public Timestamp getTranscriptionDeadlineAt() {
		return transcriptionDeadlineAt;
	}

	public void setTranscriptionDeadlineAt(Timestamp transcriptionDeadlineAt) {
		this.transcriptionDeadlineAt = transcriptionDeadlineAt;
	}

	public Timestamp getQuotaRetryAt() {
		return quotaRetryAt;
	}

	public void setQuotaRetryAt(Timestamp quotaRetryAt) {
		this.quotaRetryAt = quotaRetryAt;
	}

	public String getGptSummary() {
		return gptSummary;
	}

	public void setGptSummary(String gptSummary) {
		this.gptSummary = gptSummary;
	}

	public Boolean isWaitingForPdf() {
		return Boolean.TRUE.equals(waitingForPdf);
	}

	public void setWaitingForPdf(Boolean waitingForPdf) {
		this.waitingForPdf = waitingForPdf;
	}

	public boolean isFavorite() {
		return isFavorite;
	}

	public void setFavorite(boolean favorite) {
		isFavorite = favorite;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		if (id != null)
			map.put("id", id);
		if (userId != null)
			map.put("userId", userId);
		if (fileName != null)
			map.put("fileName", fileName);
		map.put("fileSize", fileSize);
		if (contentType != null)
			map.put("contentType", contentType);
		if (title != null)
			map.put("title", title);
		if (description != null)
			map.put("description", description);
		if (nhostFileId != null)
			map.put("nhostFileId", nhostFileId);
		if (storageUrl != null)
			map.put("storageUrl", storageUrl);
		if (audioUrl != null)
			map.put("audioUrl", audioUrl);
		if (uploadTimestamp != null)
			map.put("uploadTimestamp", uploadTimestamp);
		if (status != null)
			map.put("status", status.name());
		if (recordingId != null)
			map.put("recordingId", recordingId);
		if (summaryId != null)
			map.put("summaryId", summaryId);
		if (outputType != null)
			map.put("outputType", outputType);
		if (qualityReport != null)
			map.put("qualityReport", qualityReport.toMap());
		if (transcriptText != null)
			map.put("transcriptText", transcriptText);
		if (transcriptSegments != null && !transcriptSegments.isEmpty())
			map.put("transcriptSegments", transcriptSegments.stream().map(TranscriptSegment::toMap).toList());
		if (tempFilePath != null)
			map.put("tempFilePath", tempFilePath);
		if (failureReason != null)
			map.put("failureReason", failureReason);
		if (durationSeconds != null)
			map.put("durationSeconds", durationSeconds);
		if (estimatedGeminiAudioTokens != null)
			map.put("estimatedGeminiAudioTokens", estimatedGeminiAudioTokens);
		if (estimatedSummaryInputTokens != null)
			map.put("estimatedSummaryInputTokens", estimatedSummaryInputTokens);
		if (audioFingerprint != null)
			map.put("audioFingerprint", audioFingerprint);
		if (contextFingerprint != null)
			map.put("contextFingerprint", contextFingerprint);
		if (favoriteCount != null)
			map.put("favoriteCount", favoriteCount);
		if (lastUpdated != null)
			map.put("lastUpdated", lastUpdated);
		if (tempPptxFilePath != null)
			map.put("tempPptxFilePath", tempPptxFilePath);

		if (originalPptxFileName != null)
			map.put("originalPptxFileName", originalPptxFileName);
		if (pptxFileSize > 0)
			map.put("pptxFileSize", pptxFileSize);
		if (pptxContentType != null)
			map.put("pptxContentType", pptxContentType);
		if (nhostPptxFileId != null)
			map.put("nhostPptxFileId", nhostPptxFileId);
		if (pptxNhostUrl != null)
			map.put("pptxNhostUrl", pptxNhostUrl);
		if (generatedPdfNhostFileId != null)
			map.put("generatedPdfNhostFileId", generatedPdfNhostFileId);
		if (generatedPdfUrl != null)
			map.put("generatedPdfUrl", generatedPdfUrl);
		if (googleFilesApiPdfUri != null)
			map.put("googleFilesApiPdfUri", googleFilesApiPdfUri);
		if (convertApiPdfUrl != null)
			map.put("convertApiPdfUrl", convertApiPdfUrl);
		map.put("transcriptionComplete", transcriptionComplete);
		map.put("pdfConversionComplete", pdfConversionComplete);
		map.put("audioOnly", audioOnly);
		map.put("audioUploadComplete", audioUploadComplete);
		if (processingStage != null)
			map.put("processingStage", processingStage);
		if (transcriptionChunksTotal != null)
			map.put("transcriptionChunksTotal", transcriptionChunksTotal);
		if (transcriptionChunksCompleted != null)
			map.put("transcriptionChunksCompleted", transcriptionChunksCompleted);
		if (transcriptionStartedAt != null)
			map.put("transcriptionStartedAt", transcriptionStartedAt);
		if (transcriptionDeadlineAt != null)
			map.put("transcriptionDeadlineAt", transcriptionDeadlineAt);
		if (quotaRetryAt != null)
			map.put("quotaRetryAt", quotaRetryAt);

		if (gptSummary != null)
			map.put("gptSummary", gptSummary);
		if (waitingForPdf != null)
			map.put("waitingForPdf", waitingForPdf);

		return map;
	}

	public static AudioMetadata fromMap(Map<String, Object> map) {
		if (map == null)
			return null;
		AudioMetadata meta = new AudioMetadata();
		meta.setId((String) map.get("id"));
		meta.setUserId((String) map.get("userId"));
		meta.setFileName((String) map.get("fileName"));
		Object size = map.get("fileSize");
		if (size instanceof Number)
			meta.setFileSize(((Number) size).longValue());
		meta.setContentType((String) map.get("contentType"));
		meta.setTitle((String) map.get("title"));
		meta.setDescription((String) map.get("description"));
		meta.setNhostFileId((String) map.get("nhostFileId"));
		meta.setStorageUrl((String) map.get("storageUrl"));
		meta.setAudioUrl((String) map.get("audioUrl"));
		meta.setUploadTimestamp((Timestamp) map.get("uploadTimestamp"));
		String statusStr = (String) map.get("status");
		if (statusStr != null) {
			try {
				meta.setStatus(ProcessingStatus.valueOf(statusStr));
			} catch (IllegalArgumentException e) {
				meta.setStatus(ProcessingStatus.FAILED);
			}
		}
		meta.setRecordingId((String) map.get("recordingId"));
		meta.setSummaryId((String) map.get("summaryId"));
		meta.setOutputType((String) map.get("outputType"));
		Object qualityReportObj = map.get("qualityReport");
		if (qualityReportObj instanceof Map) {
			@SuppressWarnings("unchecked")
			Map<String, Object> qualityReportMap = (Map<String, Object>) qualityReportObj;
			meta.setQualityReport(QualityReport.fromMap(qualityReportMap));
		}
		meta.setTranscriptText((String) map.get("transcriptText"));
		Object transcriptSegmentsObj = map.get("transcriptSegments");
		if (transcriptSegmentsObj instanceof List<?> rawSegments) {
			List<TranscriptSegment> parsedSegments = new ArrayList<>();
			for (Object rawSegment : rawSegments) {
				if (rawSegment instanceof Map<?, ?> rawMap) {
					@SuppressWarnings("unchecked")
					Map<String, Object> typedMap = (Map<String, Object>) rawMap;
					TranscriptSegment segment = TranscriptSegment.fromMap(typedMap);
					if (segment != null) {
						parsedSegments.add(segment);
					}
				}
			}
			meta.setTranscriptSegments(parsedSegments);
		}
		meta.setTempFilePath((String) map.get("tempFilePath"));
		meta.setFailureReason((String) map.get("failureReason"));
		Object durationObj = map.get("durationSeconds");
		if (durationObj instanceof Number) {
			meta.setDurationSeconds(((Number) durationObj).intValue());
		}
		Object estimatedAudioTokensObj = map.get("estimatedGeminiAudioTokens");
		if (estimatedAudioTokensObj instanceof Number) {
			meta.setEstimatedGeminiAudioTokens(((Number) estimatedAudioTokensObj).longValue());
		}
		Object estimatedSummaryTokensObj = map.get("estimatedSummaryInputTokens");
		if (estimatedSummaryTokensObj instanceof Number) {
			meta.setEstimatedSummaryInputTokens(((Number) estimatedSummaryTokensObj).longValue());
		}
		meta.setAudioFingerprint((String) map.get("audioFingerprint"));
		meta.setContextFingerprint((String) map.get("contextFingerprint"));
		Object favoriteCountObj = map.get("favoriteCount");
		if (favoriteCountObj instanceof Number) {
			meta.setFavoriteCount(((Number) favoriteCountObj).intValue());
		} else {
			meta.setFavoriteCount(0);
		}
		meta.setLastUpdated((Timestamp) map.get("lastUpdated"));
		meta.setTempPptxFilePath((String) map.get("tempPptxFilePath"));

		meta.setOriginalPptxFileName((String) map.get("originalPptxFileName"));
		Object pptxSize = map.get("pptxFileSize");
		if (pptxSize instanceof Number)
			meta.setPptxFileSize(((Number) pptxSize).longValue());
		meta.setPptxContentType((String) map.get("pptxContentType"));
		meta.setNhostPptxFileId((String) map.get("nhostPptxFileId"));
		meta.setPptxNhostUrl((String) map.get("pptxNhostUrl"));
		meta.setGeneratedPdfNhostFileId((String) map.get("generatedPdfNhostFileId"));
		meta.setGeneratedPdfUrl((String) map.get("generatedPdfUrl"));
		meta.setGoogleFilesApiPdfUri((String) map.get("googleFilesApiPdfUri"));
		meta.setConvertApiPdfUrl((String) map.get("convertApiPdfUrl"));

		Object transcriptionCompleteFlag = map.get("transcriptionComplete");
		if (transcriptionCompleteFlag instanceof Boolean)
			meta.setTranscriptionComplete((Boolean) transcriptionCompleteFlag);
		else
			meta.setTranscriptionComplete(false);

		Object pdfConversionCompleteFlag = map.get("pdfConversionComplete");
		if (pdfConversionCompleteFlag instanceof Boolean)
			meta.setPdfConversionComplete((Boolean) pdfConversionCompleteFlag);
		else
			meta.setPdfConversionComplete(false);

		Object audioOnlyFlag = map.get("audioOnly");
		if (audioOnlyFlag instanceof Boolean)
			meta.setAudioOnly((Boolean) audioOnlyFlag);
		else
			meta.setAudioOnly(false);

		Object audioUploadCompleteFlag = map.get("audioUploadComplete");
		if (audioUploadCompleteFlag instanceof Boolean)
			meta.setAudioUploadComplete((Boolean) audioUploadCompleteFlag);
		else
			meta.setAudioUploadComplete(false);

		meta.setProcessingStage((String) map.get("processingStage"));
		Object chunksTotal = map.get("transcriptionChunksTotal");
		if (chunksTotal instanceof Number)
			meta.setTranscriptionChunksTotal(((Number) chunksTotal).intValue());
		Object chunksCompleted = map.get("transcriptionChunksCompleted");
		if (chunksCompleted instanceof Number)
			meta.setTranscriptionChunksCompleted(((Number) chunksCompleted).intValue());
		meta.setTranscriptionStartedAt((Timestamp) map.get("transcriptionStartedAt"));
		meta.setTranscriptionDeadlineAt((Timestamp) map.get("transcriptionDeadlineAt"));
		meta.setQuotaRetryAt((Timestamp) map.get("quotaRetryAt"));

		meta.setGptSummary((String) map.get("gptSummary"));
		meta.setWaitingForPdf((Boolean) map.get("waitingForPdf"));

		return meta;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		AudioMetadata that = (AudioMetadata) o;
		return Objects.equals(id, that.id) && Objects.equals(userId, that.userId)
				&& Objects.equals(fileName, that.fileName) && Objects.equals(contentType, that.contentType)
				&& Objects.equals(title, that.title) && Objects.equals(description, that.description)
				&& Objects.equals(nhostFileId, that.nhostFileId) && Objects.equals(storageUrl, that.storageUrl)
				&& Objects.equals(uploadTimestamp, that.uploadTimestamp) && status == that.status
				&& Objects.equals(tempFilePath, that.tempFilePath)
				&& Objects.equals(tempPptxFilePath, that.tempPptxFilePath)
				&& Objects.equals(failureReason, that.failureReason) && Objects.equals(recordingId, that.recordingId)
				&& Objects.equals(summaryId, that.summaryId) && Objects.equals(transcriptText, that.transcriptText)
				&& Objects.equals(transcriptSegments, that.transcriptSegments)
				&& Objects.equals(durationSeconds, that.durationSeconds)
				&& Objects.equals(estimatedGeminiAudioTokens, that.estimatedGeminiAudioTokens)
				&& Objects.equals(estimatedSummaryInputTokens, that.estimatedSummaryInputTokens)
				&& Objects.equals(audioFingerprint, that.audioFingerprint)
				&& Objects.equals(contextFingerprint, that.contextFingerprint)
				&& Objects.equals(favoriteCount, that.favoriteCount) && Objects.equals(lastUpdated, that.lastUpdated)
				&& Objects.equals(originalPptxFileName, that.originalPptxFileName)
				&& Objects.equals(pptxContentType, that.pptxContentType)
				&& Objects.equals(nhostPptxFileId, that.nhostPptxFileId)
				&& Objects.equals(pptxNhostUrl, that.pptxNhostUrl)
				&& Objects.equals(generatedPdfNhostFileId, that.generatedPdfNhostFileId)
				&& Objects.equals(generatedPdfUrl, that.generatedPdfUrl)
				&& Objects.equals(googleFilesApiPdfUri, that.googleFilesApiPdfUri)
				&& Objects.equals(convertApiPdfUrl, that.convertApiPdfUrl)
				&& transcriptionComplete == that.transcriptionComplete
				&& pdfConversionComplete == that.pdfConversionComplete && audioOnly == that.audioOnly
				&& audioUploadComplete == that.audioUploadComplete && Objects.equals(gptSummary, that.gptSummary)
				&& Objects.equals(waitingForPdf, that.waitingForPdf)
				&& Objects.equals(processingStage, that.processingStage)
				&& Objects.equals(transcriptionChunksTotal, that.transcriptionChunksTotal)
				&& Objects.equals(transcriptionChunksCompleted, that.transcriptionChunksCompleted)
				&& Objects.equals(transcriptionStartedAt, that.transcriptionStartedAt)
				&& Objects.equals(transcriptionDeadlineAt, that.transcriptionDeadlineAt)
				&& Objects.equals(quotaRetryAt, that.quotaRetryAt);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, userId, fileName, fileSize, contentType, title, description, nhostFileId, storageUrl,
				uploadTimestamp, status, failureReason, recordingId, summaryId, transcriptText, tempFilePath,
				transcriptSegments, tempPptxFilePath, durationSeconds, estimatedGeminiAudioTokens,
				estimatedSummaryInputTokens, audioFingerprint, contextFingerprint, favoriteCount, lastUpdated,
				originalPptxFileName, pptxFileSize, pptxContentType, nhostPptxFileId, pptxNhostUrl,
				generatedPdfNhostFileId, generatedPdfUrl, googleFilesApiPdfUri, convertApiPdfUrl, transcriptionComplete,
				pdfConversionComplete, audioOnly, audioUploadComplete, gptSummary, waitingForPdf, processingStage,
				transcriptionChunksTotal, transcriptionChunksCompleted, transcriptionStartedAt, transcriptionDeadlineAt,
				quotaRetryAt);
	}

	@Override
	public String toString() {
		return "AudioMetadata{" + "id='" + id + '\'' + ", userId='" + userId + '\'' + ", fileName='" + fileName + '\''
				+ ", status=" + status + ", recordingId='" + recordingId + '\'' + ", tempFilePath='" + tempFilePath
				+ '\'' + ", tempPptxFilePath='" + tempPptxFilePath + '\'' + ", storageUrl='" + storageUrl + '\''
				+ ", transcriptText='"
				+ (transcriptText != null
						? transcriptText.substring(0, Math.min(50, transcriptText.length())) + "..."
						: null)
				+ '\'' + ", durationSeconds=" + durationSeconds + ", favoriteCount=" + favoriteCount + ", lastUpdated="
				+ lastUpdated + ", originalPptxFileName='" + originalPptxFileName + '\'' + ", pptxFileSize="
				+ pptxFileSize + ", pptxContentType='" + pptxContentType + '\'' + ", nhostPptxFileId='"
				+ nhostPptxFileId + '\'' + ", pptxNhostUrl='" + pptxNhostUrl + '\'' + ", generatedPdfNhostFileId='"
				+ generatedPdfNhostFileId + '\'' + ", generatedPdfUrl='" + generatedPdfUrl + '\''
				+ ", googleFilesApiPdfUri='" + googleFilesApiPdfUri + '\'' + ", convertApiPdfUrl='" + convertApiPdfUrl
				+ '\'' + ", transcriptionComplete=" + transcriptionComplete + ", pdfConversionComplete="
				+ pdfConversionComplete + ", audioOnly=" + audioOnly + ", audioUploadComplete=" + audioUploadComplete
				+ ", gptSummary='" + gptSummary + '\'' + ", waitingForPdf=" + waitingForPdf + '}';
	}
}
