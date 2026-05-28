package edu.cit.audioscholar.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.Summary;
import edu.cit.audioscholar.model.SummaryKeyPoint;

@Service
public class SummaryRepository {
	private static final String COLLECTION_NAME = "summaries";
	private static final String SUMMARY_KEY_POINTS_COLLECTION = "summaryKeyPoints";

	private final FirebaseService firebaseService;

	public SummaryRepository(FirebaseService firebaseService) {
		this.firebaseService = firebaseService;
	}

	public Summary save(Summary summary) {
		firebaseService.saveData(COLLECTION_NAME, summary.getSummaryId(), summary);
		return summary;
	}

	public Summary update(Summary summary) {
		firebaseService.updateData(COLLECTION_NAME, summary.getSummaryId(), summary);
		return summary;
	}

	public Map<String, Object> findById(String summaryId) {
		return firebaseService.getData(COLLECTION_NAME, summaryId);
	}

	public List<Map<String, Object>> findByRecordingId(String recordingId) {
		return firebaseService.queryCollection(COLLECTION_NAME, "recordingId", recordingId);
	}

	public SummaryKeyPoint saveKeyPoint(SummaryKeyPoint keyPoint) {
		firebaseService.saveData(SUMMARY_KEY_POINTS_COLLECTION, keyPoint.getKeyPointId(), keyPoint.toMap());
		return keyPoint;
	}

	public void delete(String summaryId) {
		firebaseService.deleteData(COLLECTION_NAME, summaryId);
	}
}
