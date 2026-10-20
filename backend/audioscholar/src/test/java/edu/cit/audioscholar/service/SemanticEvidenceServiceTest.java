package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.model.MultiSourceJob;
import edu.cit.audioscholar.model.SourceAttribution;
import edu.cit.audioscholar.model.SourceFile;
import edu.cit.audioscholar.model.Summary;

class SemanticEvidenceServiceTest {

	@Test
	void decisionsReferencePersistedMergedPointAndAttributionIsPerContributor() throws Exception {
		GeminiService gemini = mock(GeminiService.class);
		FirebaseService firebase = mock(FirebaseService.class);
		ValidationEventService events = mock(ValidationEventService.class);
		SemanticEvidenceService service = new SemanticEvidenceService(gemini, firebase, new ObjectMapper(), events, .80,
				"test-version", "summary-model-v1", "semantic-model-v1");
		MultiSourceJob job = new MultiSourceJob();
		job.setJobId("job-1");
		job.setOutputType("NOTES");
		SourceFile unresolved = source("source-a", "A");
		SourceFile resolved = source("source-b", "B");
		job.setSourceFiles(List.of(unresolved, resolved));
		Summary summary = new Summary("summary-1", "job-1", "merged");

		when(gemini.generateTranscriptOnlySummary(anyString(), anyString(), eq("NOTES")))
				.thenReturn("{\"keyPoints\":[\"shared claim\"]}");
		when(firebase.queryCollection("transcriptSegments", "sourceFileId", "source-a")).thenReturn(List.of());
		when(firebase.queryCollection("transcriptSegments", "sourceFileId", "source-b"))
				.thenReturn(List.of(Map.of("segmentId", "segment-b", "sourceFileId", "source-b", "text", "shared claim",
						"startMs", 1000L, "endMs", 2000L)));

		List<SourceAttribution> attributions = service.evaluate(job, summary);

		String mergedPointId = summary.getSummaryKeyPoints().get(0).getKeyPointId();
		ArgumentCaptor<Map<String, Object>> decision = ArgumentCaptor.forClass(Map.class);
		verify(firebase).createEvidence(eq("semanticDecisions"), anyString(), decision.capture());
		assertEquals(mergedPointId, decision.getValue().get("canonicalKeyPointId"));
		assertEquals("source-b", summary.getSummaryKeyPoints().get(0).getSourceFileId());
		assertTrue(job.isMeasurementIncomplete());
		assertEquals("UNRESOLVED", status(attributions, "source-a"));
		assertEquals("MULTI_SOURCE", status(attributions, "source-b"));
		ArgumentCaptor<String> sourcePointIds = ArgumentCaptor.forClass(String.class);
		verify(firebase, org.mockito.Mockito.times(2)).createEvidence(eq("sourceKeyPoints"), sourcePointIds.capture(),
				org.mockito.ArgumentMatchers.anyMap());
		assertTrue(sourcePointIds.getAllValues().contains(ValidationEventService.digest("job-1:source-a:0")));
		ArgumentCaptor<Map<String, Object>> lineage = ArgumentCaptor.forClass(Map.class);
		verify(firebase).createEvidence(eq("mergedKeyPointLineage"), anyString(), lineage.capture());
		assertEquals(1, ((List<?>) lineage.getValue().get("semanticDecisionIds")).size());
	}

	private SourceFile source(String id, String label) {
		SourceFile source = new SourceFile();
		source.setSourceFileId(id);
		source.setSourceLabel(label);
		source.setTranscriptText("shared claim");
		return source;
	}

	private String status(List<SourceAttribution> attributions, String sourceFileId) {
		return attributions.stream().filter(a -> sourceFileId.equals(a.getSourceFileId())).findFirst().orElseThrow()
				.getAttributionStatus();
	}
}
