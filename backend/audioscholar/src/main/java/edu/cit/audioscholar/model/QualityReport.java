package edu.cit.audioscholar.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.cloud.Timestamp;

public class QualityReport {
	private String reportId;
	private String recordingId;
	private String attemptId;
	private String status;
	private String analysisStatus;
	private String analysisMode;
	private Long analyzedDurationMs;
	private Long totalDurationMs;
	private Double coverageRatio;
	private String detectorVersion;
	private String thresholdVersion;
	private Long analysisWindowMs;
	private String failureCode;
	private String failureDetail;
	private String audioFingerprint;
	private List<QualityIssue> issues = new ArrayList<>();
	private Date createdAt;
	private Date updatedAt;

	public QualityReport() {
		this.createdAt = new Date();
		this.updatedAt = new Date();
	}

	public static QualityReport allClear(String recordingId) {
		QualityReport report = new QualityReport();
		report.setRecordingId(recordingId);
		report.setStatus("ALL_CLEAR");
		report.setAnalysisStatus("COMPLETED");
		report.setAnalysisMode("FULL_PCM");
		return report;
	}

	public static QualityReport unavailable(String recordingId) {
		QualityReport report = new QualityReport();
		report.setRecordingId(recordingId);
		report.setStatus("UNAVAILABLE");
		report.setAnalysisStatus("FAILED");
		report.setAnalysisMode("NONE");
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
	public String getAttemptId() {
		return attemptId;
	}
	public void setAttemptId(String attemptId) {
		this.attemptId = attemptId;
	}
	public String getAnalysisStatus() {
		return analysisStatus;
	}
	public void setAnalysisStatus(String analysisStatus) {
		this.analysisStatus = analysisStatus;
	}
	public String getAnalysisMode() {
		return analysisMode;
	}
	public void setAnalysisMode(String analysisMode) {
		this.analysisMode = analysisMode;
	}
	public Long getAnalyzedDurationMs() {
		return analyzedDurationMs;
	}
	public void setAnalyzedDurationMs(Long value) {
		analyzedDurationMs = value;
	}
	public Long getTotalDurationMs() {
		return totalDurationMs;
	}
	public void setTotalDurationMs(Long value) {
		totalDurationMs = value;
	}
	public Double getCoverageRatio() {
		return coverageRatio;
	}
	public void setCoverageRatio(Double value) {
		coverageRatio = value;
	}
	public String getDetectorVersion() {
		return detectorVersion;
	}
	public void setDetectorVersion(String value) {
		detectorVersion = value;
	}
	public String getThresholdVersion() {
		return thresholdVersion;
	}
	public void setThresholdVersion(String value) {
		thresholdVersion = value;
	}
	public Long getAnalysisWindowMs() {
		return analysisWindowMs;
	}
	public void setAnalysisWindowMs(Long value) {
		analysisWindowMs = value;
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
	public String getAudioFingerprint() {
		return audioFingerprint;
	}
	public void setAudioFingerprint(String value) {
		audioFingerprint = value;
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
		map.put("attemptId", attemptId);
		map.put("status", status);
		map.put("analysisStatus", analysisStatus);
		map.put("analysisMode", analysisMode);
		map.put("analyzedDurationMs", analyzedDurationMs);
		map.put("totalDurationMs", totalDurationMs);
		map.put("coverageRatio", coverageRatio);
		map.put("detectorVersion", detectorVersion);
		map.put("thresholdVersion", thresholdVersion);
		map.put("analysisWindowMs", analysisWindowMs);
		map.put("failureCode", failureCode);
		map.put("failureDetail", failureDetail);
		map.put("audioFingerprint", audioFingerprint);
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
		report.attemptId = (String) map.get("attemptId");
		report.status = (String) map.get("status");
		report.analysisStatus = (String) map.get("analysisStatus");
		report.analysisMode = (String) map.get("analysisMode");
		report.analyzedDurationMs = number(map.get("analyzedDurationMs"));
		report.totalDurationMs = number(map.get("totalDurationMs"));
		Object coverage = map.get("coverageRatio");
		report.coverageRatio = coverage instanceof Number n ? n.doubleValue() : null;
		report.detectorVersion = (String) map.get("detectorVersion");
		report.thresholdVersion = (String) map.get("thresholdVersion");
		report.analysisWindowMs = number(map.get("analysisWindowMs"));
		report.failureCode = (String) map.get("failureCode");
		report.failureDetail = (String) map.get("failureDetail");
		report.audioFingerprint = (String) map.get("audioFingerprint");
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
	private static Long number(Object value) {
		return value instanceof Number n ? n.longValue() : null;
	}
}
