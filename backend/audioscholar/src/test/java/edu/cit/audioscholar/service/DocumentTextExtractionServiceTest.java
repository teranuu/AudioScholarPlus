package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentTextExtractionServiceTest {
	private final DocumentTextExtractionService service = new DocumentTextExtractionService();

	@TempDir
	Path tempDir;

	@Test
	void extractsPdfText() throws Exception {
		Path file = tempDir.resolve("lecture.pdf");
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(50, 700);
				content.showText("Photosynthesis lecture notes");
				content.endText();
			}
			document.save(file.toFile());
		}

		String text = service.extractText(file, "lecture.pdf", "application/pdf");

		assertTrue(text.contains("Photosynthesis lecture notes"));
	}

	@Test
	void extractsDocxText() throws Exception {
		Path file = tempDir.resolve("lecture.docx");
		try (XWPFDocument document = new XWPFDocument(); OutputStream output = Files.newOutputStream(file)) {
			document.createParagraph().createRun().setText("Cell biology document source");
			document.write(output);
		}

		String text = service.extractText(file, "lecture.docx",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document");

		assertTrue(text.contains("Cell biology document source"));
	}

	@Test
	void extractsPptxText() throws Exception {
		Path file = tempDir.resolve("lecture.pptx");
		try (XMLSlideShow presentation = new XMLSlideShow(); OutputStream output = Files.newOutputStream(file)) {
			XSLFSlide slide = presentation.createSlide();
			XSLFTextBox textBox = slide.createTextBox();
			textBox.setText("Quantum mechanics slide source");
			presentation.write(output);
		}

		String text = service.extractText(file, "lecture.pptx",
				"application/vnd.openxmlformats-officedocument.presentationml.presentation");

		assertTrue(text.contains("Quantum mechanics slide source"));
	}

	@Test
	void rejectsEmptyReadableText() throws Exception {
		Path file = tempDir.resolve("empty.pdf");
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			document.save(file.toFile());
		}

		assertThrows(IllegalArgumentException.class, () -> service.extractText(file, "empty.pdf", "application/pdf"));
	}
}
