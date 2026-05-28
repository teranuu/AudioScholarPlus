package edu.cit.audioscholar.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import edu.cit.audioscholar.model.QualityIssue;
import edu.cit.audioscholar.model.QualityReport;
import edu.cit.audioscholar.model.Summary;
import edu.cit.audioscholar.model.SummaryKeyPoint;
import edu.cit.audioscholar.model.WarningIndicator;

@Service
public class WarningIndicatorService {
	private static final String SUMMARY_KEY_POINTS_COLLECTION = "summaryKeyPoints";
	private static final String WARNING_INDICATORS_COLLECTION = "warningIndicators";

	private final SummaryService summaryService;
	private final QualityReportService qualityReportService;
	private final FirebaseService firebaseService;

	public WarningIndicatorService(SummaryService summaryService, QualityReportService qualityReportService,
			FirebaseService firebaseService) {
		this.summaryService = summaryService;
		this.qualityReportService = qualityReportService;
		this.firebaseService = firebaseService;
	}

	public List<WarningIndicator> generateWarningIndicators(String summaryId) throws Exception {
		Summary summary = summaryService.getSummaryById(summaryId);
		if (summary == null) {
			throw new IllegalArgumentException("Summary not found.");
		}
		QualityReport report = summary.getQualityReport();
		if (report == null && StringUtils.hasText(summary.getRecordingId())) {
			report = qualityReportService.getReport(summary.getRecordingId());
		}
		if (report == null || report.getIssues() == null || report.getIssues().isEmpty()) {
			return List.of();
		}

		List<SummaryKeyPoint> keyPoints = getSummaryKeyPoints(summary);
		List<WarningIndicator> warnings = new ArrayList<>();
		for (SummaryKeyPoint keyPoint : keyPoints) {
			for (QualityIssue issue : report.getIssues()) {
				if (checkTimestampOverlap(keyPoint.getSourceStartTime(), keyPoint.getSourceEndTime(),
						issue.getStartTime(), issue.getEndTime())) {
					WarningIndicator warning = new WarningIndicator();
					warning.setKeyPointId(keyPoint.getKeyPointId());
					warning.setIssueId(issue.getIssueId());
					warning.setIssueType(issue.getIssueType());
					warning.setSeverity(issue.getSeverity());
					warning.setRecommendedAction(issue.getRecommendedAction());
					firebaseService.saveData(WARNING_INDICATORS_COLLECTION, warning.getWarningId(), warning.toMap());
					warnings.add(warning);
				}
			}
		}
		return warnings;
	}

	public boolean checkTimestampOverlap(String keyPointStart, String keyPointEnd, String issueStart, String issueEnd) {
		Integer kpStart = parseTimestamp(keyPointStart);
		Integer kpEnd = parseTimestamp(keyPointEnd);
		Integer isStart = parseTimestamp(issueStart);
		Integer isEnd = parseTimestamp(issueEnd);
		if (kpStart == null || kpEnd == null || isStart == null || isEnd == null) {
			return false;
		}
		return kpStart <= isEnd && isStart <= kpEnd;
	}

	private List<SummaryKeyPoint> getSummaryKeyPoints(Summary summary) {
		List<Map<String, Object>> stored = firebaseService.queryCollection(SUMMARY_KEY_POINTS_COLLECTION, "summaryId",
				summary.getSummaryId());
		if (!stored.isEmpty()) {
			return stored.stream().map(SummaryKeyPoint::fromMap).filter(java.util.Objects::nonNull).toList();
		}
		if (summary.getSummaryKeyPoints() != null && !summary.getSummaryKeyPoints().isEmpty()) {
			return summary.getSummaryKeyPoints();
		}
		List<SummaryKeyPoint> generated = new ArrayList<>();
		for (String text : summary.getKeyPoints()) {
			SummaryKeyPoint keyPoint = new SummaryKeyPoint();
			keyPoint.setSummaryId(summary.getSummaryId());
			keyPoint.setText(text);
			generated.add(keyPoint);
		}
		return generated;
	}

	private Integer parseTimestamp(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String[] parts = value.trim().split(":");
		try {
			if (parts.length == 2) {
				return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
			}
			if (parts.length == 3) {
				return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
			}
		} catch (NumberFormatException ignored) {
			return null;
		}
		return null;
	}
}
