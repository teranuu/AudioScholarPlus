package edu.cit.audioscholar.service;

import java.util.List;

import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.SourceFile;
import edu.cit.audioscholar.model.SourceTranscript;

@Service
public class SourceTranscriptService {
	private static final String COLLECTION_NAME = "sourceTranscripts";

	private final FirebaseService firebaseService;

	public SourceTranscriptService(FirebaseService firebaseService) {
		this.firebaseService = firebaseService;
	}

	public SourceTranscript getTranscript(SourceFile sourceFile) {
		return new SourceTranscript(sourceFile.getSourceFileId(), sourceFile.getSourceLabel(),
				sourceFile.getTranscriptText());
	}

	public SourceTranscript saveTranscript(String jobId, SourceFile sourceFile) {
		SourceTranscript transcript = getTranscript(sourceFile);
		transcript.setJobId(jobId);
		transcript.setStatus("COMPLETE");
		firebaseService.saveData(COLLECTION_NAME, transcript.getTranscriptId(), transcript.toMap());
		return transcript;
	}

	public List<SourceTranscript> getTranscriptsByJob(List<SourceFile> sourceFiles) {
		return sourceFiles.stream().map(this::getTranscript).toList();
	}
}
