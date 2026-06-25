package edu.cit.audioscholar.model;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SourceFile {
	private String sourceFileId = UUID.randomUUID().toString();
	private String jobId;
	private String sourceLabel;
	private String sourceKind;
	private String fileUrl;
	private String fileType;
	private String uploadStatus;
	private String fileName;
	private String contentType;
	private long fileSize;
	private Long durationSeconds;
	private Long estimatedGeminiAudioTokens;
	private String audioFingerprint;
	private String transcriptText;
	private QualityReport qualityReport;
	private Date createdAt = new Date();

	public String getSourceFileId() {
		return sourceFileId;
	}
	public void setSourceFileId(String sourceFileId) {
		this.sourceFileId = sourceFileId;
	}
	public String getJobId() {
		return jobId;
	}
	public void setJobId(String jobId) {
		this.jobId = jobId;
	}
	public String getSourceLabel() {
		return sourceLabel;
	}
	public void setSourceLabel(String sourceLabel) {
		this.sourceLabel = sourceLabel;
	}
	public String getSourceKind() {
		return sourceKind;
	}
	public void setSourceKind(String sourceKind) {
		this.sourceKind = sourceKind;
	}
	public String getFileUrl() {
		return fileUrl;
	}
	public void setFileUrl(String fileUrl) {
		this.fileUrl = fileUrl;
	}
	public String getFileType() {
		return fileType;
	}
	public void setFileType(String fileType) {
		this.fileType = fileType;
	}
	public String getUploadStatus() {
		return uploadStatus;
	}
	public void setUploadStatus(String uploadStatus) {
		this.uploadStatus = uploadStatus;
	}
	public String getFileName() {
		return fileName;
	}
	public void setFileName(String fileName) {
		this.fileName = fileName;
	}
	public String getContentType() {
		return contentType;
	}
	public void setContentType(String contentType) {
		this.contentType = contentType;
	}
	public long getFileSize() {
		return fileSize;
	}
	public void setFileSize(long fileSize) {
		this.fileSize = fileSize;
	}
	public Long getDurationSeconds() {
		return durationSeconds;
	}
	public void setDurationSeconds(Long durationSeconds) {
		this.durationSeconds = durationSeconds;
	}
	public Long getEstimatedGeminiAudioTokens() {
		return estimatedGeminiAudioTokens;
	}
	public void setEstimatedGeminiAudioTokens(Long estimatedGeminiAudioTokens) {
		this.estimatedGeminiAudioTokens = estimatedGeminiAudioTokens;
	}
	public String getAudioFingerprint() {
		return audioFingerprint;
	}
	public void setAudioFingerprint(String audioFingerprint) {
		this.audioFingerprint = audioFingerprint;
	}
	public String getTranscriptText() {
		return transcriptText;
	}
	public void setTranscriptText(String transcriptText) {
		this.transcriptText = transcriptText;
	}
	public QualityReport getQualityReport() {
		return qualityReport;
	}
	public void setQualityReport(QualityReport qualityReport) {
		this.qualityReport = qualityReport;
	}
	public Date getCreatedAt() {
		return createdAt;
	}
	public void setCreatedAt(Date createdAt) {
		this.createdAt = createdAt;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("sourceFileId", sourceFileId);
		map.put("jobId", jobId);
		map.put("sourceLabel", sourceLabel);
		map.put("sourceKind", sourceKind);
		map.put("fileUrl", fileUrl);
		map.put("fileType", fileType);
		map.put("uploadStatus", uploadStatus);
		map.put("fileName", fileName);
		map.put("contentType", contentType);
		map.put("fileSize", fileSize);
		map.put("durationSeconds", durationSeconds);
		map.put("estimatedGeminiAudioTokens", estimatedGeminiAudioTokens);
		map.put("audioFingerprint", audioFingerprint);
		map.put("transcriptText", transcriptText);
		map.put("qualityReport", qualityReport != null ? qualityReport.toMap() : null);
		map.put("createdAt", createdAt);
		return map;
	}
}
