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
	private String failureStage;
	private String failureCode;
	private String failureDetail;
	private Date failedAt;
	private Integer sourceCount;
	private List<SourceFile> sourceFiles = new ArrayList<>();
	private Summary mergedSummary;
	private Date createdAt = new Date();
	private Date updatedAt = new Date();
	private Date acceptedAt;
	private Date queuedAt;
	private Date processingStartedAt;
	private Date finalTranscriptionCompletedAt;
	private Date mergeStartedAt;
	private Date mergedSummaryAvailableAt;
	private Long mergeLatencyMs;
	private boolean measurementIncomplete;
	private String clientSource = "UNKNOWN";
	private int processingAttempts;
	private Integer finalAttemptCount;

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
	public String getFailureStage() {
		return failureStage;
	}
	public void setFailureStage(String value) {
		failureStage = value;
	}
	public String getFailureCode() {
		return failureCode;
	}
	public void setFailureCode(String value) {
		failureCode = value;
	}
	public String getFailureDetail() {
		return failureDetail;
	}
	public void setFailureDetail(String value) {
		failureDetail = value;
	}
	public Date getFailedAt() {
		return failedAt;
	}
	public void setFailedAt(Date value) {
		failedAt = value;
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
	public Date getAcceptedAt() {
		return acceptedAt;
	}
	public void setAcceptedAt(Date value) {
		acceptedAt = value;
	}
	public Date getQueuedAt() {
		return queuedAt;
	}
	public void setQueuedAt(Date value) {
		queuedAt = value;
	}
	public Date getProcessingStartedAt() {
		return processingStartedAt;
	}
	public void setProcessingStartedAt(Date value) {
		processingStartedAt = value;
	}
	public Date getFinalTranscriptionCompletedAt() {
		return finalTranscriptionCompletedAt;
	}
	public void setFinalTranscriptionCompletedAt(Date value) {
		finalTranscriptionCompletedAt = value;
	}
	public Date getMergeStartedAt() {
		return mergeStartedAt;
	}
	public void setMergeStartedAt(Date value) {
		mergeStartedAt = value;
	}
	public Date getMergedSummaryAvailableAt() {
		return mergedSummaryAvailableAt;
	}
	public void setMergedSummaryAvailableAt(Date value) {
		mergedSummaryAvailableAt = value;
	}
	public Long getMergeLatencyMs() {
		return mergeLatencyMs;
	}
	public void setMergeLatencyMs(Long value) {
		mergeLatencyMs = value;
	}
	public boolean isMeasurementIncomplete() {
		return measurementIncomplete;
	}
	public void setMeasurementIncomplete(boolean value) {
		measurementIncomplete = value;
	}
	public String getClientSource() {
		return clientSource;
	}
	public void setClientSource(String value) {
		clientSource = value;
	}
	public int getProcessingAttempts() {
		return processingAttempts;
	}
	public void setProcessingAttempts(int value) {
		processingAttempts = value;
	}
	public Integer getFinalAttemptCount() {
		return finalAttemptCount;
	}
	public void setFinalAttemptCount(Integer value) {
		finalAttemptCount = value;
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
		map.put("failureStage", failureStage);
		map.put("failureCode", failureCode);
		map.put("failureDetail", failureDetail);
		map.put("failedAt", failedAt);
		map.put("sourceCount", sourceCount != null ? sourceCount : sourceFiles.size());
		map.put("sourceFiles", sourceFiles.stream().map(SourceFile::toMap).toList());
		map.put("mergedSummary", mergedSummary != null ? mergedSummary.toMap() : null);
		map.put("createdAt", createdAt);
		map.put("updatedAt", updatedAt);
		map.put("acceptedAt", acceptedAt);
		map.put("queuedAt", queuedAt);
		map.put("processingStartedAt", processingStartedAt);
		map.put("finalTranscriptionCompletedAt", finalTranscriptionCompletedAt);
		map.put("mergeStartedAt", mergeStartedAt);
		map.put("mergedSummaryAvailableAt", mergedSummaryAvailableAt);
		map.put("mergeLatencyMs", mergeLatencyMs);
		map.put("measurementIncomplete", measurementIncomplete);
		map.put("clientSource", clientSource);
		map.put("processingAttempts", processingAttempts);
		map.put("finalAttemptCount", finalAttemptCount);
		return map;
	}
}
