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
	private Long startMs;
	private Long endMs;
	private String detectorVersion = "pcm-window-v1";
	private Integer analysisWindowSeconds = 15;
	private String thresholdVersion = "quality-thresholds-v1";

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
		this.startMs = parseMillis(startTime);
		this.endMs = parseMillis(endTime);
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
	public Long getStartMs() {
		return startMs;
	}
	public void setStartMs(Long startMs) {
		this.startMs = startMs;
	}
	public Long getEndMs() {
		return endMs;
	}
	public void setEndMs(Long endMs) {
		this.endMs = endMs;
	}
	public String getDetectorVersion() {
		return detectorVersion;
	}
	public void setDetectorVersion(String detectorVersion) {
		this.detectorVersion = detectorVersion;
	}
	public Integer getAnalysisWindowSeconds() {
		return analysisWindowSeconds;
	}
	public void setAnalysisWindowSeconds(Integer value) {
		this.analysisWindowSeconds = value;
	}
	public String getThresholdVersion() {
		return thresholdVersion;
	}
	public void setThresholdVersion(String thresholdVersion) {
		this.thresholdVersion = thresholdVersion;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("issueId", issueId);
		map.put("startTime", startTime);
		map.put("endTime", endTime);
		map.put("issueType", issueType);
		map.put("severity", severity);
		map.put("recommendedAction", recommendedAction);
		map.put("startMs", startMs);
		map.put("endMs", endMs);
		map.put("detectorVersion", detectorVersion);
		map.put("analysisWindowSeconds", analysisWindowSeconds);
		map.put("thresholdVersion", thresholdVersion);
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
		issue.startMs = number(map.get("startMs"), parseMillis(issue.startTime));
		issue.endMs = number(map.get("endMs"), parseMillis(issue.endTime));
		issue.detectorVersion = (String) map.getOrDefault("detectorVersion", "pcm-window-v1");
		issue.analysisWindowSeconds = number(map.get("analysisWindowSeconds"), 15L).intValue();
		issue.thresholdVersion = (String) map.getOrDefault("thresholdVersion", "quality-thresholds-v1");
		return issue;
	}

	private static Long parseMillis(String value) {
		if (value == null || value.isBlank())
			return null;
		String[] parts = value.split(":");
		try {
			long seconds = parts.length == 2
					? Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])
					: Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2]);
			return seconds * 1000;
		} catch (Exception e) {
			return null;
		}
	}
	private static Long number(Object value, Long fallback) {
		return value instanceof Number n ? n.longValue() : fallback;
	}
}
