package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

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

	private MultiSourceJobService service() throws Exception {
		return new MultiSourceJobService(mock(GeminiService.class), mock(QualityReportService.class),
				mock(SummaryService.class), mock(DeduplicationService.class), mock(SourceAttributionService.class),
				mock(SourceFileService.class), mock(SourceTranscriptService.class),
				mock(DocumentTextExtractionService.class), mock(MergedSummaryRepository.class),
				mock(MultiSourceJobRepository.class), tempDir.toString(), "500MB");
	}

	private MockMultipartFile media(String filename) {
		return new MockMultipartFile("mediaFiles", filename, "audio/mpeg", "audio".getBytes());
	}

	private MockMultipartFile document(String filename) {
		return new MockMultipartFile("documentFiles", filename, "application/pdf", "document".getBytes());
	}
}
