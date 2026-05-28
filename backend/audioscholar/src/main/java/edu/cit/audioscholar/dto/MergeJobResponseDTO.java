package edu.cit.audioscholar.dto;

public class MergeJobResponseDTO {
	private String jobId;
	private String status;
	private String message;

	public MergeJobResponseDTO() {
	}

	public MergeJobResponseDTO(String jobId, String status, String message) {
		this.jobId = jobId;
		this.status = status;
		this.message = message;
	}

	public String getJobId() {
		return jobId;
	}

	public String getStatus() {
		return status;
	}

	public String getMessage() {
		return message;
	}
}
