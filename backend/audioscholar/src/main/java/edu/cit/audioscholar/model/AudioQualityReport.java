package edu.cit.audioscholar.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.cloud.Timestamp;

public class AudioQualityReport {

	public enum OverallQuality {
		GOOD, FAIR, POOR
	}

	private String reportId;
	private String recordingId;
	private String metadataId;
	private List<AudioQualityIssue> issues;
	private OverallQuality overallQuality;
	private String overallSummary;
	private Timestamp createdAt;

	public AudioQualityReport() {
		this.issues = new ArrayList<>();
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("reportId", reportId);
		map.put("recordingId", recordingId);
		map.put("metadataId", metadataId);
		map.put("overallQuality", overallQuality != null ? overallQuality.name() : null);
		map.put("overallSummary", overallSummary);
		map.put("createdAt", createdAt);

		List<Map<String, Object>> issuesList = new ArrayList<>();
		if (issues != null) {
			for (AudioQualityIssue issue : issues) {
				issuesList.add(issue.toMap());
			}
		}
		map.put("issues", issuesList);

		return map;
	}

	@SuppressWarnings("unchecked")
	public static AudioQualityReport fromMap(Map<String, Object> map) {
		if (map == null)
			return null;
		AudioQualityReport report = new AudioQualityReport();
		report.reportId = (String) map.get("reportId");
		report.recordingId = (String) map.get("recordingId");
		report.metadataId = (String) map.get("metadataId");
		report.overallSummary = (String) map.get("overallSummary");

		String qualityStr = (String) map.get("overallQuality");
		if (qualityStr != null) {
			try {
				report.overallQuality = OverallQuality.valueOf(qualityStr);
			} catch (IllegalArgumentException ignored) {
			}
		}

		Object createdAtObj = map.get("createdAt");
		if (createdAtObj instanceof Timestamp) {
			report.createdAt = (Timestamp) createdAtObj;
		}

		Object issuesObj = map.get("issues");
		if (issuesObj instanceof List) {
			List<Map<String, Object>> rawIssues = (List<Map<String, Object>>) issuesObj;
			for (Map<String, Object> rawIssue : rawIssues) {
				AudioQualityIssue issue = AudioQualityIssue.fromMap(rawIssue);
				if (issue != null)
					report.issues.add(issue);
			}
		}

		return report;
	}

	// Getters & Setters
	public String getReportId() {
		return reportId;
	}
	public void setReportId(String reportId) {
		this.reportId = reportId;
	}

	public String getRecordingId() {
		return recordingId;
	}
	public void setRecordingId(String recordingId) {
		this.recordingId = recordingId;
	}

	public String getMetadataId() {
		return metadataId;
	}
	public void setMetadataId(String metadataId) {
		this.metadataId = metadataId;
	}

	public List<AudioQualityIssue> getIssues() {
		return issues;
	}
	public void setIssues(List<AudioQualityIssue> issues) {
		this.issues = issues;
	}

	public OverallQuality getOverallQuality() {
		return overallQuality;
	}
	public void setOverallQuality(OverallQuality overallQuality) {
		this.overallQuality = overallQuality;
	}

	public String getOverallSummary() {
		return overallSummary;
	}
	public void setOverallSummary(String overallSummary) {
		this.overallSummary = overallSummary;
	}

	public Timestamp getCreatedAt() {
		return createdAt;
	}
	public void setCreatedAt(Timestamp createdAt) {
		this.createdAt = createdAt;
	}
}
