package edu.cit.audioscholar.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import edu.cit.audioscholar.model.AudioMetadata;

@Service
@ConditionalOnProperty(name = "pptx.conversion.provider", havingValue = "libreoffice")
public class LibreOfficePptxConversionProvider implements PptxConversionProvider {

	private static final Logger log = LoggerFactory.getLogger(LibreOfficePptxConversionProvider.class);
	private static final String PDF_CONTENT_TYPE = "application/pdf";

	private final NhostStorageService nhostStorageService;
	private final MediaCommandRunner commandRunner;
	private final Path tempDir;
	private final String libreOfficeCommand;
	private final Duration timeout;

	public LibreOfficePptxConversionProvider(NhostStorageService nhostStorageService, MediaCommandRunner commandRunner,
			@Value("${app.temp-file-dir}") String tempDirStr,
			@Value("${pptx.conversion.libreoffice-command:soffice}") String libreOfficeCommand,
			@Value("${pptx.conversion.timeout:2m}") Duration timeout) throws Exception {
		this.nhostStorageService = nhostStorageService;
		this.commandRunner = commandRunner;
		this.tempDir = Path.of(tempDirStr);
		this.libreOfficeCommand = libreOfficeCommand;
		this.timeout = timeout;
		Files.createDirectories(this.tempDir);
	}

	@Override
	public PptxConversionResult convert(AudioMetadata metadata) throws Exception {
		if (metadata == null || !StringUtils.hasText(metadata.getNhostPptxFileId())) {
			throw new IllegalArgumentException("No durable PPTX file ID is available for conversion.");
		}

		String metadataId = metadata.getId();
		Path pptxPath = Files.createTempFile(tempDir, metadataId + "-source-", ".pptx");
		Path pdfPath = null;

		try {
			log.info("[{}] Downloading PPTX from Nhost for LibreOffice PDF conversion", metadataId);
			nhostStorageService.downloadFileToPath(metadata.getNhostPptxFileId(), pptxPath);

			List<String> command = List.of(libreOfficeCommand, "--headless", "--convert-to", "pdf", "--outdir",
					tempDir.toAbsolutePath().toString(), pptxPath.toAbsolutePath().toString());
			MediaProcessResult result = commandRunner.run(command, timeout);
			if (result.exitCode() != 0) {
				throw new IllegalStateException("LibreOffice conversion failed: " + result.stderr());
			}

			pdfPath = tempDir.resolve(StringUtils.stripFilenameExtension(pptxPath.getFileName().toString()) + ".pdf");
			if (!Files.exists(pdfPath)) {
				throw new IllegalStateException("LibreOffice conversion completed but no PDF was produced.");
			}

			String pdfFilename = buildPdfFilename(metadata);
			String pdfFileId = nhostStorageService.uploadFile(pdfPath.toFile(), pdfFilename, PDF_CONTENT_TYPE);
			String pdfUrl = nhostStorageService.getPublicUrl(pdfFileId);
			log.info("[{}] Uploaded LibreOffice-converted PDF to Nhost. File ID: {}", metadataId, pdfFileId);

			return new PptxConversionResult(pdfFileId, pdfUrl, "libreoffice");
		} finally {
			deleteTempFile(pptxPath, metadataId);
			if (pdfPath != null) {
				deleteTempFile(pdfPath, metadataId);
			}
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
			log.warn("[{}] Failed to delete temporary LibreOffice conversion file {}: {}", metadataId,
					path.getFileName(), e.getMessage());
		}
	}
}
