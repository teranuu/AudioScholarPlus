package edu.cit.audioscholar.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.cloud.Timestamp;

public class QualityReport {
	private String reportId;
	private String recordingId;
	private String status;
	private List<QualityIssue> issues = new ArrayList<>();
	private Date createdAt;
	private Date updatedAt;

	public QualityReport() {
		this.reportId = UUID.randomUUID().toString();
		this.createdAt = new Date();
		this.updatedAt = new Date();
	}

	public static QualityReport allClear(String recordingId) {
		QualityReport report = new QualityReport();
		report.setRecordingId(recordingId);
		report.setStatus("ALL_CLEAR");
		return report;
	}

	public static QualityReport unavailable(String recordingId) {
		QualityReport report = new QualityReport();
		report.setRecordingId(recordingId);
		report.setStatus("UNAVAILABLE");
		return report;
	}

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
	public String getStatus() {
		return status;
	}
	public void setStatus(String status) {
		this.status = status;
	}
	public List<QualityIssue> getIssues() {
		return issues;
	}
	public void setIssues(List<QualityIssue> issues) {
		this.issues = issues != null ? new ArrayList<>(issues) : new ArrayList<>();
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
		map.put("reportId", reportId);
		map.put("recordingId", recordingId);
		map.put("status", status);
		map.put("issues", issues.stream().map(QualityIssue::toMap).toList());
		map.put("createdAt", createdAt);
		map.put("updatedAt", updatedAt);
		return map;
	}

	@SuppressWarnings("unchecked")
	public static QualityReport fromMap(Map<String, Object> map) {
		if (map == null)
			return null;
		QualityReport report = new QualityReport();
		report.reportId = (String) map.get("reportId");
		report.recordingId = (String) map.get("recordingId");
		report.status = (String) map.get("status");
		Object created = map.get("createdAt");
		if (created instanceof Timestamp timestamp)
			report.createdAt = timestamp.toDate();
		else if (created instanceof Date date)
			report.createdAt = date;
		Object updated = map.get("updatedAt");
		if (updated instanceof Timestamp timestamp)
			report.updatedAt = timestamp.toDate();
		else if (updated instanceof Date date)
			report.updatedAt = date;
		Object issueObj = map.get("issues");
		if (issueObj instanceof List<?> rawIssues) {
			List<QualityIssue> parsed = new ArrayList<>();
			for (Object raw : rawIssues) {
				if (raw instanceof Map<?, ?> rawMap) {
					parsed.add(QualityIssue.fromMap((Map<String, Object>) rawMap));
				}
			}
			report.issues = parsed;
		}
		return report;
	}
}
