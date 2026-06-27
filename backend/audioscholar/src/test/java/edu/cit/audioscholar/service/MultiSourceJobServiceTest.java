package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import edu.cit.audioscholar.model.MultiSourceJob;
import edu.cit.audioscholar.model.Summary;

class MultiSourceJobServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void rejectsFewerThanTwoMediaFilesEvenWithDocuments() throws Exception {
		MultiSourceJobService service = service();

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> service.createAndProcess("user-1", List.of(media("a.mp3")), List.of(document("slides.pdf")),
						"Title", null, "NOTES"));

		assertTrue(exception.getMessage().contains("at least two audio or video sources"));
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

	private MultiSourceJobService service() throws Exception {
		return service(mock(AudioProcessingGuardrailService.class));
	}

	private MultiSourceJobService service(AudioProcessingGuardrailService guardrailService) throws Exception {
		return new MultiSourceJobService(mock(GeminiService.class), mock(QualityReportService.class),
				mock(SummaryService.class), mock(DeduplicationService.class), mock(SourceAttributionService.class),
				mock(SourceFileService.class), mock(SourceTranscriptService.class),
				mock(DocumentTextExtractionService.class), mock(MergedSummaryRepository.class),
				mock(MultiSourceJobRepository.class), guardrailService, tempDir.toString(), "500MB");
	}

	private MultiSourceJobService serviceWithDeduplication() throws Exception {
		return new MultiSourceJobService(mock(GeminiService.class), mock(QualityReportService.class),
				mock(SummaryService.class), new DeduplicationService(), mock(SourceAttributionService.class),
				mock(SourceFileService.class), mock(SourceTranscriptService.class),
				mock(DocumentTextExtractionService.class), mock(MergedSummaryRepository.class),
				mock(MultiSourceJobRepository.class), mock(AudioProcessingGuardrailService.class), tempDir.toString(),
				"500MB");
	}

	private MockMultipartFile media(String filename) {
		return new MockMultipartFile("mediaFiles", filename, "audio/mpeg", "audio".getBytes());
	}

	private MockMultipartFile document(String filename) {
		return new MockMultipartFile("documentFiles", filename, "application/pdf", "document".getBytes());
	}
}
