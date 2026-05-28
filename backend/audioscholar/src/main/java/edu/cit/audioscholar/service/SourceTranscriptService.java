package edu.cit.audioscholar.service;

import java.util.List;

import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.SourceFile;
import edu.cit.audioscholar.model.SourceTranscript;

@Service
public class SourceTranscriptService {
	public SourceTranscript getTranscript(SourceFile sourceFile) {
		return new SourceTranscript(sourceFile.getSourceFileId(), sourceFile.getSourceLabel(),
				sourceFile.getTranscriptText());
	}

	public List<SourceTranscript> getTranscriptsByJob(List<SourceFile> sourceFiles) {
		return sourceFiles.stream().map(this::getTranscript).toList();
	}
}
