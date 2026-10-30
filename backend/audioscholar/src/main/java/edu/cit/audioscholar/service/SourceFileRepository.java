package edu.cit.audioscholar.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.SourceFile;

@Service
public class SourceFileRepository {
	private static final String COLLECTION_NAME = "sourceFiles";

	private final FirebaseService firebaseService;

	public SourceFileRepository(FirebaseService firebaseService) {
		this.firebaseService = firebaseService;
	}

	public SourceFile save(SourceFile sourceFile) {
		firebaseService.saveData(COLLECTION_NAME, sourceFile.getSourceFileId(), sourceFile.toMap());
		return sourceFile;
	}

	public void delete(String sourceFileId) {
		firebaseService.deleteData(COLLECTION_NAME, sourceFileId);
	}

	public List<Map<String, Object>> findByJobId(String jobId) {
		return firebaseService.queryCollection(COLLECTION_NAME, "jobId", jobId);
	}
}
