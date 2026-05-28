package edu.cit.audioscholar.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MergedSummary {
	private String mergedSummaryId = UUID.randomUUID().toString();
	private String jobId;
	private String userId;
	private String content;
	private List<SourceAttribution> sourceAttributions = new ArrayList<>();
	private String status;
	private Date createdAt = new Date();
	private Date updatedAt = new Date();

	public String getMergedSummaryId() {
		return mergedSummaryId;
	}

	public void setMergedSummaryId(String mergedSummaryId) {
		this.mergedSummaryId = mergedSummaryId;
	}

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

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public List<SourceAttribution> getSourceAttributions() {
		return sourceAttributions;
	}

	public void setSourceAttributions(List<SourceAttribution> sourceAttributions) {
		this.sourceAttributions = sourceAttributions != null ? new ArrayList<>(sourceAttributions) : new ArrayList<>();
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

	public Date getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Date updatedAt) {
		this.updatedAt = updatedAt;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("mergedSummaryId", mergedSummaryId);
		map.put("jobId", jobId);
		map.put("userId", userId);
		map.put("content", content);
		map.put("sourceAttributions", sourceAttributions.stream().map(SourceAttribution::toMap).toList());
		map.put("status", status);
		map.put("createdAt", createdAt);
		map.put("updatedAt", updatedAt);
		return map;
	}
}
