package edu.cit.audioscholar.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.QualityReport;

@Service
public class QualityReportRepository {
	private static final String COLLECTION_NAME = "qualityReports";

	private final FirebaseService firebaseService;

	public QualityReportRepository(FirebaseService firebaseService) {
		this.firebaseService = firebaseService;
	}

	public QualityReport save(QualityReport report) {
		firebaseService.saveData(COLLECTION_NAME, report.getReportId(), report.toMap());
		return report;
	}

	public Map<String, Object> findByRecordingId(String recordingId) {
		List<Map<String, Object>> results = firebaseService.queryCollection(COLLECTION_NAME, "recordingId",
				recordingId);
		return results.isEmpty()
				? null
				: results.stream()
						.max(java.util.Comparator.comparingInt(this::attemptNumber)
								.thenComparing(r -> String.valueOf(r.getOrDefault("attemptId", ""))))
						.orElse(results.get(0));
	}

	private int attemptNumber(Map<String, Object> report) {
		String attemptId = String.valueOf(report.getOrDefault("attemptId", ""));
		int separator = attemptId.lastIndexOf(':');
		if (separator < 0 || separator == attemptId.length() - 1)
			return 0;
		try {
			return Integer.parseInt(attemptId.substring(separator + 1));
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}
}
