package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import edu.cit.audioscholar.config.RabbitMQConfig;
import edu.cit.audioscholar.dto.MultiSourceJobMessage;
import edu.cit.audioscholar.exception.GeminiBudgetExceededException;
import edu.cit.audioscholar.exception.GeminiStageRequestLimitException;
import edu.cit.audioscholar.model.MultiSourceJob;
import edu.cit.audioscholar.model.SourceFile;
import edu.cit.audioscholar.model.SourceKind;
import edu.cit.audioscholar.model.Summary;

class MultiSourceJobServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void acceptsOneMediaAndOneDocumentAsTwoSources() throws Exception {
		MultiSourceJob job = submissionService().createAndProcess("user-1", List.of(media("a.mp3")),
				List.of(document("slides.pdf")), "Title", null, "NOTES");

		assertEquals(2, job.getSourceCount());
		assertEquals(List.of("MEDIA", "DOCUMENT"),
				job.getSourceFiles().stream().map(SourceFile::getSourceKind).toList());
	}

	@Test
	void acceptsTwoDocumentsAsTwoSources() throws Exception {
		MultiSourceJob job = submissionService().createAndProcess("user-1", List.of(),
				List.of(document("slides.pdf"), document("lecture.pptx")), "Title", null, "NOTES");

		assertEquals(2, job.getSourceCount());
		assertTrue(job.getSourceFiles().stream().allMatch(source -> "DOCUMENT".equals(source.getSourceKind())));
	}

	@Test
	void acceptsAndQueuesUploadEvenWhenLegacyDailyAdmissionWouldRejectIt() throws Exception {
		GeminiRequestGovernor governor = governor();
		doThrow(new GeminiBudgetExceededException("legacy global cap exhausted", Instant.now().plusSeconds(3600)))
				.when(governor).requireDailyCapacity(anyString(), anyLong(), anyString());

		MultiSourceJob job = submissionService(governor).createAndProcess("user-1",
				List.of(media("a.mp3"), media("b.mp3")), List.of(), "Title", null, "NOTES");

		assertEquals("PROCESSING_QUEUED", job.getStatus());
		verify(governor, never()).requireDailyCapacity(anyString(), anyLong(), anyString());
	}

	@Test
	void rejectsFewerThanTwoTotalSources() throws Exception {
		MultiSourceJobService service = service();

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> service.createAndProcess("user-1", List.of(media("a.mp3")), List.of(), "Title", null, "NOTES"));

		assertTrue(exception.getMessage().contains("at least two sources"));
	}

	@Test
	void rejectsDocumentSubmittedAsMedia() throws Exception {
		MultiSourceJobService service = service();

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> service.createAndProcess("user-1", List.of(media("a.mp3"), document("slides.pdf")), null, "Title",
						null, "NOTES"));

		assertTrue(exception.getMessage().contains("Unsupported media source file type"));
	}

	@Test
	void rejectsMediaSubmittedAsDocument() throws Exception {
		MultiSourceJobService service = service();

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> service.createAndProcess("user-1", List.of(media("a.mp3"), media("b.mp3")),
						List.of(media("extra.mp3")), "Title", null, "NOTES"));

		assertTrue(exception.getMessage().contains("Unsupported document source file type"));
	}

	@Test
	void rejectsMoreThanFiveTotalSources() throws Exception {
		MultiSourceJobService service = service();

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> service.createAndProcess("user-1",
						List.of(media("a.mp3"), media("b.mp3"), media("c.mp3"), media("d.mp3")),
						List.of(document("slides.pdf"), document("notes.docx")), "Title", null, "NOTES"));

		assertTrue(exception.getMessage().contains("no more than five sources"));
	}

	@Test
	void rejectsAggregateUploadSizeFromGuardrail() throws Exception {
		AudioProcessingGuardrailService guardrailService = mock(AudioProcessingGuardrailService.class);
		doThrow(new edu.cit.audioscholar.exception.ProcessingGuardrailException(
				"Combined multi-source upload size exceeds the 100 MB limit.")).when(guardrailService)
				.validateUploadBytes(anyList(), anyList());
		MultiSourceJobService service = service(guardrailService);

		edu.cit.audioscholar.exception.ProcessingGuardrailException exception = assertThrows(
				edu.cit.audioscholar.exception.ProcessingGuardrailException.class,
				() -> service.createAndProcess("user-1", List.of(media("a.mp3"), media("b.mp3")),
						List.of(document("slides.pdf")), "Title", null, "NOTES"));

		assertTrue(exception.getMessage().contains("Combined multi-source upload size"));
	}

	@Test
	void parseMergedSummaryDeduplicatesReviewMaterialFlashcards() throws Exception {
		MultiSourceJobService service = serviceWithDeduplication();
		MultiSourceJob job = new MultiSourceJob();
		job.setJobId("job-1");
		job.setUserId("user-1");
		job.setOutputType("REVIEW_MATERIAL");
		String summaryJson = """
				{
				  "summaryText": "Deck overview",
				  "keyPoints": [],
				  "topics": [],
				  "glossary": [],
				  "flashcards": [
				    {"front": "What is polymorphism?", "back": "Many forms."},
				    {"front": "What is polymorphism", "back": "Duplicate wording."},
				    {"front": "What is inheritance?", "back": "Sharing behavior."}
				  ]
				}
				""";

		Method parser = MultiSourceJobService.class.getDeclaredMethod("parseMergedSummary", MultiSourceJob.class,
				String.class);
		parser.setAccessible(true);
		Summary summary = (Summary) parser.invoke(service, job, summaryJson);

		assertEquals(2, summary.getFlashcards().size());
		assertEquals("What is polymorphism?", summary.getFlashcards().get(0).getFront());
		assertEquals("What is inheritance?", summary.getFlashcards().get(1).getFront());
	}

	@Test
	void rejectsLegacyGeminiErrorPayloadAsMediaTranscript() throws Exception {
		MultiSourceJobService service = service();
		Method reject = MultiSourceJobService.class.getDeclaredMethod("rejectGeminiErrorTranscript", String.class);
		reject.setAccessible(true);
		java.lang.reflect.InvocationTargetException wrapped = assertThrows(
				java.lang.reflect.InvocationTargetException.class, () -> reject.invoke(service,
						"{\"error\":\"Unexpected Transcription Error\",\"details\":\"503 Service Unavailable\"}"));
		java.io.IOException exception = (java.io.IOException) wrapped.getCause();

		assertTrue(exception.getMessage().contains("Gemini transcription failed"));
	}

	@Test
	void terminalRaceDoesNotPublishCompletionAfterReconciliationWon() throws Exception {
		ProcessingHarness harness = processingHarness(false);

		harness.service.processQueuedJob("job-1");

		verify(harness.repository).transitionTerminal(any(MultiSourceJob.class), eq("JOB_COMPLETED"), eq("initial"),
				anyList(), anyMap());
		verify(harness.events, never()).emit(eq("job-1"), eq("MULTI_SOURCE"), anyString(), eq("JOB_COMPLETED"),
				anyList(), anyString(), anyMap(), anyString());
		verify(harness.repository, never()).markTerminalEventPublished("job-1");
	}

	@Test
	void defersJobWithoutClaimingWhenGlobalProcessingLeaseIsBusy() throws Exception {
		ProcessingHarness harness = processingHarness(true);
		when(harness.repository.tryAcquireProcessingLease(anyString(), any())).thenReturn(false);

		harness.service.processQueuedJob("job-1");

		verify(harness.repository, never()).claim(anyString());
		verify(harness.repository, never()).releaseProcessingLease(anyString());
	}

	@Test
	void futureQuotaWindowIsRebucketedWithoutClaimingOrTakingTheGlobalLease() throws Exception {
		ProcessingHarness harness = processingHarness(true);
		when(harness.repository.findById("job-1")).thenReturn(Map.of("jobId", "job-1", "status", "PROCESSING_QUEUED",
				"quotaRetryAt", Date.from(Instant.now().plusSeconds(3600))));

		harness.service.processQueuedJob("job-1");

		verify(harness.publisher).publishToProcessingExchange(eq(RabbitMQConfig.MULTI_SOURCE_QUOTA_RETRY_ROUTING_KEY),
				any(MultiSourceJobMessage.class));
		verify(harness.repository, never()).claim(anyString());
		verify(harness.repository, never()).tryAcquireProcessingLease(anyString(), any());
	}

	@Test
	void semanticEnrichmentFailureCompletesWithDegradedResult() throws Exception {
		ProcessingHarness harness = processingHarness(true);
		when(harness.semantic.evaluate(any(), any())).thenThrow(new IllegalStateException("provider unavailable"));

		harness.service.processQueuedJob("job-1");

		verify(harness.repository).transitionTerminal(
				argThat(job -> "COMPLETE".equals(job.getStatus())
						&& "DEGRADED".equals(job.getSemanticEnrichmentStatus()) && job.isMeasurementIncomplete()),
				eq("JOB_COMPLETED"), eq("initial"), anyList(), anyMap());
		verify(harness.repository, never()).transitionTerminal(any(), eq("JOB_FAILED"), anyString(), anyList(),
				anyMap());
	}

	@Test
	void terminalStateAndPendingEventArePersistedBeforePublishingCompletion() throws Exception {
		ProcessingHarness harness = processingHarness(true);

		harness.service.processQueuedJob("job-1");

		var order = inOrder(harness.repository, harness.events);
		order.verify(harness.repository).transitionTerminal(any(MultiSourceJob.class), eq("JOB_COMPLETED"),
				eq("initial"), anyList(), anyMap());
		order.verify(harness.events).emit(eq("job-1"), eq("MULTI_SOURCE"), eq("WEB"), eq("JOB_COMPLETED"), anyList(),
				eq("initial"), anyMap(), eq("multiSourceJobs"));
		order.verify(harness.repository).markTerminalEventPublished("job-1");
	}

	@Test
	void persistedSourceCountMismatchFailsPermanentlyInsteadOfCompletingPartialBatch() throws Exception {
		ProcessingHarness harness = processingHarness(true);
		when(harness.sourceFiles.findByJobId("job-1")).thenReturn(List.of(completedSource("source-a", "Source A")));

		harness.service.processQueuedJob("job-1");

		verify(harness.repository).transitionTerminal(
				argThat(job -> "SOURCE_COUNT_MISMATCH".equals(job.getFailureCode())
						&& Integer.valueOf(1).equals(job.getFinalAttemptCount())),
				eq("JOB_FAILED"), eq("initial"), anyList(), anyMap());
		verify(harness.repository, never()).transitionTerminal(any(), eq("JOB_COMPLETED"), anyString(), anyList(),
				anyMap());
	}

	@Test
	void persistedDocumentOnlyBatchCanComplete() throws Exception {
		ProcessingHarness harness = processingHarness(true);
		when(harness.sourceFiles.findByJobId("job-1"))
				.thenReturn(List.of(completedSource("source-a", "Source A", "DOCUMENT"),
						completedSource("source-b", "Source B", "DOCUMENT")));

		harness.service.processQueuedJob("job-1");

		verify(harness.repository).transitionTerminal(any(MultiSourceJob.class), eq("JOB_COMPLETED"), eq("initial"),
				anyList(), anyMap());
		verify(harness.repository, never()).transitionTerminal(any(), eq("JOB_FAILED"), anyString(), anyList(),
				anyMap());
		verify(harness.transcripts, never()).saveTranscript(anyString(), any(SourceFile.class));
		verify(harness.segments, never()).persist(any(SourceFile.class));
	}

	@Test
	void dailyQuotaExhaustionIsDeferredUntilRetryAtInsteadOfImmediatelyRetried() throws Exception {
		ProcessingHarness harness = processingHarness(true);
		Instant retryAt = Instant.now().plusSeconds(3600);
		when(harness.gemini.generateTranscriptOnlySummary(anyString(), eq("job-1"), eq("NOTES")))
				.thenThrow(new GeminiBudgetExceededException("daily budget exhausted", retryAt));

		harness.service.processQueuedJob("job-1");

		verify(harness.publisher).publishToProcessingExchange(eq(RabbitMQConfig.MULTI_SOURCE_QUOTA_RETRY_ROUTING_KEY),
				any(MultiSourceJobMessage.class));
		verify(harness.publisher, never()).publishToProcessingExchange(eq(RabbitMQConfig.MULTI_SOURCE_ROUTING_KEY),
				any(MultiSourceJobMessage.class), any(java.time.Duration.class));
		verify(harness.repository, atLeastOnce()).saveIfNonterminal(
				argThat(job -> "PROCESSING_QUEUED".equals(job.getStatus()) && job.getQuotaRetryAt() != null
						&& !job.getQuotaRetryAt().toInstant().isBefore(retryAt.minusSeconds(1))));
		verify(harness.repository, never()).transitionTerminal(any(), anyString(), anyString(), anyList(), anyMap());
	}

	@Test
	void semanticQuotaExhaustionDefersDurableJobInsteadOfCompletingDegraded() throws Exception {
		ProcessingHarness harness = processingHarness(true);
		Instant retryAt = Instant.now().plusSeconds(90);
		when(harness.semantic.evaluate(any(), any()))
				.thenThrow(new GeminiBudgetExceededException("semantic RPM exhausted", retryAt));

		harness.service.processQueuedJob("job-1");

		verify(harness.publisher).publishToProcessingExchange(eq(RabbitMQConfig.MULTI_SOURCE_QUOTA_RETRY_ROUTING_KEY),
				any(MultiSourceJobMessage.class));
		verify(harness.repository, atLeastOnce()).saveIfNonterminal(
				argThat(job -> "PROCESSING_QUEUED".equals(job.getStatus()) && job.getQuotaRetryAt() != null
						&& !job.getQuotaRetryAt().toInstant().isBefore(retryAt.minusSeconds(1))));
		verify(harness.repository, never()).transitionTerminal(any(), eq("JOB_COMPLETED"), anyString(), anyList(),
				anyMap());
	}

	@Test
	void ordinaryFailureUsesTheShortDelayQueueInsteadOfTheActiveQueue() throws Exception {
		ProcessingHarness harness = processingHarness(true);
		when(harness.gemini.generateTranscriptOnlySummary(anyString(), eq("job-1"), eq("NOTES")))
				.thenThrow(new IllegalStateException("temporary parser failure"));

		harness.service.processQueuedJob("job-1");

		verify(harness.publisher).publishToProcessingExchange(eq(RabbitMQConfig.MULTI_SOURCE_RETRY_ROUTING_KEY),
				any(MultiSourceJobMessage.class));
		verify(harness.publisher, never()).publishToProcessingExchange(eq(RabbitMQConfig.MULTI_SOURCE_ROUTING_KEY),
				any(MultiSourceJobMessage.class), any(java.time.Duration.class));
	}

	@Test
	void stageAttemptLimitFailsTerminallyInsteadOfBeingDeferredToQuotaReset() throws Exception {
		ProcessingHarness harness = processingHarness(true);
		when(harness.gemini.generateTranscriptOnlySummary(anyString(), eq("job-1"), eq("NOTES")))
				.thenThrow(new GeminiStageRequestLimitException("transcript-summary.generateContent", 2));

		harness.service.processQueuedJob("job-1");

		verify(harness.repository).transitionTerminal(
				argThat(job -> "FAILED".equals(job.getStatus())
						&& "GEMINI_STAGE_REQUEST_LIMIT".equals(job.getFailureCode()) && job.getQuotaRetryAt() == null),
				eq("JOB_FAILED"), eq("initial"), anyList(), anyMap());
		verify(harness.publisher, never()).publishToProcessingExchange(anyString(), any(),
				any(java.time.Duration.class));
	}

	private ProcessingHarness processingHarness(boolean terminalTransitionWins) throws Exception {
		GeminiService gemini = mock(GeminiService.class);
		DeduplicationService deduplication = mock(DeduplicationService.class);
		SourceFileService sourceFiles = mock(SourceFileService.class);
		MultiSourceJobRepository repository = mock(MultiSourceJobRepository.class);
		ValidationEventService events = mock(ValidationEventService.class);
		SemanticEvidenceService semantic = mock(SemanticEvidenceService.class);
		SourceTranscriptService transcripts = mock(SourceTranscriptService.class);
		TimedTranscriptSegmentService segments = mock(TimedTranscriptSegmentService.class);
		GeminiRequestGovernor governor = governor();
		ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
		when(repository.tryAcquireProcessingLease(anyString(), any())).thenReturn(true);
		when(repository.claim("job-1")).thenReturn(true);
		when(repository.findById("job-1"))
				.thenReturn(Map.of("jobId", "job-1", "userId", "user-1", "title", "Title", "outputType", "NOTES",
						"status", "PROCESSING", "clientSource", "WEB", "sourceCount", 2, "processingAttempts", 0));
		when(repository.saveIfNonterminal(any())).thenReturn(true);
		when(repository.transitionTerminal(any(), eq("JOB_COMPLETED"), anyString(), anyList(), anyMap()))
				.thenReturn(terminalTransitionWins);
		when(sourceFiles.findByJobId("job-1"))
				.thenReturn(List.of(completedSource("source-a", "Source A"), completedSource("source-b", "Source B")));
		when(gemini.generateTranscriptOnlySummary(anyString(), eq("job-1"), eq("NOTES"))).thenReturn(
				"{\"summaryText\":\"Merged\",\"keyPoints\":[],\"topics\":[],\"glossary\":[],\"flashcards\":[]}");
		when(deduplication.removeDuplicateText(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
		when(deduplication.removeDuplicateFlashcards(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
		when(semantic.evaluate(any(), any())).thenReturn(List.of());
		when(events.emit(anyString(), anyString(), anyString(), anyString(), anyList(), anyString(), anyMap(),
				anyString())).thenReturn(true);
		MultiSourceJobService service = new MultiSourceJobService(gemini, mock(QualityReportService.class),
				mock(SummaryService.class), deduplication, mock(SourceAttributionService.class), sourceFiles,
				transcripts, mock(DocumentTextExtractionService.class), mock(MergedSummaryRepository.class), repository,
				mock(AudioProcessingGuardrailService.class), tempDir.toString(), "500MB", publisher, events, segments,
				semantic, governor, java.time.Duration.ofMinutes(35), java.time.Duration.ofSeconds(15), "test-worker");
		return new ProcessingHarness(service, repository, events, sourceFiles, gemini, publisher, transcripts, segments,
				semantic);
	}

	private record ProcessingHarness(MultiSourceJobService service, MultiSourceJobRepository repository,
			ValidationEventService events, SourceFileService sourceFiles, GeminiService gemini,
			ConfirmedRabbitPublisher publisher, SourceTranscriptService transcripts,
			TimedTranscriptSegmentService segments, SemanticEvidenceService semantic) {
	}

	private Map<String, Object> completedSource(String sourceFileId, String label) {
		return completedSource(sourceFileId, label, "MEDIA");
	}

	private Map<String, Object> completedSource(String sourceFileId, String label, String sourceKind) {
		return Map.of("sourceFileId", sourceFileId, "jobId", "job-1", "sourceLabel", label, "sourceKind", sourceKind,
				"fileName", sourceFileId + ".mp3", "storageObjectId", "object-" + sourceFileId, "transcriptText",
				"Shared lecture point.", "transcriptionCompletedAt", new Date());
	}

	private MultiSourceJobService submissionService() throws Exception {
		return submissionService(governor());
	}

	private MultiSourceJobService submissionService(GeminiRequestGovernor governor) throws Exception {
		SourceFileService sourceFiles = mock(SourceFileService.class);
		when(sourceFiles.createSourceFile(anyString(), anyString(), any(SourceKind.class), any(MultipartFile.class),
				any(Path.class))).thenAnswer(invocation -> {
					SourceFile source = new SourceFile();
					source.setJobId(invocation.getArgument(0));
					source.setSourceLabel(invocation.getArgument(1));
					source.setSourceKind(invocation.getArgument(2, SourceKind.class).name());
					source.setFileName(invocation.getArgument(3, MultipartFile.class).getOriginalFilename());
					return source;
				});
		AudioProcessingGuardrailService guardrails = mock(AudioProcessingGuardrailService.class);
		when(guardrails.validateAudioFile(any(Path.class), anyString()))
				.thenReturn(new AudioProcessingGuardrailService.GuardrailResult(1, 32, "fingerprint", "audio"));
		return new MultiSourceJobService(mock(GeminiService.class), mock(QualityReportService.class),
				mock(SummaryService.class), mock(DeduplicationService.class), mock(SourceAttributionService.class),
				sourceFiles, mock(SourceTranscriptService.class), mock(DocumentTextExtractionService.class),
				mock(MergedSummaryRepository.class), mock(MultiSourceJobRepository.class), guardrails,
				tempDir.toString(), "500MB", mock(ConfirmedRabbitPublisher.class), mock(ValidationEventService.class),
				mock(TimedTranscriptSegmentService.class), mock(SemanticEvidenceService.class), governor,
				java.time.Duration.ofMinutes(35), java.time.Duration.ofSeconds(15), "test-worker");
	}

	private MultiSourceJobService service() throws Exception {
		return service(mock(AudioProcessingGuardrailService.class));
	}

	private MultiSourceJobService service(AudioProcessingGuardrailService guardrailService) throws Exception {
		return new MultiSourceJobService(mock(GeminiService.class), mock(QualityReportService.class),
				mock(SummaryService.class), mock(DeduplicationService.class), mock(SourceAttributionService.class),
				mock(SourceFileService.class), mock(SourceTranscriptService.class),
				mock(DocumentTextExtractionService.class), mock(MergedSummaryRepository.class),
				mock(MultiSourceJobRepository.class), guardrailService, tempDir.toString(), "500MB",
				mock(ConfirmedRabbitPublisher.class), mock(ValidationEventService.class),
				mock(TimedTranscriptSegmentService.class), mock(SemanticEvidenceService.class), governor(),
				java.time.Duration.ofMinutes(35), java.time.Duration.ofSeconds(15), "test-worker");
	}

	private MultiSourceJobService serviceWithDeduplication() throws Exception {
		return new MultiSourceJobService(mock(GeminiService.class), mock(QualityReportService.class),
				mock(SummaryService.class), new DeduplicationService(), mock(SourceAttributionService.class),
				mock(SourceFileService.class), mock(SourceTranscriptService.class),
				mock(DocumentTextExtractionService.class), mock(MergedSummaryRepository.class),
				mock(MultiSourceJobRepository.class), mock(AudioProcessingGuardrailService.class), tempDir.toString(),
				"500MB", mock(ConfirmedRabbitPublisher.class), mock(ValidationEventService.class),
				mock(TimedTranscriptSegmentService.class), mock(SemanticEvidenceService.class), governor(),
				java.time.Duration.ofMinutes(35), java.time.Duration.ofSeconds(15), "test-worker");
	}

	private GeminiRequestGovernor governor() {
		GeminiRequestGovernor governor = mock(GeminiRequestGovernor.class);
		when(governor.snapshot(anyString())).thenReturn(GeminiRequestGovernor.UsageSnapshot.empty());
		when(governor.snapshotAndClear(anyString())).thenReturn(GeminiRequestGovernor.UsageSnapshot.empty());
		return governor;
	}

	private MockMultipartFile media(String filename) {
		return new MockMultipartFile("mediaFiles", filename, "audio/mpeg", "audio".getBytes());
	}

	private MockMultipartFile document(String filename) {
		return new MockMultipartFile("documentFiles", filename, "application/pdf", "document".getBytes());
	}
}
