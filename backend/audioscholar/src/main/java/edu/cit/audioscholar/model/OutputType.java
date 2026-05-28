package edu.cit.audioscholar.model;

public enum OutputType {
	NOTES, STUDY_MATERIAL, REVIEW_MATERIAL;

	public static OutputType fromValue(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Output type is required.");
		}
		String normalized = value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
		return OutputType.valueOf(normalized);
	}

	public String displayName() {
		return switch (this) {
			case NOTES -> "Notes";
			case STUDY_MATERIAL -> "Study Material";
			case REVIEW_MATERIAL -> "Review Material";
		};
	}
}
