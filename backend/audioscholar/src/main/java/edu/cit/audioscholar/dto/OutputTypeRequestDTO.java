package edu.cit.audioscholar.dto;

public class OutputTypeRequestDTO {
	private String recordingId;
	private String outputType;

	public OutputTypeRequestDTO() {
	}

	public OutputTypeRequestDTO(String recordingId, String outputType) {
		this.recordingId = recordingId;
		this.outputType = outputType;
	}

	public String getRecordingId() {
		return recordingId;
	}

	public void setRecordingId(String recordingId) {
		this.recordingId = recordingId;
	}

	public String getOutputType() {
		return outputType;
	}

	public void setOutputType(String outputType) {
		this.outputType = outputType;
	}
}
