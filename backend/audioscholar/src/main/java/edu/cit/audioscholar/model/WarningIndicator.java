package edu.cit.audioscholar.model;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.cloud.Timestamp;

public class WarningIndicator {
	private String warningId = UUID.randomUUID().toString();
	private String keyPointId;
	private String issueId;
	private String issueType;
	private String severity;
	private String recommendedAction;
	private Date createdAt = new Date();

	public String getWarningId() {
		return warningId;
	}

	public void setWarningId(String warningId) {
		this.warningId = warningId;
	}

	public String getKeyPointId() {
		return keyPointId;
	}

	public void setKeyPointId(String keyPointId) {
		this.keyPointId = keyPointId;
	}

	public String getIssueId() {
		return issueId;
	}

	public void setIssueId(String issueId) {
		this.issueId = issueId;
	}

	public String getIssueType() {
		return issueType;
	}

	public void setIssueType(String issueType) {
		this.issueType = issueType;
	}

	public String getSeverity() {
		return severity;
	}

	public void setSeverity(String severity) {
		this.severity = severity;
	}

	public String getRecommendedAction() {
		return recommendedAction;
	}

	public void setRecommendedAction(String recommendedAction) {
		this.recommendedAction = recommendedAction;
	}

	public Date getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Date createdAt) {
		this.createdAt = createdAt;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("warningId", warningId);
		map.put("keyPointId", keyPointId);
		map.put("issueId", issueId);
		map.put("issueType", issueType);
		map.put("severity", severity);
		map.put("recommendedAction", recommendedAction);
		map.put("createdAt", createdAt);
		return map;
	}

	public static WarningIndicator fromMap(Map<String, Object> map) {
		if (map == null) {
			return null;
		}
		WarningIndicator warning = new WarningIndicator();
		warning.warningId = (String) map.get("warningId");
		warning.keyPointId = (String) map.get("keyPointId");
		warning.issueId = (String) map.get("issueId");
		warning.issueType = (String) map.get("issueType");
		warning.severity = (String) map.get("severity");
		warning.recommendedAction = (String) map.get("recommendedAction");
		Object created = map.get("createdAt");
		if (created instanceof Timestamp timestamp) {
			warning.createdAt = timestamp.toDate();
		} else if (created instanceof Date date) {
			warning.createdAt = date;
		}
		return warning;
	}
}
