package edu.cit.audioscholar.model;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SourceTranscript {
	private String transcriptId = UUID.randomUUID().toString();
	private String sourceFileId;
	private String jobId;
	private String sourceLabel;
	private String transcriptText;
	private String transcriptContent;
	private String status;
	private Date createdAt = new Date();

	public SourceTranscript() {
	}

	public SourceTranscript(String sourceFileId, String sourceLabel, String transcriptText) {
		this.sourceFileId = sourceFileId;
		this.sourceLabel = sourceLabel;
		this.transcriptText = transcriptText;
		this.transcriptContent = transcriptText;
		this.status = "COMPLETE";
	}

	public String getTranscriptId() {
		return transcriptId;
	}

	public void setTranscriptId(String transcriptId) {
		this.transcriptId = transcriptId;
	}

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

	public String getTranscriptText() {
		return transcriptText;
	}

	public void setTranscriptText(String transcriptText) {
		this.transcriptText = transcriptText;
		this.transcriptContent = transcriptText;
	}

	public String getTranscriptContent() {
		return transcriptContent;
	}

	public void setTranscriptContent(String transcriptContent) {
		this.transcriptContent = transcriptContent;
		this.transcriptText = transcriptContent;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public Date getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Date createdAt) {
		this.createdAt = createdAt;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("transcriptId", transcriptId);
		map.put("sourceFileId", sourceFileId);
		map.put("jobId", jobId);
		map.put("sourceLabel", sourceLabel);
		map.put("transcriptContent", transcriptContent != null ? transcriptContent : transcriptText);
		map.put("transcriptText", transcriptText);
		map.put("status", status);
		map.put("createdAt", createdAt);
		return map;
	}
}
