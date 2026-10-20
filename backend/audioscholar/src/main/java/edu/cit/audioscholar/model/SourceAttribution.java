package edu.cit.audioscholar.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SourceAttribution {
	private String attributionId = UUID.randomUUID().toString();
	private String mergedSummaryId;
	private String jobId;
	private String keyPointId;
	private String sourceFileId;
	private String sourceLabel;
	private String attributionType;
	private String attributionStatus;
	private String algorithmVersion;
	private String applicationVersion;

	public String getAttributionId() {
		return attributionId;
	}

	public void setAttributionId(String attributionId) {
		this.attributionId = attributionId;
	}

	public String getMergedSummaryId() {
		return mergedSummaryId;
	}

	public void setMergedSummaryId(String mergedSummaryId) {
		this.mergedSummaryId = mergedSummaryId;
	}
	public String getJobId() {
		return jobId;
	}
	public void setJobId(String value) {
		jobId = value;
	}

	public String getKeyPointId() {
		return keyPointId;
	}

	public void setKeyPointId(String keyPointId) {
		this.keyPointId = keyPointId;
	}
	public String getSourceFileId() {
		return sourceFileId;
	}
	public void setSourceFileId(String value) {
		sourceFileId = value;
	}

	public String getSourceLabel() {
		return sourceLabel;
	}

	public void setSourceLabel(String sourceLabel) {
		this.sourceLabel = sourceLabel;
	}

	public String getAttributionType() {
		return attributionType;
	}

	public void setAttributionType(String attributionType) {
		this.attributionType = attributionType;
	}
	public String getAttributionStatus() {
		return attributionStatus;
	}
	public void setAttributionStatus(String value) {
		attributionStatus = value;
	}
	public String getAlgorithmVersion() {
		return algorithmVersion;
	}
	public void setAlgorithmVersion(String value) {
		algorithmVersion = value;
	}
	public String getApplicationVersion() {
		return applicationVersion;
	}
	public void setApplicationVersion(String value) {
		applicationVersion = value;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("attributionId", attributionId);
		map.put("mergedSummaryId", mergedSummaryId);
		map.put("jobId", jobId);
		map.put("keyPointId", keyPointId);
		map.put("sourceFileId", sourceFileId);
		map.put("sourceLabel", sourceLabel);
		map.put("attributionType", attributionType);
		map.put("attributionStatus", attributionStatus);
		map.put("algorithmVersion", algorithmVersion);
		map.put("applicationVersion", applicationVersion);
		return map;
	}
}
