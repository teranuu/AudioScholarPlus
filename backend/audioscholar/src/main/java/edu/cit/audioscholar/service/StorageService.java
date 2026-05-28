package edu.cit.audioscholar.service;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.stereotype.Service;

@Service
public class StorageService {
	private final NhostStorageService nhostStorageService;

	public StorageService(NhostStorageService nhostStorageService) {
		this.nhostStorageService = nhostStorageService;
	}

	public String storeSourceFile(Path path, String originalFilename, String contentType) throws IOException {
		String fileId = nhostStorageService.uploadFile(path.toFile(), originalFilename, contentType);
		return nhostStorageService.getPublicUrl(fileId);
	}
}
