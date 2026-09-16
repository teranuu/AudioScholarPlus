package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import edu.cit.audioscholar.model.AudioMetadata;

class LocalPptxConversionProviderTest {

	@TempDir
	Path tempDir;

	@Test
	void convert_downloadsPptxConvertsAndUploadsPdfToNhost() throws Exception {
		NhostStorageService nhostStorageService = org.mockito.Mockito.mock(NhostStorageService.class);
		PptxToPdfConverter pptxToPdfConverter = org.mockito.Mockito.mock(PptxToPdfConverter.class);
		LocalPptxConversionProvider provider = new LocalPptxConversionProvider(nhostStorageService, pptxToPdfConverter,
				tempDir.toString());

		AudioMetadata metadata = new AudioMetadata();
		metadata.setId("metadata-1");
		metadata.setNhostPptxFileId("pptx-file-id");
		metadata.setOriginalPptxFileName("lecture-slides.pptx");

		doAnswer(invocation -> {
			Path targetPath = invocation.getArgument(1);
			Files.writeString(targetPath, "pptx bytes");
			return null;
		}).when(nhostStorageService).downloadFileToPath(eq("pptx-file-id"), any(Path.class));
		doAnswer(invocation -> {
			OutputStream outputStream = invocation.getArgument(1);
			outputStream.write("pdf bytes".getBytes());
			return null;
		}).when(pptxToPdfConverter).convert(any(), any());
		when(nhostStorageService.uploadFile(any(File.class), eq("lecture-slides.pdf"), eq("application/pdf")))
				.thenReturn("pdf-file-id");
		when(nhostStorageService.getPublicUrl("pdf-file-id")).thenReturn("https://storage.test/v1/files/pdf-file-id");

		PptxConversionResult result = provider.convert(metadata);

		assertEquals("pdf-file-id", result.pdfFileId());
		assertEquals("https://storage.test/v1/files/pdf-file-id", result.pdfUrl());
		assertEquals("local-poi-pdfbox", result.providerName());

		ArgumentCaptor<File> uploadedFile = ArgumentCaptor.forClass(File.class);
		verify(nhostStorageService).uploadFile(uploadedFile.capture(), eq("lecture-slides.pdf"), eq("application/pdf"));
		assertTrue(uploadedFile.getValue().getName().endsWith(".pdf"));
	}
}
