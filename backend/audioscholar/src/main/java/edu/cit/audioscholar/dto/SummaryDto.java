package edu.cit.audioscholar.dto;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import edu.cit.audioscholar.model.Summary;
import edu.cit.audioscholar.model.SummaryKeyPoint;
import edu.cit.audioscholar.model.TranscriptSegment;
import edu.cit.audioscholar.service.TranscriptClarityService;

public class SummaryDto {

	private String summaryId;
	private String recordingId;
	private String userId;
	private String outputType;
	private String content;
	private String status;
	private Object qualityReport;
	private String qualityNotice;
	private boolean hasQualityWarnings;
	private List<String> keyPoints;
	private List<SummaryKeyPoint> summaryKeyPoints;
	private List<TranscriptSegment> transcriptSegments;
	private List<String> topics;
	private List<Map<String, String>> glossary;
	private String formattedSummaryText;
	private Date createdAt;
	private Date updatedAt;

	private SummaryDto() {
	}

	public String getSummaryId() {
		return summaryId;
	}

	public String getRecordingId() {
		return recordingId;
	}

	public String getUserId() {
		return userId;
	}

	public String getOutputType() {
		return outputType;
	}

	public String getContent() {
		return content;
	}

	public String getStatus() {
		return status;
	}

	public Object getQualityReport() {
		return qualityReport;
	}

	public String getQualityNotice() {
		return qualityNotice;
	}

	public boolean isHasQualityWarnings() {
		return hasQualityWarnings;
	}

	public List<String> getKeyPoints() {
		return (keyPoints != null) ? Collections.unmodifiableList(keyPoints) : null;
	}

	public List<SummaryKeyPoint> getSummaryKeyPoints() {
		return (summaryKeyPoints != null) ? Collections.unmodifiableList(summaryKeyPoints) : null;
	}

	public List<TranscriptSegment> getTranscriptSegments() {
		return (transcriptSegments != null) ? Collections.unmodifiableList(transcriptSegments) : null;
	}

	public List<String> getTopics() {
		return (topics != null) ? Collections.unmodifiableList(topics) : null;
	}

	public List<Map<String, String>> getGlossary() {
		return (glossary != null) ? Collections.unmodifiableList(glossary) : null;
	}

	public String getFormattedSummaryText() {
		return formattedSummaryText;
	}

	public Date getCreatedAt() {
		return (createdAt != null) ? (Date) createdAt.clone() : null;
	}

	public Date getUpdatedAt() {
		return (updatedAt != null) ? (Date) updatedAt.clone() : null;
	}

	public static SummaryDto fromModel(Summary summary) {
		if (summary == null) {
			return null;
		}

		SummaryDto dto = new SummaryDto();
		dto.summaryId = summary.getSummaryId();
		dto.recordingId = summary.getRecordingId();
		dto.userId = summary.getUserId();
		dto.outputType = summary.getOutputType();
		dto.content = summary.getContent();
		dto.status = summary.getStatus();
		dto.qualityReport = summary.getQualityReport();
		TranscriptClarityService clarityService = new TranscriptClarityService();
		dto.qualityNotice = clarityService.buildQualityNotice(summary.getQualityReport());
		dto.hasQualityWarnings = clarityService.hasQualityWarnings(summary.getQualityReport());
		dto.keyPoints = summary.getKeyPoints();
		dto.summaryKeyPoints = summary.getSummaryKeyPoints();
		dto.transcriptSegments = summary.getTranscriptSegments();
		dto.topics = summary.getTopics();
		dto.glossary = summary.getGlossary();
		dto.formattedSummaryText = summary.getFormattedSummaryText();
		dto.createdAt = summary.getCreatedAt();
		dto.updatedAt = summary.getUpdatedAt();

		return dto;
	}
}
