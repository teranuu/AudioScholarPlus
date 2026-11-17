package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.model.MultiSourceJob;
import edu.cit.audioscholar.model.SourceAttribution;
import edu.cit.audioscholar.model.SourceFile;
import edu.cit.audioscholar.model.Summary;

class SemanticEvidenceServiceTest {
	@Test
	void groundedMergeRequiresEveryClaimAndPreservesBothContributors() throws Exception {
		GeminiService gemini = mock(GeminiService.class);
		FirebaseService firebase = mock(FirebaseService.class);
		SemanticEvidenceService service = new SemanticEvidenceService(gemini, firebase, new ObjectMapper(),
				mock(ValidationEventService.class), .80, "app", "summary", "semantic");
		MultiSourceJob job = new MultiSourceJob();
		job.setJobId("grounded-1");
		job.setOutputType("NOTES");
		job.setSourceFiles(List.of(source("a", "A"), source("b", "B")));
		when(firebase.queryCollection("transcriptSegments", "sourceFileId", "a")).thenReturn(List.of(Map.of("segmentId",
				"sa", "sourceFileId", "a", "text", "Water boils at 100 C.", "startMs", 0L, "endMs", 1000L)));
		when(firebase.queryCollection("transcriptSegments", "sourceFileId", "b")).thenReturn(List.of(Map.of("segmentId",
				"sb", "sourceFileId", "b", "text", "Water boils at 100 C.", "startMs", 0L, "endMs", 1000L)));
		when(gemini.callSimpleTextAPI(anyString(), eq("grounded-1"),
				eq("multi-source.grounded-claims-0.generateContent"))).thenReturn(
						"{\"claims\":[{\"sourceFileId\":\"a\",\"segmentId\":\"sa\",\"quote\":\"Water boils at 100 C.\",\"text\":\"Boiling point is 100 C\"},{\"sourceFileId\":\"b\",\"segmentId\":\"sb\",\"quote\":\"Water boils at 100 C.\",\"text\":\"Boiling point is 100 C\"}]}");
		String a = ValidationEventService
				.digest("grounded-1:claim-v1:a:sa:Water boils at 100 C.:Boiling point is 100 C");
		String b = ValidationEventService
				.digest("grounded-1:claim-v1:b:sb:Water boils at 100 C.:Boiling point is 100 C");
		when(gemini.callSimpleTextAPI(anyString(), eq("grounded-1"),
				eq("multi-source.grounded-groups.generateContent")))
				.thenReturn("{\"summaryText\":\"Boiling point\",\"groups\":[{\"claimIds\":[\"" + a + "\",\"" + b
						+ "\"],\"text\":\"Water boils at 100 C\"}]}");
		SemanticEvidenceService.GroundedResult result = service.evaluateGrounded(job);
		assertEquals(1, result.summary().getSummaryKeyPoints().size());
		assertEquals(2, result.attributions().size());
		assertTrue(result.attributions().stream().allMatch(x -> "MULTI_SOURCE".equals(x.getAttributionStatus())));
		verify(firebase).createEvidenceBatch(eq("multiSourceGroupCoverage"), anyMap());
	}

	@Test
	void groundedMergeRejectsInventedQuote() throws Exception {
		GeminiService gemini = mock(GeminiService.class);
		FirebaseService firebase = mock(FirebaseService.class);
		SemanticEvidenceService service = new SemanticEvidenceService(gemini, firebase, new ObjectMapper(),
				mock(ValidationEventService.class), .80, "app", "summary", "semantic");
		MultiSourceJob job = new MultiSourceJob();
		job.setJobId("bad-quote");
		job.setSourceFiles(List.of(source("a", "A")));
		when(firebase.queryCollection("transcriptSegments", "sourceFileId", "a"))
				.thenReturn(List.of(Map.of("segmentId", "sa", "sourceFileId", "a", "text", "Water boils at 100 C.")));
		when(gemini.callSimpleTextAPI(anyString(), eq("bad-quote"),
				eq("multi-source.grounded-claims-0.generateContent"))).thenReturn(
						"{\"claims\":[{\"sourceFileId\":\"a\",\"segmentId\":\"sa\",\"quote\":\"Water boils at 10 C.\",\"text\":\"Wrong\"}]}");
		assertThrows(IllegalStateException.class, () -> service.evaluateGrounded(job));
		verify(firebase, never()).createEvidenceBatch(eq("sourceKeyPoints"), anyMap());
	}
	@Test
	void vocabularyOverlapDoesNotProveSupportingEvidence() {
		Map<String, Object> passage = Map.of("text", "The dose is 15 mg.");
		assertEquals(null, SemanticEvidenceService.bestSegment("The dose is 1.5 mg.", List.of(passage)));
		assertEquals(null, SemanticEvidenceService.bestSegment("The dose is not 15 mg.", List.of(passage)));
		assertEquals(passage, SemanticEvidenceService.bestSegment("The dose is 15 mg.", List.of(passage)));
		assertEquals(passage, SemanticEvidenceService.bestSegment("The dose is 15 mg", List.of(passage)));
	}

	@Test
	void doesNotMergeTransitiveDuplicatesWithoutEveryPairConfirmed() {
		int[] parent = {0, 1, 2};
		Set<Long> chain = Set.of(1L, (1L << 32) | 2L);
		assertTrue(SemanticEvidenceService.mergeIfFullyConfirmed(parent, 0, 1, chain));
		assertFalse(SemanticEvidenceService.mergeIfFullyConfirmed(parent, 1, 2, chain));
		assertEquals(2, parent[2]);
		assertTrue(SemanticEvidenceService.mergeIfFullyConfirmed(parent, 1, 2, Set.of(1L, 2L, (1L << 32) | 2L)));
	}

	@Test
	void incompleteSemanticBatchFailsInsteadOfPublishingPartialDeduplication() throws Exception {
		GeminiService gemini = mock(GeminiService.class);
		FirebaseService firebase = mock(FirebaseService.class);
		ValidationEventService events = mock(ValidationEventService.class);
		SemanticEvidenceService service = new SemanticEvidenceService(gemini, firebase, new ObjectMapper(), events, .80,
				"test-version", "summary-model-v1", "semantic-model-v1");
		MultiSourceJob job = new MultiSourceJob();
		job.setJobId("job-1");
		SourceFile first = source("source-a", "A");
		SourceFile second = source("source-b", "B");
		job.setSourceFiles(List.of(first, second));
		when(gemini.callSimpleTextAPI(anyString(), eq("job-1"), eq("multi-source.key-point-batch.generateContent"),
				anyMap()))
				.thenReturn("{\"sources\":[{\"sourceFileId\":\"source-a\",\"keyPoints\":[\"claim A\"]},"
						+ "{\"sourceFileId\":\"source-b\",\"keyPoints\":[\"claim B\"]}]}");
		when(gemini.callSimpleTextAPI(anyString(), eq("job-1"), eq("multi-source.semantic-pair-batch.generateContent"),
				anyMap())).thenReturn("{\"pairs\":[]}");
		when(firebase.queryCollection(eq("transcriptSegments"), eq("sourceFileId"), anyString())).thenReturn(List.of());

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> service.evaluate(job, new Summary("summary-1", "job-1", "merged")));

		assertTrue(failure.getMessage().contains("omitted one or more pairs"));
	}

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

		when(gemini.callSimpleTextAPI(anyString(), eq("job-1"), eq("multi-source.key-point-batch.generateContent"),
				anyMap()))
				.thenReturn("{\"sources\":[{\"sourceFileId\":\"source-a\",\"keyPoints\":[\"shared claim\"]},"
						+ "{\"sourceFileId\":\"source-b\",\"keyPoints\":[\"shared claim\"]}]}");
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

	@Test
	void retryReusesCompletedSourceKeyPointCheckpoint() throws Exception {
		GeminiService gemini = mock(GeminiService.class);
		FirebaseService firebase = mock(FirebaseService.class);
		ValidationEventService events = mock(ValidationEventService.class);
		SemanticEvidenceService service = new SemanticEvidenceService(gemini, firebase, new ObjectMapper(), events, .80,
				"test-version", "summary-model-v1", "semantic-model-v1");
		MultiSourceJob job = new MultiSourceJob();
		job.setJobId("job-1");
		job.setOutputType("NOTES");
		job.setSourceFiles(List.of(source("source-a", "A"), source("source-b", "B")));
		when(firebase.getData("multiSourceStageCheckpoints",
				ValidationEventService.digest("job-1:source-key-point-checkpoint-v2")))
				.thenReturn(Map.of("pointsBySource",
						Map.of("source-a", List.of("shared claim"), "source-b", List.of("shared claim"))));
		when(firebase.queryCollection(eq("transcriptSegments"), eq("sourceFileId"), anyString())).thenReturn(List.of());

		service.evaluate(job, new Summary("summary-1", "job-1", "merged"));

		verify(gemini, never()).callSimpleTextAPI(anyString(), eq("job-1"),
				eq("multi-source.key-point-batch.generateContent"), anyMap());
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
