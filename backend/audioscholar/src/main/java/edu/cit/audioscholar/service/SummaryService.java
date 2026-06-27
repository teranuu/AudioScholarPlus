package edu.cit.audioscholar.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import edu.cit.audioscholar.model.Recording;
import edu.cit.audioscholar.model.Summary;

@Service
public class SummaryService {

	private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

	private final SummaryRepository summaryRepository;
	private final RecordingService recordingService;

	public SummaryService(SummaryRepository summaryRepository, RecordingService recordingService) {
		this.summaryRepository = summaryRepository;
		this.recordingService = recordingService;
	}

	public Summary createSummary(Summary summary) throws ExecutionException, InterruptedException {
		if (summary.getSummaryId() == null) {
			summary.setSummaryId(UUID.randomUUID().toString());
			log.debug("Generated new summaryId: {}", summary.getSummaryId());
		}

		log.info("Attempting to save Summary object (ID: {}) using POJO method.", summary.getSummaryId());
		if (summary.getStatus() == null) {
			summary.setStatus("COMPLETE");
		}
		populateMissingKeyPointTimestamps(summary);
		attachSummaryIdToFlashcards(summary);
		summary.setUpdatedAt(new java.util.Date());
		saveSummaryKeyPoints(summary);
		summaryRepository.save(summary);
		log.info("Firestore saveData call completed for summary ID: {}", summary.getSummaryId());

		try {
			Recording recording = recordingService.getRecordingById(summary.getRecordingId());
			if (recording != null) {
				if (!summary.getSummaryId().equals(recording.getSummaryId())) {
					log.info("Linking summary ID {} to recording ID {}", summary.getSummaryId(),
							recording.getRecordingId());
					recording.setSummaryId(summary.getSummaryId());
					recordingService.updateRecording(recording);
				} else {
					log.debug("Summary ID {} already linked to recording ID {}", summary.getSummaryId(),
							recording.getRecordingId());
				}
			} else {
				log.warn("Could not find Recording with ID {} to link summary {}.", summary.getRecordingId(),
						summary.getSummaryId());
			}
		} catch (ExecutionException | InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error("Error retrieving or updating recording {} while linking summary {}: {}",
					summary.getRecordingId(), summary.getSummaryId(), e.getMessage(), e);
		} catch (Exception e) {
			log.error("Unexpected error retrieving or updating recording {} while linking summary {}: {}",
					summary.getRecordingId(), summary.getSummaryId(), e.getMessage(), e);
		}

		return summary;
	}

	public Summary getSummaryById(String summaryId) throws ExecutionException, InterruptedException {
		log.debug("Fetching summary by ID: {}", summaryId);
		Map<String, Object> data = summaryRepository.findById(summaryId);
		if (data == null) {
			log.warn("Summary not found for ID: {}", summaryId);
			return null;
		}
		Summary summary = Summary.fromMap(data);
		log.debug("Successfully fetched and mapped summary ID: {}", summaryId);
		return summary;
	}

	public Summary getSummaryByRecordingId(String recordingId) throws ExecutionException, InterruptedException {
		log.debug("Fetching summary by recordingId: {}", recordingId);
		List<Map<String, Object>> results = summaryRepository.findByRecordingId(recordingId);
		if (results.isEmpty()) {
			log.warn("No summary found for recordingId: {}", recordingId);
			return null;
		}
		if (results.size() > 1) {
			log.warn("Multiple summaries found for recordingId: {}. Returning the first one.", recordingId);
		}
		Summary summary = Summary.fromMap(results.get(0));
		log.debug("Successfully fetched and mapped summary for recordingId: {}", recordingId);
		return summary;
	}

	public Summary updateSummary(Summary summary) throws ExecutionException, InterruptedException {
		if (summary == null || summary.getSummaryId() == null) {
			throw new IllegalArgumentException("Summary object and its ID cannot be null for update.");
		}
		log.info("Attempting to update Summary object (ID: {}) using POJO merge method.", summary.getSummaryId());
		if (summary.getStatus() == null) {
			summary.setStatus("COMPLETE");
		}
		populateMissingKeyPointTimestamps(summary);
		attachSummaryIdToFlashcards(summary);
		summary.setUpdatedAt(new java.util.Date());
		saveSummaryKeyPoints(summary);
		summaryRepository.update(summary);
		log.info("Firestore updateData call completed for summary ID: {}", summary.getSummaryId());
		return summary;
	}

	private void saveSummaryKeyPoints(Summary summary) {
		if (summary == null || summary.getSummaryId() == null) {
			return;
		}
		List<edu.cit.audioscholar.model.SummaryKeyPoint> keyPoints = summary.getSummaryKeyPoints();
		if ((keyPoints == null || keyPoints.isEmpty()) && summary.getKeyPoints() != null) {
			keyPoints = new java.util.ArrayList<>();
			for (String text : summary.getKeyPoints()) {
				edu.cit.audioscholar.model.SummaryKeyPoint keyPoint = new edu.cit.audioscholar.model.SummaryKeyPoint();
				keyPoint.setSummaryId(summary.getSummaryId());
				keyPoint.setText(text);
				keyPoints.add(keyPoint);
			}
			summary.setSummaryKeyPoints(keyPoints);
		}
		populateMissingKeyPointTimestamps(summary);
		if (keyPoints == null) {
			return;
		}
		for (edu.cit.audioscholar.model.SummaryKeyPoint keyPoint : keyPoints) {
			if (keyPoint == null || keyPoint.getKeyPointId() == null) {
				continue;
			}
			keyPoint.setSummaryId(summary.getSummaryId());
			summaryRepository.saveKeyPoint(keyPoint);
		}
	}

	private void populateMissingKeyPointTimestamps(Summary summary) {
		if (summary == null || summary.getSummaryKeyPoints() == null || summary.getSummaryKeyPoints().isEmpty()) {
			return;
		}
		List<edu.cit.audioscholar.model.QualityIssue> issues = summary.getQualityReport() != null
				? summary.getQualityReport().getIssues()
				: List.of();
		for (int i = 0; i < summary.getSummaryKeyPoints().size(); i++) {
			edu.cit.audioscholar.model.SummaryKeyPoint keyPoint = summary.getSummaryKeyPoints().get(i);
			if (keyPoint == null || keyPoint.getSourceStartTime() != null || keyPoint.getSourceEndTime() != null) {
				continue;
			}
			if (issues != null && !issues.isEmpty()) {
				edu.cit.audioscholar.model.QualityIssue issue = issues.get(Math.min(i, issues.size() - 1));
				keyPoint.setSourceStartTime(issue.getStartTime());
				keyPoint.setSourceEndTime(issue.getEndTime());
			} else {
				keyPoint.setSourceStartTime("00:00");
				keyPoint.setSourceEndTime("00:00");
			}
		}
	}

	private void attachSummaryIdToFlashcards(Summary summary) {
		if (summary == null || summary.getSummaryId() == null || summary.getFlashcards() == null) {
			return;
		}
		for (edu.cit.audioscholar.model.Flashcard flashcard : summary.getFlashcards()) {
			if (flashcard != null) {
				flashcard.setSummaryId(summary.getSummaryId());
			}
		}
	}

	public void deleteSummary(String summaryId) throws ExecutionException, InterruptedException {
		log.info("Attempting to delete summary with ID: {}", summaryId);
		Summary summary = getSummaryById(summaryId);

		if (summary != null) {
			try {
				Recording recording = recordingService.getRecordingById(summary.getRecordingId());
				if (recording != null && summaryId.equals(recording.getSummaryId())) {
					log.info("Unlinking summary ID {} from recording ID {}", summaryId, recording.getRecordingId());
					recording.setSummaryId(null);
					recordingService.updateRecording(recording);
				} else if (recording != null) {
					log.warn(
							"Recording {} found, but it was not linked to summary {} (current link: {}). No unlink performed.",
							summary.getRecordingId(), summaryId, recording.getSummaryId());
				} else {
					log.warn("Recording {} not found while trying to unlink summary {}.", summary.getRecordingId(),
							summaryId);
				}
			} catch (ExecutionException | InterruptedException e) {
				Thread.currentThread().interrupt();
				log.error("Error retrieving or updating recording {} while unlinking summary {}: {}",
						summary.getRecordingId(), summaryId, e.getMessage(), e);
			} catch (Exception e) {
				log.error("Unexpected error retrieving or updating recording {} while unlinking summary {}: {}",
						summary.getRecordingId(), summaryId, e.getMessage(), e);
			}

			summaryRepository.delete(summaryId);
			log.info("Successfully deleted summary document ID: {}", summaryId);

		} else {
			log.warn("Summary document with ID {} not found. Cannot delete.", summaryId);
		}
	}
}
