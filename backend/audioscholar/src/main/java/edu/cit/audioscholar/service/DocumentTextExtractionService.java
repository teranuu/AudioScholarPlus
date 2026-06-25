package edu.cit.audioscholar.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DocumentTextExtractionService {

	public String extractText(Path filePath, String originalFilename, String contentType) throws IOException {
		String extension = extensionOf(originalFilename);
		String text;
		if ("pdf".equals(extension) || matches(contentType, "application/pdf")) {
			text = extractPdf(filePath);
		} else if (isOfficeDocument(extension, contentType)) {
			text = extractOfficeDocument(filePath);
		} else {
			throw new IllegalArgumentException("Unsupported document source: " + originalFilename);
		}
		if (!StringUtils.hasText(text)) {
			throw new IllegalArgumentException("No readable text found in document source: " + originalFilename);
		}
		return text.trim();
	}

	private String extractPdf(Path filePath) throws IOException {
		try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
			return new PDFTextStripper().getText(document);
		}
	}

	private String extractOfficeDocument(Path filePath) throws IOException {
		try (InputStream input = Files.newInputStream(filePath);
				POITextExtractor extractor = ExtractorFactory.createExtractor(input)) {
			return extractor.getText();
		} catch (Exception e) {
			throw new IOException("Could not extract text from Office document: " + filePath.getFileName(), e);
		}
	}

	private boolean isOfficeDocument(String extension, String contentType) {
		return switch (extension) {
			case "ppt", "pptx", "doc", "docx" -> true;
			default ->
				contentType != null && (contentType.contains("powerpoint") || contentType.contains("presentation")
						|| contentType.contains("msword") || contentType.contains("wordprocessingml"));
		};
	}

	private boolean matches(String actual, String expected) {
		return actual != null && expected.equalsIgnoreCase(actual);
	}

	private String extensionOf(String filename) {
		String extension = StringUtils.getFilenameExtension(filename != null ? filename : "");
		return extension != null ? extension.toLowerCase() : "";
	}
}
