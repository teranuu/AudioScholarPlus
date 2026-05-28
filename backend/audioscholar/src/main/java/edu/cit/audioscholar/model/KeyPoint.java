package edu.cit.audioscholar.model;

import java.util.UUID;

public class KeyPoint {
	private String keyPointId = UUID.randomUUID().toString();
	private String text;
	private String sourceLabel;

	public KeyPoint() {
	}

	public KeyPoint(String text, String sourceLabel) {
		this.text = text;
		this.sourceLabel = sourceLabel;
	}

	public String getKeyPointId() {
		return keyPointId;
	}

	public void setKeyPointId(String keyPointId) {
		this.keyPointId = keyPointId;
	}

	public String getText() {
		return text;
	}

	public void setText(String text) {
		this.text = text;
	}

	public String getSourceLabel() {
		return sourceLabel;
	}

	public void setSourceLabel(String sourceLabel) {
		this.sourceLabel = sourceLabel;
	}
}
