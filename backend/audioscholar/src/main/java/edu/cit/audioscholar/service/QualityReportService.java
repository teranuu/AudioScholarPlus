package edu.cit.audioscholar.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.QualityIssue;
import edu.cit.audioscholar.model.QualityReport;

@Service
public class QualityReportService {
	private static final long WINDOW_MS = 15_000;
	private final QualityReportRepository repository;
	private final QualityIssueDetector detector;
	private final ValidationEventService validationEvents;

	public QualityReportService(FirebaseService firebaseService, QualityIssueDetector detector) {
		this(new QualityReportRepository(firebaseService), detector, null);
	}
	public QualityReportService(QualityReportRepository repository, QualityIssueDetector detector) {
		this(repository, detector, null);
	}
	@Autowired
	public QualityReportService(QualityReportRepository repository, QualityIssueDetector detector,
			ValidationEventService validationEvents) {
		this.repository = repository;
		this.detector = detector;
		this.validationEvents = validationEvents;
	}

	public QualityReport analyzeAndSave(String recordingId, Path mediaPath) {
		return analyzeAndSave(recordingId, mediaPath, recordingId + ":1", null, recordingId, "SINGLE_SOURCE", "UNKNOWN",
				"audio_metadata");
	}
	public QualityReport analyzeAndSave(String recordingId, Path mediaPath, String workflowId, String workflowType,
			String clientSource, String workflowCollection) {
		return analyzeAndSave(recordingId, mediaPath, recordingId + ":1", null, workflowId, workflowType, clientSource,
				workflowCollection);
	}
	public QualityReport analyzeAndSave(String recordingId, Path mediaPath, String attemptId, String audioFingerprint,
			String workflowId, String workflowType, String clientSource, String workflowCollection) {
		QualityReport report = analyze(recordingId, mediaPath, attemptId, audioFingerprint);
		emit(workflowId, workflowType, clientSource, "QUALITY_ANALYSIS_COMPLETED", List.of(3, 4, 5, 6),
				report.getReportId(),
				Map.of("recordingId", recordingId, "attemptId", attemptId, "reportId", report.getReportId(), "status",
						report.getStatus(), "analysisStatus", report.getAnalysisStatus(), "issueCount",
						report.getIssues().size()),
				workflowCollection);
		try {
			save(report);
		} catch (Exception e) {
			throw new IllegalStateException("Quality report could not be persisted for " + recordingId, e);
		}
		emit(workflowId, workflowType, clientSource, "QUALITY_REPORT_PERSISTED", List.of(6), report.getReportId(),
				Map.of("recordingId", recordingId, "attemptId", attemptId, "reportId", report.getReportId(), "status",
						report.getStatus()),
				workflowCollection);
		for (QualityIssue issue : report.getIssues())
			emit(workflowId, workflowType, clientSource, "QUALITY_ISSUE_PERSISTED", List.of(3, 4, 5),
					issue.getIssueId(),
					Map.of("recordingId", recordingId, "attemptId", attemptId, "reportId", report.getReportId(),
							"issueId", issue.getIssueId(), "issueType", issue.getIssueType(), "startMs",
							issue.getStartMs(), "endMs", issue.getEndMs(), "detectorVersion",
							issue.getDetectorVersion(), "thresholdVersion", issue.getThresholdVersion()),
					workflowCollection);
		return report;
	}

	public QualityReport analyze(String recordingId, Path mediaPath) {
		return analyze(recordingId, mediaPath, recordingId + ":1", fingerprint(mediaPath));
	}
	public QualityReport analyze(String recordingId, Path mediaPath, String attemptId, String audioFingerprint) {
		QualityIssueDetector.AnalysisResult result = detector.analyze(mediaPath);
		QualityReport report = new QualityReport();
		report.setRecordingId(recordingId);
		report.setAttemptId(attemptId);
		report.setAudioFingerprint(audioFingerprint == null ? fingerprint(mediaPath) : audioFingerprint);
		report.setDetectorVersion(QualityIssueDetector.DETECTOR_VERSION);
		report.setThresholdVersion(QualityIssueDetector.THRESHOLD_VERSION);
		report.setAnalysisWindowMs(WINDOW_MS);
		report.setAnalysisStatus(result.analysisStatus());
		report.setAnalysisMode(result.analysisMode());
		report.setAnalyzedDurationMs(result.analyzedDurationMs());
		report.setTotalDurationMs(result.totalDurationMs());
		report.setCoverageRatio(result.coverageRatio());
		report.setFailureCode(result.failureCode());
		report.setFailureDetail(result.failureDetail());
		report.setReportId(digest(
				attemptId + report.getAudioFingerprint() + report.getDetectorVersion() + report.getThresholdVersion()));
		for (QualityIssue issue : result.issues())
			issue.setIssueId(digest(report.getReportId() + issue.getDetectorRuleId() + issue.getIssueType()
					+ issue.getStartMs() + issue.getEndMs()));
		report.setIssues(result.issues());
		if (!"COMPLETED".equals(result.analysisStatus()) || !"FULL_PCM".equals(result.analysisMode()))
			report.setStatus("UNAVAILABLE");
		else
			report.setStatus(result.issues().isEmpty() ? "ALL_CLEAR" : "ISSUES_DETECTED");
		return report;
	}

	public QualityReport getReport(String recordingId) throws ExecutionException, InterruptedException {
		Map<String, Object> data = repository.findByRecordingId(recordingId);
		return data == null ? null : QualityReport.fromMap(data);
	}
	public QualityReport generateReport(String recordingId) throws ExecutionException, InterruptedException {
		QualityReport existing = getReport(recordingId);
		if (existing != null)
			return existing;
		QualityReport report = analyze(recordingId, null);
		save(report);
		return report;
	}
	/**
	 * Compatibility method: an ALL_CLEAR report can no longer be created without
	 * completed PCM analysis.
	 */
	public QualityReport createAllClearReport(String recordingId) throws ExecutionException, InterruptedException {
		QualityReport report = analyze(recordingId, null);
		save(report);
		return report;
	}
	public void save(QualityReport report) throws ExecutionException, InterruptedException {
		repository.save(report);
	}

	private void emit(String workflowId, String workflowType, String clientSource, String type,
			List<Integer> objectives, String key, Map<String, ?> payload, String collection) {
		if (validationEvents != null)
			validationEvents.emit(workflowId, workflowType, clientSource, type, objectives, key, payload, collection);
	}
	private static String fingerprint(Path path) {
		if (path == null)
			return "unavailable";
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
		} catch (Exception e) {
			return "unavailable";
		}
	}
	private static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
