package edu.cit.audioscholar.dto;

public class OutputTypeResponseDTO {
	private String recordingId;
	private String outputType;
	private String message;
	private String status;

	public OutputTypeResponseDTO() {
	}

	public OutputTypeResponseDTO(String recordingId, String outputType, String message, String status) {
		this.recordingId = recordingId;
		this.outputType = outputType;
		this.message = message;
		this.status = status;
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

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}
}
