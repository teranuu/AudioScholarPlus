package edu.cit.audioscholar.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class QualityIssue {
	private String issueId;
	private String startTime;
	private String endTime;
	private String issueType;
	private String severity;
	private String recommendedAction;

	public QualityIssue() {
		this.issueId = UUID.randomUUID().toString();
	}

	public QualityIssue(String startTime, String endTime, String issueType, String severity, String recommendedAction) {
		this();
		this.startTime = startTime;
		this.endTime = endTime;
		this.issueType = issueType;
		this.severity = severity;
		this.recommendedAction = recommendedAction;
	}

	public String getIssueId() {
		return issueId;
	}
	public void setIssueId(String issueId) {
		this.issueId = issueId;
	}
	public String getStartTime() {
		return startTime;
	}
	public void setStartTime(String startTime) {
		this.startTime = startTime;
	}
	public String getEndTime() {
		return endTime;
	}
	public void setEndTime(String endTime) {
		this.endTime = endTime;
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

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("issueId", issueId);
		map.put("startTime", startTime);
		map.put("endTime", endTime);
		map.put("issueType", issueType);
		map.put("severity", severity);
		map.put("recommendedAction", recommendedAction);
		return map;
	}

	public static QualityIssue fromMap(Map<String, Object> map) {
		if (map == null)
			return null;
		QualityIssue issue = new QualityIssue();
		issue.issueId = (String) map.get("issueId");
		issue.startTime = (String) map.get("startTime");
		issue.endTime = (String) map.get("endTime");
		issue.issueType = (String) map.get("issueType");
		issue.severity = (String) map.get("severity");
		issue.recommendedAction = (String) map.get("recommendedAction");
		return issue;
	}
}
