package edu.cit.audioscholar.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import edu.cit.audioscholar.model.Flashcard;
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
		persistSummaryKeyPoints(summary.getSummaryId(), keyPoints);
		List<WarningIndicator> warnings = new ArrayList<>();
		Set<String> existingLinks = existingWarningLinks(keyPoints);
		existingLinks.addAll(existingFlashcardWarningLinks(summary.getFlashcards()));
		for (SummaryKeyPoint keyPoint : keyPoints) {
			for (QualityIssue issue : report.getIssues()) {
				if (checkTimestampOverlap(keyPoint.getSourceStartTime(), keyPoint.getSourceEndTime(),
						issue.getStartTime(), issue.getEndTime())) {
					String linkKey = keyPoint.getKeyPointId() + "::" + issue.getIssueId();
					if (existingLinks.contains(linkKey)) {
						continue;
					}
					WarningIndicator warning = new WarningIndicator();
					warning.setSummaryId(summary.getSummaryId());
					warning.setKeyPointId(keyPoint.getKeyPointId());
					warning.setIssueId(issue.getIssueId());
					warning.setIssueType(issue.getIssueType());
					warning.setSeverity(issue.getSeverity());
					warning.setRecommendedAction(issue.getRecommendedAction());
					firebaseService.saveData(WARNING_INDICATORS_COLLECTION, warning.getWarningId(), warning.toMap());
					existingLinks.add(linkKey);
					warnings.add(warning);
				}
			}
		}
		for (Flashcard flashcard : summary.getFlashcards()) {
			if (flashcard == null || flashcard.getCardId() == null) {
				continue;
			}
			for (QualityIssue issue : report.getIssues()) {
				if (checkTimestampOverlap(flashcard.getSourceStartTime(), flashcard.getSourceEndTime(),
						issue.getStartTime(), issue.getEndTime())) {
					String linkKey = flashcard.getCardId() + "::" + issue.getIssueId();
					if (existingLinks.contains(linkKey)) {
						continue;
					}
					WarningIndicator warning = new WarningIndicator();
					warning.setSummaryId(summary.getSummaryId());
					warning.setCardId(flashcard.getCardId());
					warning.setIssueId(issue.getIssueId());
					warning.setIssueType(issue.getIssueType());
					warning.setSeverity(issue.getSeverity());
					warning.setRecommendedAction(issue.getRecommendedAction());
					firebaseService.saveData(WARNING_INDICATORS_COLLECTION, warning.getWarningId(), warning.toMap());
					existingLinks.add(linkKey);
					warnings.add(warning);
				}
			}
		}
		return warnings;
	}

	public List<WarningIndicator> getWarningIndicators(String summaryId) throws Exception {
		Summary summary = summaryService.getSummaryById(summaryId);
		if (summary == null) {
			throw new IllegalArgumentException("Summary not found.");
		}
		List<SummaryKeyPoint> keyPoints = getSummaryKeyPoints(summary);
		List<WarningIndicator> warnings = mapWarnings(
				firebaseService.queryCollection(WARNING_INDICATORS_COLLECTION, "summaryId", summaryId));
		if (warnings.isEmpty()) {
			warnings = getLegacyWarnings(keyPoints, summary.getFlashcards());
		}
		return warnings.isEmpty() ? generateWarningIndicators(summaryId) : warnings;
	}

	private List<WarningIndicator> getLegacyWarnings(List<SummaryKeyPoint> keyPoints, List<Flashcard> flashcards) {
		List<WarningIndicator> warnings = new ArrayList<>();
		List<String> keyPointIds = keyPoints.stream().filter(java.util.Objects::nonNull)
				.map(SummaryKeyPoint::getKeyPointId).filter(StringUtils::hasText).toList();
		for (List<String> chunk : chunks(keyPointIds, 30)) {
			warnings.addAll(mapWarnings(
					firebaseService.queryCollectionWhereIn(WARNING_INDICATORS_COLLECTION, "keyPointId", chunk)));
		}
		List<Flashcard> safeFlashcards = flashcards == null ? List.of() : flashcards;
		List<String> cardIds = safeFlashcards.stream().filter(java.util.Objects::nonNull).map(Flashcard::getCardId)
				.filter(StringUtils::hasText).toList();
		for (List<String> chunk : chunks(cardIds, 30)) {
			warnings.addAll(mapWarnings(
					firebaseService.queryCollectionWhereIn(WARNING_INDICATORS_COLLECTION, "cardId", chunk)));
		}
		return warnings;
	}

	private List<WarningIndicator> mapWarnings(List<Map<String, Object>> stored) {
		return stored.stream().map(WarningIndicator::fromMap).filter(java.util.Objects::nonNull).toList();
	}

	private List<List<String>> chunks(List<String> values, int size) {
		List<List<String>> chunks = new ArrayList<>();
		for (int start = 0; start < values.size(); start += size) {
			chunks.add(values.subList(start, Math.min(start + size, values.size())));
		}
		return chunks;
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

	private void persistSummaryKeyPoints(String summaryId, List<SummaryKeyPoint> keyPoints) {
		if (keyPoints == null) {
			return;
		}
		for (SummaryKeyPoint keyPoint : keyPoints) {
			if (keyPoint == null || keyPoint.getKeyPointId() == null) {
				continue;
			}
			keyPoint.setSummaryId(summaryId);
			firebaseService.saveData(SUMMARY_KEY_POINTS_COLLECTION, keyPoint.getKeyPointId(), keyPoint.toMap());
		}
	}

	private Set<String> existingWarningLinks(List<SummaryKeyPoint> keyPoints) {
		Set<String> links = new HashSet<>();
		if (keyPoints == null) {
			return links;
		}
		for (SummaryKeyPoint keyPoint : keyPoints) {
			if (keyPoint == null || keyPoint.getKeyPointId() == null) {
				continue;
			}
			List<Map<String, Object>> stored = firebaseService.queryCollection(WARNING_INDICATORS_COLLECTION,
					"keyPointId", keyPoint.getKeyPointId());
			for (Map<String, Object> item : stored) {
				WarningIndicator warning = WarningIndicator.fromMap(item);
				if (warning != null && warning.getIssueId() != null) {
					links.add(keyPoint.getKeyPointId() + "::" + warning.getIssueId());
				}
			}
		}
		return links;
	}

	private Set<String> existingFlashcardWarningLinks(List<Flashcard> flashcards) {
		Set<String> links = new HashSet<>();
		if (flashcards == null) {
			return links;
		}
		for (Flashcard flashcard : flashcards) {
			if (flashcard == null || flashcard.getCardId() == null) {
				continue;
			}
			List<Map<String, Object>> stored = firebaseService.queryCollection(WARNING_INDICATORS_COLLECTION, "cardId",
					flashcard.getCardId());
			for (Map<String, Object> item : stored) {
				WarningIndicator warning = WarningIndicator.fromMap(item);
				if (warning != null && warning.getIssueId() != null) {
					links.add(flashcard.getCardId() + "::" + warning.getIssueId());
				}
			}
		}
		return links;
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
