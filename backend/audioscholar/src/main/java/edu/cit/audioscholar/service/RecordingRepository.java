package edu.cit.audioscholar.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.Recording;

@Service
public class RecordingRepository {
	private static final String COLLECTION_NAME = "recordings";

	private final FirebaseService firebaseService;

	public RecordingRepository(FirebaseService firebaseService) {
		this.firebaseService = firebaseService;
	}

	public Recording save(Recording recording) {
		firebaseService.saveData(COLLECTION_NAME, recording.getRecordingId(), recording.toMap());
		return recording;
	}

	public Recording update(Recording recording) {
		firebaseService.updateData(COLLECTION_NAME, recording.getRecordingId(), recording.toMap());
		return recording;
	}

	public Map<String, Object> findById(String recordingId) {
		return firebaseService.getData(COLLECTION_NAME, recordingId);
	}

	public List<Map<String, Object>> findByUserId(String userId) {
		return firebaseService.queryCollection(COLLECTION_NAME, "userId", userId);
	}

	public void delete(String recordingId) {
		firebaseService.deleteData(COLLECTION_NAME, recordingId);
	}
}
