package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import edu.cit.audioscholar.model.Flashcard;
import edu.cit.audioscholar.model.QualityIssue;
import edu.cit.audioscholar.model.QualityReport;
import edu.cit.audioscholar.model.Summary;
import edu.cit.audioscholar.model.SummaryKeyPoint;
import edu.cit.audioscholar.model.WarningIndicator;

class WarningIndicatorServiceTest {

	@Test
	void generateWarningIndicatorsLinksReviewMaterialWarningsByCardId() throws Exception {
		SummaryService summaryService = mock(SummaryService.class);
		QualityReportService qualityReportService = mock(QualityReportService.class);
		FirebaseService firebaseService = mock(FirebaseService.class);
		WarningIndicatorService service = new WarningIndicatorService(summaryService, qualityReportService,
				firebaseService);

		Flashcard flashcard = new Flashcard("What is cohesion?", "Related responsibilities.");
		flashcard.setCardId("card-1");
		flashcard.setSourceStartTime("00:10");
		flashcard.setSourceEndTime("00:20");
		flashcard.setSourceFileId("source-1");
		flashcard.setSourceSegmentId("segment-1");

		QualityIssue issue = new QualityIssue("00:15", "00:18", "UNCLEAR_AUDIO", "MODERATE", "Review this card.");
		issue.setIssueId("issue-1");
		QualityReport report = new QualityReport();
		report.setIssues(List.of(issue));

		Summary summary = new Summary();
		summary.setSummaryId("summary-1");
		summary.setOutputType("REVIEW_MATERIAL");
		summary.setFlashcards(List.of(flashcard));
		summary.setQualityReport(report);

		when(summaryService.getSummaryById("summary-1")).thenReturn(summary);
		when(firebaseService.queryCollection(eq("summaryKeyPoints"), any(), any())).thenReturn(List.of());
		when(firebaseService.queryCollection(eq("warningIndicators"), any(), any())).thenReturn(List.of());

		List<WarningIndicator> warnings = service.generateWarningIndicators("summary-1");

		assertEquals(1, warnings.size());
		assertEquals("card-1", warnings.get(0).getCardId());
		assertNull(warnings.get(0).getKeyPointId());
		assertEquals("issue-1", warnings.get(0).getIssueId());

		ArgumentCaptor<Object> savedWarning = ArgumentCaptor.forClass(Object.class);
		verify(firebaseService).saveData(eq("warningIndicators"), any(), savedWarning.capture());
		@SuppressWarnings("unchecked")
		Map<String, Object> savedMap = (Map<String, Object>) savedWarning.getValue();
		assertEquals("card-1", savedMap.get("cardId"));
		assertEquals("summary-1", savedMap.get("summaryId"));
		assertNull(savedMap.get("keyPointId"));
	}

	@Test
	void getsWarningsBySummaryIdWithoutPerItemQueries() throws Exception {
		SummaryService summaryService = mock(SummaryService.class);
		QualityReportService qualityReportService = mock(QualityReportService.class);
		FirebaseService firebaseService = mock(FirebaseService.class);
		WarningIndicatorService service = new WarningIndicatorService(summaryService, qualityReportService,
				firebaseService);
		Summary summary = new Summary();
		summary.setSummaryId("summary-1");
		summary.setSummaryKeyPoints(List.of(new SummaryKeyPoint()));
		when(summaryService.getSummaryById("summary-1")).thenReturn(summary);
		when(firebaseService.queryCollection("summaryKeyPoints", "summaryId", "summary-1")).thenReturn(List.of());
		when(firebaseService.queryCollection("warningIndicators", "summaryId", "summary-1"))
				.thenReturn(List.of(Map.of("warningId", "warning-1", "summaryId", "summary-1")));

		List<WarningIndicator> warnings = service.getWarningIndicators("summary-1");

		assertEquals(1, warnings.size());
		assertEquals("summary-1", warnings.get(0).getSummaryId());
		verify(firebaseService, never()).queryCollectionWhereIn(any(), any(), any());
	}

	@Test
	void generationIsIdempotentAndUnsupportedTemplatesRemainBlockedCandidates() throws Exception {
		SummaryService summaryService = mock(SummaryService.class);
		QualityReportService qualityReportService = mock(QualityReportService.class);
		FirebaseService firebaseService = mock(FirebaseService.class);
		WarningIndicatorService service = new WarningIndicatorService(summaryService, qualityReportService,
				firebaseService);
		SummaryKeyPoint point = new SummaryKeyPoint();
		point.setKeyPointId("point-1");
		point.setText("Generated point");
		point.setSourceFileId("source-1");
		point.setSourceSegmentId("segment-1");
		point.setSourceStartTime("00:00");
		point.setSourceEndTime("00:10");
		QualityIssue issue = new QualityIssue("00:01", "00:05", "UNREGISTERED_ISSUE", "MODERATE", "Fallback");
		issue.setIssueId("issue-1");
		QualityReport report = new QualityReport();
		report.setReportId("report-1");
		report.setIssues(List.of(issue));
		Summary summary = new Summary();
		summary.setSummaryId("summary-1");
		summary.setSummaryKeyPoints(List.of(point));
		summary.setQualityReport(report);
		when(summaryService.getSummaryById("summary-1")).thenReturn(summary);
		when(firebaseService.queryCollection("summaryKeyPoints", "summaryId", "summary-1")).thenReturn(List.of());

		assertEquals(List.of(), service.generateWarningIndicators("summary-1"));
		assertEquals(List.of(), service.generateWarningIndicators("summary-1"));

		ArgumentCaptor<Object> candidates = ArgumentCaptor.forClass(Object.class);
		verify(firebaseService, org.mockito.Mockito.times(2)).saveData(eq("warningCandidates"), any(),
				candidates.capture());
		@SuppressWarnings("unchecked")
		Map<String, Object> first = (Map<String, Object>) candidates.getAllValues().get(0);
		@SuppressWarnings("unchecked")
		Map<String, Object> second = (Map<String, Object>) candidates.getAllValues().get(1);
		assertEquals(first.get("warningId"), second.get("warningId"));
		assertEquals("BLOCKED", first.get("generationStatus"));
		assertEquals(false, first.get("automatedConformancePassed"));
		verify(firebaseService, never()).saveData(eq("warningIndicators"), any(), any());
	}

	@Test
	void summaryEditMarksEveryEvidenceRecordStaleAndReplacesEvidenceVersion() {
		SummaryService summaryService = mock(SummaryService.class);
		FirebaseService firebaseService = mock(FirebaseService.class);
		WarningIndicatorService service = new WarningIndicatorService(summaryService, mock(QualityReportService.class),
				firebaseService);
		Summary summary = new Summary();
		summary.setSummaryId("summary-1");
		summary.setSummaryEvidenceVersion("old-version");
		summary.setFormattedSummaryText("edited content");
		for (String collection : List.of("warningMappings", "warningCandidates", "warningIndicators",
				"warningDisplayReceipts"))
			when(firebaseService.queryCollection(collection, "summaryId", "summary-1"))
					.thenReturn(List.of(Map.of("id", collection + "-1")));

		service.markEvidenceStale(summary);

		assertEquals(true, summary.isWarningEvidenceStale());
		assertNotEquals("old-version", summary.getSummaryEvidenceVersion());
		for (String collection : List.of("warningMappings", "warningCandidates", "warningIndicators",
				"warningDisplayReceipts"))
			verify(firebaseService).updateDataWithMap(collection, collection + "-1", Map.of("stale", true));
	}

	@Test
	void staleWarningCannotCreateAViewportOrExpansionReceipt() throws Exception {
		SummaryService summaryService = mock(SummaryService.class);
		FirebaseService firebaseService = mock(FirebaseService.class);
		WarningIndicatorService service = new WarningIndicatorService(summaryService, mock(QualityReportService.class),
				firebaseService);
		Summary summary = new Summary();
		summary.setSummaryId("summary-1");
		summary.setRecordingId("recording-1");
		summary.setSummaryEvidenceVersion("v2");
		when(summaryService.getSummaryById("summary-1")).thenReturn(summary);
		when(firebaseService.getData("warningIndicators", "warning-1")).thenReturn(Map.of("summaryId", "summary-1",
				"summaryEvidenceVersion", "v1", "generationStatus", "DISPLAYABLE", "stale", true));

		assertThrows(IllegalArgumentException.class,
				() -> service.recordDisplay("summary-1", "warning-1", "INDICATOR_VISIBLE"));
		assertThrows(IllegalArgumentException.class,
				() -> service.recordDisplay("summary-1", "warning-1", "DETAILS_EXPANDED"));
		verify(firebaseService, never()).saveData(eq("warningDisplayReceipts"), any(), any());
	}

	@Test
	void editedKeyPointsNeverReuseStoredStructuredEvidenceAndRegenerateDeterministically() throws Exception {
		SummaryService summaryService = mock(SummaryService.class);
		FirebaseService firebaseService = mock(FirebaseService.class);
		WarningIndicatorService service = new WarningIndicatorService(summaryService, mock(QualityReportService.class),
				firebaseService);
		QualityIssue issue = new QualityIssue("00:00", "00:05", "UNCLEAR_AUDIO", "MODERATE", "Review");
		issue.setIssueId("issue-1");
		QualityReport report = new QualityReport();
		report.setReportId("report-1");
		report.setIssues(List.of(issue));
		Summary summary = new Summary();
		summary.setSummaryId("summary-1");
		summary.setRecordingId("recording-1");
		summary.setKeyPoints(List.of("Edited key point"));
		summary.setWarningEvidenceStale(true);
		summary.setQualityReport(report);
		when(summaryService.getSummaryById("summary-1")).thenReturn(summary);
		when(firebaseService.queryCollection("summaryKeyPoints", "summaryId", "summary-1"))
				.thenReturn(List.of(Map.of("keyPointId", "old-point", "summaryId", "summary-1", "text", "Old text")));

		service.generateWarningIndicators("summary-1");
		String generatedId = summary.getSummaryKeyPoints().get(0).getKeyPointId();
		summary.setWarningEvidenceStale(true);
		summary.setSummaryKeyPoints(List.of());
		service.generateWarningIndicators("summary-1");

		assertNotEquals("old-point", generatedId);
		assertEquals(generatedId, summary.getSummaryKeyPoints().get(0).getKeyPointId());
		verify(firebaseService, never()).queryCollection("summaryKeyPoints", "summaryId", "summary-1");
	}
}
