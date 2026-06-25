package edu.cit.audioscholar.service;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import edu.cit.audioscholar.model.SourceFile;
import edu.cit.audioscholar.model.SourceKind;

@Service
public class SourceFileService {
	private final StorageService storageService;
	private final SourceFileRepository sourceFileRepository;

	public SourceFileService(StorageService storageService, SourceFileRepository sourceFileRepository) {
		this.storageService = storageService;
		this.sourceFileRepository = sourceFileRepository;
	}

	public SourceFile createSourceFile(String jobId, String sourceLabel, SourceKind sourceKind, MultipartFile file,
			Path localPath) throws IOException {
		SourceFile sourceFile = new SourceFile();
		sourceFile.setJobId(jobId);
		sourceFile.setSourceLabel(sourceLabel);
		sourceFile.setSourceKind(sourceKind.name());
		sourceFile.setFileName(file.getOriginalFilename());
		sourceFile.setContentType(file.getContentType());
		sourceFile.setFileType(file.getContentType());
		sourceFile.setFileSize(file.getSize());
		sourceFile.setUploadStatus("UPLOADING");
		sourceFile.setFileUrl(
				storageService.storeSourceFile(localPath, file.getOriginalFilename(), file.getContentType()));
		sourceFile.setUploadStatus("UPLOADED");
		return sourceFile;
	}

	public SourceFile save(SourceFile sourceFile) {
		return sourceFileRepository.save(sourceFile);
	}
}
