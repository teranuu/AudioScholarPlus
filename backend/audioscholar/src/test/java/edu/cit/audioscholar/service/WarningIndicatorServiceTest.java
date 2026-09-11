package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
		assertNull(savedMap.get("keyPointId"));
	}
}
