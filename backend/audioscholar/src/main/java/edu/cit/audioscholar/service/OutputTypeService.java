package edu.cit.audioscholar.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.google.cloud.Timestamp;

import edu.cit.audioscholar.dto.OutputTypeResponseDTO;
import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.OutputType;
import edu.cit.audioscholar.model.Recording;

@Service
public class OutputTypeService {
	private final RecordingService recordingService;
	private final FirebaseService firebaseService;

	public OutputTypeService(RecordingService recordingService, FirebaseService firebaseService) {
		this.recordingService = recordingService;
		this.firebaseService = firebaseService;
	}

	public boolean validateOutputType(String outputType) {
		try {
			OutputType.fromValue(outputType);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	public Recording assignOutputType(String recordingId, String outputType) throws Exception {
		if (!StringUtils.hasText(recordingId)) {
			throw new IllegalArgumentException("Recording ID is required.");
		}
		OutputType selectedType = OutputType.fromValue(outputType);
		Recording recording = recordingService.getRecordingById(recordingId);
		if (recording == null) {
			throw new IllegalArgumentException("Recording not found.");
		}

		recording.setOutputType(selectedType.name());
		recordingService.updateRecording(recording);
		syncAudioMetadata(recordingId, selectedType.name());
		return recording;
	}

	public OutputTypeResponseDTO saveOutputType(String recordingId, String outputType) throws Exception {
		Recording recording = assignOutputType(recordingId, outputType);
		return new OutputTypeResponseDTO(recording.getRecordingId(), recording.getOutputType(),
				"Output type saved successfully.", "OK");
	}

	private void syncAudioMetadata(String recordingId, String outputType) {
		try {
			AudioMetadata metadata = firebaseService.getAudioMetadataByRecordingId(recordingId);
			if (metadata == null || !StringUtils.hasText(metadata.getId())) {
				return;
			}
			Map<String, Object> updates = new HashMap<>();
			updates.put("outputType", outputType);
			updates.put("lastUpdated", Timestamp.now());
			firebaseService.updateData(firebaseService.getAudioMetadataCollectionName(), metadata.getId(), updates);
		} catch (Exception ignored) {
			// Recording is the authoritative record for this explicit selection endpoint.
		}
	}
}
