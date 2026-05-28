package edu.cit.audioscholar.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MultiSourceJob {
	private String jobId = UUID.randomUUID().toString();
	private String userId;
	private String title;
	private String description;
	private String outputType;
	private String status = ProcessingStatus.PROCESSING_QUEUED.name();
	private String failureReason;
	private Integer sourceCount;
	private List<SourceFile> sourceFiles = new ArrayList<>();
	private Summary mergedSummary;
	private Date createdAt = new Date();
	private Date updatedAt = new Date();

	public String getJobId() {
		return jobId;
	}
	public void setJobId(String jobId) {
		this.jobId = jobId;
	}
	public String getUserId() {
		return userId;
	}
	public void setUserId(String userId) {
		this.userId = userId;
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
	public String getOutputType() {
		return outputType;
	}
	public void setOutputType(String outputType) {
		this.outputType = outputType;
	}
	public String getStatus() {
		return status;
	}
	public void setStatus(String status) {
		this.status = status;
	}
	public String getFailureReason() {
		return failureReason;
	}
	public void setFailureReason(String failureReason) {
		this.failureReason = failureReason;
	}

	public Integer getSourceCount() {
		return sourceCount;
	}

	public void setSourceCount(Integer sourceCount) {
		this.sourceCount = sourceCount;
	}
	public List<SourceFile> getSourceFiles() {
		return sourceFiles;
	}
	public void setSourceFiles(List<SourceFile> sourceFiles) {
		this.sourceFiles = sourceFiles != null ? new ArrayList<>(sourceFiles) : new ArrayList<>();
	}
	public Summary getMergedSummary() {
		return mergedSummary;
	}
	public void setMergedSummary(Summary mergedSummary) {
		this.mergedSummary = mergedSummary;
	}
	public Date getCreatedAt() {
		return createdAt;
	}
	public void setCreatedAt(Date createdAt) {
		this.createdAt = createdAt;
	}
	public Date getUpdatedAt() {
		return updatedAt;
	}
	public void setUpdatedAt(Date updatedAt) {
		this.updatedAt = updatedAt;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("jobId", jobId);
		map.put("userId", userId);
		map.put("title", title);
		map.put("description", description);
		map.put("outputType", outputType);
		map.put("status", status);
		map.put("failureReason", failureReason);
		map.put("sourceCount", sourceCount != null ? sourceCount : sourceFiles.size());
		map.put("sourceFiles", sourceFiles.stream().map(SourceFile::toMap).toList());
		map.put("mergedSummary", mergedSummary != null ? mergedSummary.toMap() : null);
		map.put("createdAt", createdAt);
		map.put("updatedAt", updatedAt);
		return map;
	}
}
