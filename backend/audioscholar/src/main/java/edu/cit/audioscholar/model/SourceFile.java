package edu.cit.audioscholar.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SourceFile {
	private String sourceFileId = UUID.randomUUID().toString();
	private String sourceLabel;
	private String fileName;
	private String contentType;
	private long fileSize;
	private String transcriptText;
	private QualityReport qualityReport;

	public String getSourceFileId() {
		return sourceFileId;
	}
	public void setSourceFileId(String sourceFileId) {
		this.sourceFileId = sourceFileId;
	}
	public String getSourceLabel() {
		return sourceLabel;
	}
	public void setSourceLabel(String sourceLabel) {
		this.sourceLabel = sourceLabel;
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

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("sourceFileId", sourceFileId);
		map.put("sourceLabel", sourceLabel);
		map.put("fileName", fileName);
		map.put("contentType", contentType);
		map.put("fileSize", fileSize);
		map.put("transcriptText", transcriptText);
		map.put("qualityReport", qualityReport != null ? qualityReport.toMap() : null);
		return map;
	}
}
