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

	public StoredObject storeSourceFile(Path path, String originalFilename, String contentType) throws IOException {
		String objectId = nhostStorageService.uploadFile(path.toFile(), originalFilename, contentType);
		return new StoredObject(objectId, nhostStorageService.getPublicUrl(objectId));
	}

	public void retrieveSourceFile(String objectId, Path targetPath) throws IOException {
		nhostStorageService.downloadFileToPath(objectId, targetPath);
	}

	public record StoredObject(String objectId, String compatibilityUrl) {
	}
}
