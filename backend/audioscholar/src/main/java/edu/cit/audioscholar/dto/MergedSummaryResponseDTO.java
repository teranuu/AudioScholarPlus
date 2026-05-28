package edu.cit.audioscholar.dto;

import java.util.List;
import java.util.Map;

import edu.cit.audioscholar.model.MergedSummary;

public class MergedSummaryResponseDTO {
	private String mergedSummaryId;
	private String jobId;
	private String userId;
	private String content;
	private List<Map<String, Object>> sourceAttributions;
	private String status;

	public static MergedSummaryResponseDTO fromModel(MergedSummary summary) {
		MergedSummaryResponseDTO dto = new MergedSummaryResponseDTO();
		dto.mergedSummaryId = summary.getMergedSummaryId();
		dto.jobId = summary.getJobId();
		dto.userId = summary.getUserId();
		dto.content = summary.getContent();
		dto.sourceAttributions = summary.getSourceAttributions().stream().map(a -> a.toMap()).toList();
		dto.status = summary.getStatus();
		return dto;
	}

	public String getMergedSummaryId() {
		return mergedSummaryId;
	}

	public String getJobId() {
		return jobId;
	}

	public String getUserId() {
		return userId;
	}

	public String getContent() {
		return content;
	}

	public List<Map<String, Object>> getSourceAttributions() {
		return sourceAttributions;
	}

	public String getStatus() {
		return status;
	}
}
