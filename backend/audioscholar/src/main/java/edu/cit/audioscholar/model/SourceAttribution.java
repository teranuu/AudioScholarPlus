package edu.cit.audioscholar.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SourceAttribution {
	private String attributionId = UUID.randomUUID().toString();
	private String mergedSummaryId;
	private String keyPointId;
	private String sourceLabel;
	private String attributionType;

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

	public String getKeyPointId() {
		return keyPointId;
	}

	public void setKeyPointId(String keyPointId) {
		this.keyPointId = keyPointId;
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

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("attributionId", attributionId);
		map.put("mergedSummaryId", mergedSummaryId);
		map.put("keyPointId", keyPointId);
		map.put("sourceLabel", sourceLabel);
		map.put("attributionType", attributionType);
		return map;
	}
}
