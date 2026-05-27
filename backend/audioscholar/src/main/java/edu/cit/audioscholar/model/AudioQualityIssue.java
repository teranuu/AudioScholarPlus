package edu.cit.audioscholar.model;

import java.util.HashMap;
import java.util.Map;

public class AudioQualityIssue {

	public enum Severity {
		LOW, MEDIUM, HIGH
	}

	public enum IssueType {
		BACKGROUND_NOISE, LOW_VOLUME, UNCLEAR_SPEECH, EXCESSIVE_FILLER_WORDS, INCOHERENT_SEGMENT, ABRUPT_CUT, OVERLAPPING_SPEECH
	}

	private String issueId;
	private Double startTime;
	private Double endTime;
	private Severity severity;
	private IssueType issueType;
	private String description;
	private String recommendedAction;

	public AudioQualityIssue() {
	}

	public AudioQualityIssue(String issueId, Double startTime, Double endTime, Severity severity, IssueType issueType,
			String description, String recommendedAction) {
		this.issueId = issueId;
		this.startTime = startTime;
		this.endTime = endTime;
		this.severity = severity;
		this.issueType = issueType;
		this.description = description;
		this.recommendedAction = recommendedAction;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("issueId", issueId);
		map.put("startTime", startTime);
		map.put("endTime", endTime);
		map.put("severity", severity != null ? severity.name() : null);
		map.put("issueType", issueType != null ? issueType.name() : null);
		map.put("description", description);
		map.put("recommendedAction", recommendedAction);
		return map;
	}

	public static AudioQualityIssue fromMap(Map<String, Object> map) {
		if (map == null)
			return null;
		AudioQualityIssue issue = new AudioQualityIssue();
		issue.issueId = (String) map.get("issueId");
		Object startObj = map.get("startTime");
		if (startObj instanceof Number)
			issue.startTime = ((Number) startObj).doubleValue();
		Object endObj = map.get("endTime");
		if (endObj instanceof Number)
			issue.endTime = ((Number) endObj).doubleValue();
		String severityStr = (String) map.get("severity");
		if (severityStr != null) {
			try {
				issue.severity = Severity.valueOf(severityStr);
			} catch (IllegalArgumentException ignored) {
			}
		}
		String issueTypeStr = (String) map.get("issueType");
		if (issueTypeStr != null) {
			try {
				issue.issueType = IssueType.valueOf(issueTypeStr);
			} catch (IllegalArgumentException ignored) {
			}
		}
		issue.description = (String) map.get("description");
		issue.recommendedAction = (String) map.get("recommendedAction");
		return issue;
	}

	// Getters & Setters
	public String getIssueId() {
		return issueId;
	}
	public void setIssueId(String issueId) {
		this.issueId = issueId;
	}

	public Double getStartTime() {
		return startTime;
	}
	public void setStartTime(Double startTime) {
		this.startTime = startTime;
	}

	public Double getEndTime() {
		return endTime;
	}
	public void setEndTime(Double endTime) {
		this.endTime = endTime;
	}

	public Severity getSeverity() {
		return severity;
	}
	public void setSeverity(Severity severity) {
		this.severity = severity;
	}

	public IssueType getIssueType() {
		return issueType;
	}
	public void setIssueType(IssueType issueType) {
		this.issueType = issueType;
	}

	public String getDescription() {
		return description;
	}
	public void setDescription(String description) {
		this.description = description;
	}

	public String getRecommendedAction() {
		return recommendedAction;
	}
	public void setRecommendedAction(String recommendedAction) {
		this.recommendedAction = recommendedAction;
	}
}
