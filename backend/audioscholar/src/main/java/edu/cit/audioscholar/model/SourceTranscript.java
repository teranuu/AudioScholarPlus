package edu.cit.audioscholar.model;

public class SourceTranscript {
	private String sourceFileId;
	private String sourceLabel;
	private String transcriptText;

	public SourceTranscript() {
	}

	public SourceTranscript(String sourceFileId, String sourceLabel, String transcriptText) {
		this.sourceFileId = sourceFileId;
		this.sourceLabel = sourceLabel;
		this.transcriptText = transcriptText;
	}

	public String getSourceFileId() {
		return sourceFileId;
	}

	public void setSourceFileId(String sourceFileId) {
		this.sourceFileId = sourceFileId;
	}

	public String getSourceLabel() {
		return sourceLabel;
	}

	public void setSourceLabel(String sourceLabel) {
		this.sourceLabel = sourceLabel;
	}

	public String getTranscriptText() {
		return transcriptText;
	}

	public void setTranscriptText(String transcriptText) {
		this.transcriptText = transcriptText;
	}
}
