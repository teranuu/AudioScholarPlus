package edu.cit.audioscholar.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.MergedSummary;

@Service
public class MergedSummaryRepository {
	private static final String COLLECTION_NAME = "mergedSummaries";

	private final FirebaseService firebaseService;

	public MergedSummaryRepository(FirebaseService firebaseService) {
		this.firebaseService = firebaseService;
	}

	public MergedSummary save(MergedSummary mergedSummary) {
		firebaseService.saveData(COLLECTION_NAME, mergedSummary.getMergedSummaryId(), mergedSummary.toMap());
		return mergedSummary;
	}

	public Map<String, Object> getByJobId(String jobId) {
		List<Map<String, Object>> results = firebaseService.queryCollection(COLLECTION_NAME, "jobId", jobId);
		return results.isEmpty() ? null : results.get(0);
	}
}
