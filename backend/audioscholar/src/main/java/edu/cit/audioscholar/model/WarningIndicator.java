package edu.cit.audioscholar.model;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.cloud.Timestamp;

public class WarningIndicator {
	private String warningId = UUID.randomUUID().toString();
	private String summaryId;
	private String keyPointId;
	private String cardId;
	private String issueId;
	private String issueType;
	private String severity;
	private String recommendedAction;
	private String reportId;
	private String recordingId;
	private String summaryEvidenceVersion;
	private String templateId;
	private String templateVersion;
	private String renderedTextHash;
	private String conformanceRuleVersion;
	private boolean automatedConformancePassed;
	private String generationStatus;
	private boolean stale;
	private String evidenceSource = "PROCESSING";
	private Date createdAt = new Date();
	private Date expiresAt = new Date(System.currentTimeMillis() + 180L * 86_400_000L);

	public String getWarningId() {
		return warningId;
	}

	public void setWarningId(String warningId) {
		this.warningId = warningId;
	}

	public String getSummaryId() {
		return summaryId;
	}
	public void setSummaryId(String summaryId) {
		this.summaryId = summaryId;
	}

	public String getKeyPointId() {
		return keyPointId;
	}

	public void setKeyPointId(String keyPointId) {
		this.keyPointId = keyPointId;
	}

	public String getCardId() {
		return cardId;
	}

	public void setCardId(String cardId) {
		this.cardId = cardId;
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
	public String getReportId() {
		return reportId;
	}
	public void setReportId(String value) {
		reportId = value;
	}
	public String getRecordingId() {
		return recordingId;
	}
	public void setRecordingId(String value) {
		recordingId = value;
	}
	public String getSummaryEvidenceVersion() {
		return summaryEvidenceVersion;
	}
	public void setSummaryEvidenceVersion(String value) {
		summaryEvidenceVersion = value;
	}
	public String getTemplateId() {
		return templateId;
	}
	public void setTemplateId(String value) {
		templateId = value;
	}
	public String getTemplateVersion() {
		return templateVersion;
	}
	public void setTemplateVersion(String value) {
		templateVersion = value;
	}
	public String getRenderedTextHash() {
		return renderedTextHash;
	}
	public void setRenderedTextHash(String value) {
		renderedTextHash = value;
	}
	public String getConformanceRuleVersion() {
		return conformanceRuleVersion;
	}
	public void setConformanceRuleVersion(String value) {
		conformanceRuleVersion = value;
	}
	public boolean isAutomatedConformancePassed() {
		return automatedConformancePassed;
	}
	public void setAutomatedConformancePassed(boolean value) {
		automatedConformancePassed = value;
	}
	public String getGenerationStatus() {
		return generationStatus;
	}
	public void setGenerationStatus(String value) {
		generationStatus = value;
	}
	public boolean isStale() {
		return stale;
	}
	public void setStale(boolean value) {
		stale = value;
	}
	public String getEvidenceSource() {
		return evidenceSource;
	}
	public void setEvidenceSource(String value) {
		evidenceSource = value;
	}

	public Date getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Date createdAt) {
		this.createdAt = createdAt;
	}

	public void setExpiresAt(Date expiresAt) {
		this.expiresAt = expiresAt;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("warningId", warningId);
		map.put("summaryId", summaryId);
		map.put("keyPointId", keyPointId);
		map.put("cardId", cardId);
		map.put("issueId", issueId);
		map.put("issueType", issueType);
		map.put("severity", severity);
		map.put("recommendedAction", recommendedAction);
		map.put("reportId", reportId);
		map.put("recordingId", recordingId);
		map.put("summaryEvidenceVersion", summaryEvidenceVersion);
		map.put("templateId", templateId);
		map.put("templateVersion", templateVersion);
		map.put("renderedTextHash", renderedTextHash);
		map.put("conformanceRuleVersion", conformanceRuleVersion);
		map.put("automatedConformancePassed", automatedConformancePassed);
		map.put("generationStatus", generationStatus);
		map.put("stale", stale);
		map.put("evidenceSource", evidenceSource);
		map.put("createdAt", createdAt);
		map.put("expiresAt", expiresAt);
		return map;
	}

	public static WarningIndicator fromMap(Map<String, Object> map) {
		if (map == null) {
			return null;
		}
		WarningIndicator warning = new WarningIndicator();
		warning.warningId = (String) map.get("warningId");
		warning.summaryId = (String) map.get("summaryId");
		warning.keyPointId = (String) map.get("keyPointId");
		warning.cardId = (String) map.get("cardId");
		warning.issueId = (String) map.get("issueId");
		warning.issueType = (String) map.get("issueType");
		warning.severity = (String) map.get("severity");
		warning.recommendedAction = (String) map.get("recommendedAction");
		warning.reportId = (String) map.get("reportId");
		warning.recordingId = (String) map.get("recordingId");
		warning.summaryEvidenceVersion = (String) map.get("summaryEvidenceVersion");
		warning.templateId = (String) map.get("templateId");
		warning.templateVersion = (String) map.get("templateVersion");
		warning.renderedTextHash = (String) map.get("renderedTextHash");
		warning.conformanceRuleVersion = (String) map.get("conformanceRuleVersion");
		warning.automatedConformancePassed = Boolean.TRUE.equals(map.get("automatedConformancePassed"));
		warning.generationStatus = (String) map.get("generationStatus");
		warning.stale = Boolean.TRUE.equals(map.get("stale"));
		warning.evidenceSource = (String) map.getOrDefault("evidenceSource", "LEGACY_READ_BACKFILL");
		Object created = map.get("createdAt");
		if (created instanceof Timestamp timestamp) {
			warning.createdAt = timestamp.toDate();
		} else if (created instanceof Date date) {
			warning.createdAt = date;
		}
		Object expires = map.get("expiresAt");
		if (expires instanceof Timestamp timestamp)
			warning.expiresAt = timestamp.toDate();
		else if (expires instanceof Date date)
			warning.expiresAt = date;
		return warning;
	}
}
