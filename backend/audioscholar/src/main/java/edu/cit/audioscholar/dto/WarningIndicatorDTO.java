package edu.cit.audioscholar.dto;

import edu.cit.audioscholar.model.WarningIndicator;

public class WarningIndicatorDTO {
	private String warningId;
	private String keyPointId;
	private String cardId;
	private String issueId;
	private String issueType;
	private String severity;
	private String recommendedAction;
	private String generationStatus;
	private String summaryEvidenceVersion;

	public static WarningIndicatorDTO fromModel(WarningIndicator warningIndicator) {
		WarningIndicatorDTO dto = new WarningIndicatorDTO();
		dto.warningId = warningIndicator.getWarningId();
		dto.keyPointId = warningIndicator.getKeyPointId();
		dto.cardId = warningIndicator.getCardId();
		dto.issueId = warningIndicator.getIssueId();
		dto.issueType = warningIndicator.getIssueType();
		dto.severity = warningIndicator.getSeverity();
		dto.recommendedAction = warningIndicator.getRecommendedAction();
		dto.generationStatus = warningIndicator.getGenerationStatus();
		dto.summaryEvidenceVersion = warningIndicator.getSummaryEvidenceVersion();
		return dto;
	}

	public String getWarningId() {
		return warningId;
	}

	public String getKeyPointId() {
		return keyPointId;
	}

	public String getCardId() {
		return cardId;
	}

	public String getIssueId() {
		return issueId;
	}

	public String getIssueType() {
		return issueType;
	}

	public String getSeverity() {
		return severity;
	}

	public String getRecommendedAction() {
		return recommendedAction;
	}
	public String getGenerationStatus() {
		return generationStatus;
	}
	public String getSummaryEvidenceVersion() {
		return summaryEvidenceVersion;
	}
}
