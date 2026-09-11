package edu.cit.audioscholar.service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import edu.cit.audioscholar.model.AudioMetadata;

@Service
@ConditionalOnProperty(name = "pptx.conversion.provider", havingValue = "local", matchIfMissing = true)
public class LocalPptxConversionProvider implements PptxConversionProvider {

	private static final Logger log = LoggerFactory.getLogger(LocalPptxConversionProvider.class);
	private static final String PDF_CONTENT_TYPE = "application/pdf";

	private final NhostStorageService nhostStorageService;
	private final PptxToPdfConverter pptxToPdfConverter;
	private final Path tempDir;

	public LocalPptxConversionProvider(NhostStorageService nhostStorageService, PptxToPdfConverter pptxToPdfConverter,
			@Value("${app.temp-file-dir}") String tempDirStr) throws Exception {
		this.nhostStorageService = nhostStorageService;
		this.pptxToPdfConverter = pptxToPdfConverter;
		this.tempDir = Path.of(tempDirStr);
		Files.createDirectories(this.tempDir);
	}

	@Override
	public PptxConversionResult convert(AudioMetadata metadata) throws Exception {
		if (metadata == null || !StringUtils.hasText(metadata.getNhostPptxFileId())) {
			throw new IllegalArgumentException("No durable PPTX file ID is available for conversion.");
		}

		String metadataId = metadata.getId();
		Path pptxPath = Files.createTempFile(tempDir, metadataId + "-source-", ".pptx");
		Path pdfPath = Files.createTempFile(tempDir, metadataId + "-converted-", ".pdf");

		try {
			log.info("[{}] Downloading PPTX from Nhost for local PDF conversion", metadataId);
			nhostStorageService.downloadFileToPath(metadata.getNhostPptxFileId(), pptxPath);

			log.info("[{}] Converting PPTX to PDF with local Apache POI/PDFBox provider", metadataId);
			try (InputStream pptxInput = Files.newInputStream(pptxPath);
					OutputStream pdfOutput = Files.newOutputStream(pdfPath)) {
				pptxToPdfConverter.convert(pptxInput, pdfOutput);
			}

			String pdfFilename = buildPdfFilename(metadata);
			String pdfFileId = nhostStorageService.uploadFile(pdfPath.toFile(), pdfFilename, PDF_CONTENT_TYPE);
			String pdfUrl = nhostStorageService.getPublicUrl(pdfFileId);
			log.info("[{}] Uploaded locally converted PDF to Nhost. File ID: {}", metadataId, pdfFileId);

			return new PptxConversionResult(pdfFileId, pdfUrl, "local-poi-pdfbox");
		} finally {
			deleteTempFile(pptxPath, metadataId);
			deleteTempFile(pdfPath, metadataId);
		}
	}

	private String buildPdfFilename(AudioMetadata metadata) {
		String sourceName = StringUtils.hasText(metadata.getOriginalPptxFileName())
				? metadata.getOriginalPptxFileName()
				: metadata.getId();
		String baseName = StringUtils.stripFilenameExtension(sourceName);
		if (!StringUtils.hasText(baseName)) {
			baseName = metadata.getId();
		}
		return baseName + ".pdf";
	}

	private void deleteTempFile(Path path, String metadataId) {
		try {
			Files.deleteIfExists(path);
		} catch (Exception e) {
			log.warn("[{}] Failed to delete temporary conversion file {}: {}", metadataId, path.getFileName(),
					e.getMessage());
		}
	}
}
