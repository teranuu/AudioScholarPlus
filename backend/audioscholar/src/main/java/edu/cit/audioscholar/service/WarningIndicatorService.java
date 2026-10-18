package edu.cit.audioscholar.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.Flashcard;
import edu.cit.audioscholar.model.QualityIssue;
import edu.cit.audioscholar.model.QualityReport;
import edu.cit.audioscholar.model.Summary;
import edu.cit.audioscholar.model.SummaryKeyPoint;
import edu.cit.audioscholar.model.WarningIndicator;

@Service
public class WarningIndicatorService {
	private static final String SUMMARY_KEY_POINTS_COLLECTION = "summaryKeyPoints";
	private static final String WARNING_INDICATORS_COLLECTION = "warningIndicators";

	private final SummaryService summaryService;
	private final QualityReportService qualityReportService;
	private final FirebaseService firebaseService;
	private final ValidationEventService validationEvents;
	private final WarningTemplateRegistry templates;
	private final String applicationVersion;
	private final int retentionDays;

	public WarningIndicatorService(SummaryService summaryService, QualityReportService qualityReportService,
			FirebaseService firebaseService) {
		this(summaryService, qualityReportService, firebaseService, null, new WarningTemplateRegistry(), "unknown",
				180);
	}
	@Autowired
	public WarningIndicatorService(SummaryService summaryService, QualityReportService qualityReportService,
			FirebaseService firebaseService, ValidationEventService validationEvents, WarningTemplateRegistry templates,
			@Value("${spring.application.version:unknown}") String applicationVersion,
			@Value("${validation.retention-days:180}") int retentionDays) {
		this.summaryService = summaryService;
		this.qualityReportService = qualityReportService;
		this.firebaseService = firebaseService;
		this.validationEvents = validationEvents;
		this.templates = templates;
		this.applicationVersion = applicationVersion;
		this.retentionDays = Math.max(1, retentionDays);
	}
	public WarningIndicatorService(SummaryService summaryService, QualityReportService qualityReportService,
			FirebaseService firebaseService, ValidationEventService validationEvents) {
		this(summaryService, qualityReportService, firebaseService, validationEvents, new WarningTemplateRegistry(),
				"unknown", 180);
	}

	public List<WarningIndicator> generateWarningIndicators(String summaryId) throws Exception {
		try {
			return generateWarningIndicators(summaryId, "PROCESSING");
		} catch (Exception failure) {
			try {
				Summary summary = summaryService.getSummaryById(summaryId);
				if (summary != null)
					emitGeneration(summary, summary.getSummaryEvidenceVersion(), 0, false,
							failure.getClass().getSimpleName());
			} catch (Exception ignored) {
			}
			throw failure;
		}
	}

	private List<WarningIndicator> generateWarningIndicators(String summaryId, String evidenceSource) throws Exception {
		Summary summary = summaryService.getSummaryById(summaryId);
		if (summary == null) {
			throw new IllegalArgumentException("Summary not found.");
		}
		List<SummaryKeyPoint> keyPoints = getSummaryKeyPoints(summary);
		summary.setSummaryKeyPoints(keyPoints);
		String evidenceVersion = evidenceVersion(summary, keyPoints);
		QualityReport report = summary.getQualityReport();
		if (report == null && StringUtils.hasText(summary.getRecordingId())) {
			report = qualityReportService.getReport(summary.getRecordingId());
		}
		if (report == null || report.getIssues() == null || report.getIssues().isEmpty()) {
			summary.setSummaryEvidenceVersion(evidenceVersion);
			summary.setWarningEvidenceStale(false);
			summaryService.updateSummary(summary);
			emitGeneration(summary, evidenceVersion, 0, true, null);
			return List.of();
		}
		summary.setSummaryEvidenceVersion(evidenceVersion);
		summary.setWarningEvidenceStale(false);
		summaryService.updateSummary(summary);

		persistSummaryKeyPoints(summary.getSummaryId(), keyPoints);
		List<WarningIndicator> warnings = new ArrayList<>();
		for (SummaryKeyPoint keyPoint : keyPoints) {
			for (QualityIssue issue : report.getIssues()) {
				String decision = persistMapping(summary, keyPoint.getKeyPointId(), null, keyPoint.getSourceFileId(),
						keyPoint.getSourceSegmentId(), keyPoint.getSourceStartTime(), keyPoint.getSourceEndTime(),
						issue, evidenceSource);
				if ("OVERLAP".equals(decision)) {
					WarningIndicator warning = candidate(summary, report, keyPoint.getKeyPointId(), null, issue,
							evidenceVersion, evidenceSource);
					firebaseService.saveData("warningCandidates", warning.getWarningId(), warning.toMap());
					if ("DISPLAYABLE".equals(warning.getGenerationStatus())) {
						firebaseService.saveData(WARNING_INDICATORS_COLLECTION, warning.getWarningId(),
								warning.toMap());
						warnings.add(warning);
					}
				}
			}
		}
		for (Flashcard flashcard : summary.getFlashcards()) {
			if (flashcard == null || flashcard.getCardId() == null) {
				continue;
			}
			for (QualityIssue issue : report.getIssues()) {
				String decision = persistMapping(summary, null, flashcard.getCardId(), flashcard.getSourceFileId(),
						flashcard.getSourceSegmentId(), flashcard.getSourceStartTime(), flashcard.getSourceEndTime(),
						issue, evidenceSource);
				if ("OVERLAP".equals(decision)) {
					WarningIndicator warning = candidate(summary, report, null, flashcard.getCardId(), issue,
							evidenceVersion, evidenceSource);
					firebaseService.saveData("warningCandidates", warning.getWarningId(), warning.toMap());
					if ("DISPLAYABLE".equals(warning.getGenerationStatus())) {
						firebaseService.saveData(WARNING_INDICATORS_COLLECTION, warning.getWarningId(),
								warning.toMap());
						warnings.add(warning);
					}
				}
			}
		}
		emitGeneration(summary, evidenceVersion, warnings.size(), true, null);
		return warnings;
	}

	public List<WarningIndicator> getWarningIndicators(String summaryId) throws Exception {
		Summary summary = summaryService.getSummaryById(summaryId);
		if (summary == null) {
			throw new IllegalArgumentException("Summary not found.");
		}
		List<SummaryKeyPoint> keyPoints = getSummaryKeyPoints(summary);
		List<WarningIndicator> warnings = mapWarnings(
				firebaseService.queryCollection(WARNING_INDICATORS_COLLECTION, "summaryId", summaryId));
		if (warnings.isEmpty()) {
			warnings = getLegacyWarnings(keyPoints, summary.getFlashcards());
		}
		if (!warnings.isEmpty() || StringUtils.hasText(summary.getSummaryEvidenceVersion()))
			return warnings;
		return generateWarningIndicators(summaryId, "LEGACY_READ_BACKFILL");
	}

	public Map<String, Object> recordDisplay(String summaryId, String warningId, String displayState) throws Exception {
		if (!Set.of("INDICATOR_VISIBLE", "DETAILS_EXPANDED").contains(displayState))
			throw new IllegalArgumentException("displayState must be INDICATOR_VISIBLE or DETAILS_EXPANDED");
		Summary summary = summaryService.getSummaryById(summaryId);
		if (summary == null)
			throw new IllegalArgumentException("Summary not found.");
		Map<String, Object> stored = firebaseService.getData(WARNING_INDICATORS_COLLECTION, warningId);
		if (stored == null || !summaryId.equals(stored.get("summaryId")))
			throw new IllegalArgumentException("Warning not found.");
		String version = stored.get("summaryEvidenceVersion") instanceof String value ? value : null;
		if (Boolean.TRUE.equals(stored.get("stale")) || !"DISPLAYABLE".equals(stored.get("generationStatus"))
				|| summary.isWarningEvidenceStale() || !StringUtils.hasText(version)
				|| !version.equals(summary.getSummaryEvidenceVersion()))
			throw new IllegalArgumentException("Warning evidence is stale or not displayable.");
		String session = studySessionId(summary.getRecordingId());
		String receiptId = ValidationEventService.digest(session + warningId + displayState + version);
		java.util.Date now = new java.util.Date();
		Map<String, Object> receipt = new java.util.LinkedHashMap<>();
		receipt.put("receiptId", receiptId);
		receipt.put("studySessionId", session);
		receipt.put("warningId", warningId);
		receipt.put("displayState", displayState);
		receipt.put("summaryEvidenceVersion", version);
		receipt.put("summaryId", summaryId);
		receipt.put("recordingId", summary.getRecordingId());
		receipt.put("reportId", stored.get("reportId"));
		receipt.put("serverTime", now);
		receipt.put("expiresAt", new java.util.Date(now.getTime() + retentionDays * 86400000L));
		receipt.put("applicationVersion", applicationVersion);
		receipt.put("stale", false);
		firebaseService.saveData("warningDisplayReceipts", receiptId, receipt);
		return receipt;
	}

	public void markEvidenceStale(Summary summary) {
		if (summary == null || !StringUtils.hasText(summary.getSummaryId()))
			return;
		for (String collection : List.of("warningMappings", "warningCandidates", "warningIndicators",
				"warningDisplayReceipts"))
			for (Map<String, Object> row : firebaseService.queryCollection(collection, "summaryId",
					summary.getSummaryId()))
				if (row.get("id") != null)
					firebaseService.updateDataWithMap(collection, String.valueOf(row.get("id")), Map.of("stale", true));
		summary.setWarningEvidenceStale(true);
		summary.setSummaryEvidenceVersion(evidenceVersion(summary));
	}

	private WarningIndicator candidate(Summary summary, QualityReport report, String keyPointId, String cardId,
			QualityIssue issue, String evidenceVersion, String evidenceSource) {
		WarningTemplateRegistry.TemplateResult template = templates.render(issue.getIssueType());
		WarningIndicator warning = new WarningIndicator();
		String itemId = keyPointId != null ? keyPointId : cardId;
		warning.setWarningId(ValidationEventService
				.digest(summary.getSummaryId() + ":" + evidenceVersion + ":" + itemId + ":" + issue.getIssueId()));
		warning.setSummaryId(summary.getSummaryId());
		warning.setRecordingId(summary.getRecordingId());
		warning.setReportId(report.getReportId());
		warning.setKeyPointId(keyPointId);
		warning.setCardId(cardId);
		warning.setIssueId(issue.getIssueId());
		warning.setIssueType(issue.getIssueType());
		warning.setSeverity(issue.getSeverity());
		warning.setSummaryEvidenceVersion(evidenceVersion);
		warning.setTemplateId(template.templateId());
		warning.setTemplateVersion(template.templateVersion());
		warning.setConformanceRuleVersion(WarningTemplateRegistry.CONFORMANCE_VERSION);
		warning.setRecommendedAction(template.text() == null ? issue.getRecommendedAction() : template.text());
		warning.setRenderedTextHash(ValidationEventService.digest(String.valueOf(warning.getRecommendedAction())));
		boolean conforms = templates.conforms(template, warning.getRecommendedAction());
		warning.setAutomatedConformancePassed(conforms);
		warning.setGenerationStatus(conforms ? "DISPLAYABLE" : "BLOCKED");
		warning.setEvidenceSource(evidenceSource);
		warning.setExpiresAt(new java.util.Date(System.currentTimeMillis() + retentionDays * 86_400_000L));
		return warning;
	}

	private String evidenceVersion(Summary summary) {
		return evidenceVersion(summary, getSummaryKeyPoints(summary));
	}

	private String evidenceVersion(Summary summary, List<SummaryKeyPoint> keyPoints) {
		StringBuilder value = new StringBuilder(summary.getSummaryId()).append(':');
		value.append(ValidationEventService.digest(String.valueOf(summary.getFormattedSummaryText()))).append(':')
				.append(ValidationEventService.digest(String.valueOf(summary.getKeyPoints()))).append(':')
				.append(ValidationEventService.digest(String.valueOf(summary.getGlossary()))).append(':');
		for (SummaryKeyPoint point : keyPoints)
			value.append(point.getKeyPointId()).append(':')
					.append(ValidationEventService.digest(String.valueOf(point.getText()))).append(':')
					.append(point.getSourceStartTime()).append(':').append(point.getSourceEndTime()).append(';');
		for (Flashcard card : summary.getFlashcards())
			value.append(card.getCardId()).append(':')
					.append(ValidationEventService.digest(String.valueOf(card.getFront()) + card.getBack())).append(':')
					.append(card.getSourceStartTime()).append(':').append(card.getSourceEndTime()).append(';');
		return ValidationEventService.digest(value.toString());
	}

	private String studySessionId(String recordingId) {
		AudioMetadata metadata = firebaseService.getAudioMetadataByRecordingId(recordingId);
		String value = metadata == null ? null : metadata.getStudySessionId();
		if (!StringUtils.hasText(value))
			throw new IllegalStateException("Validation study session is unavailable.");
		return value;
	}

	private void emitGeneration(Summary summary, String evidenceVersion, int count, boolean success, String detail) {
		if (validationEvents == null || !StringUtils.hasText(summary.getRecordingId()))
			return;
		AudioMetadata metadata = firebaseService.getAudioMetadataByRecordingId(summary.getRecordingId());
		String clientSource = metadata == null ? "UNKNOWN" : metadata.getClientSource();
		Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("summaryId", summary.getSummaryId());
		payload.put("summaryEvidenceVersion", evidenceVersion);
		payload.put("displayableCount", count);
		if (detail != null)
			payload.put("detail", detail);
		validationEvents.emit(summary.getRecordingId(), "SINGLE_SOURCE", clientSource,
				success ? "WARNING_GENERATION_COMPLETED" : "WARNING_GENERATION_FAILED", List.of(7, 8), evidenceVersion,
				payload, "audio_metadata");
	}

	private List<WarningIndicator> getLegacyWarnings(List<SummaryKeyPoint> keyPoints, List<Flashcard> flashcards) {
		List<WarningIndicator> warnings = new ArrayList<>();
		List<String> keyPointIds = keyPoints.stream().filter(java.util.Objects::nonNull)
				.map(SummaryKeyPoint::getKeyPointId).filter(StringUtils::hasText).toList();
		for (List<String> chunk : chunks(keyPointIds, 30)) {
			warnings.addAll(mapWarnings(
					firebaseService.queryCollectionWhereIn(WARNING_INDICATORS_COLLECTION, "keyPointId", chunk)));
		}
		List<Flashcard> safeFlashcards = flashcards == null ? List.of() : flashcards;
		List<String> cardIds = safeFlashcards.stream().filter(java.util.Objects::nonNull).map(Flashcard::getCardId)
				.filter(StringUtils::hasText).toList();
		for (List<String> chunk : chunks(cardIds, 30)) {
			warnings.addAll(mapWarnings(
					firebaseService.queryCollectionWhereIn(WARNING_INDICATORS_COLLECTION, "cardId", chunk)));
		}
		return warnings;
	}

	private List<WarningIndicator> mapWarnings(List<Map<String, Object>> stored) {
		return stored.stream().map(WarningIndicator::fromMap).filter(java.util.Objects::nonNull).toList();
	}

	private List<List<String>> chunks(List<String> values, int size) {
		List<List<String>> chunks = new ArrayList<>();
		for (int start = 0; start < values.size(); start += size) {
			chunks.add(values.subList(start, Math.min(start + size, values.size())));
		}
		return chunks;
	}

	public boolean checkTimestampOverlap(String keyPointStart, String keyPointEnd, String issueStart, String issueEnd) {
		Integer kpStart = parseTimestamp(keyPointStart);
		Integer kpEnd = parseTimestamp(keyPointEnd);
		Integer isStart = parseTimestamp(issueStart);
		Integer isEnd = parseTimestamp(issueEnd);
		if (kpStart == null || kpEnd == null || isStart == null || isEnd == null) {
			return false;
		}
		return kpStart < isEnd && isStart < kpEnd;
	}

	private String persistMapping(Summary summary, String keyPointId, String cardId, String sourceFileId,
			String segmentId, String itemStart, String itemEnd, QualityIssue issue, String evidenceSource) {
		Integer start = parseTimestamp(itemStart), end = parseTimestamp(itemEnd);
		Long issueStart = issue.getStartMs(), issueEnd = issue.getEndMs();
		String itemId = keyPointId != null ? keyPointId : cardId;
		String decision;
		long overlapMs = 0;
		if (!StringUtils.hasText(sourceFileId) || !StringUtils.hasText(segmentId) || start == null || end == null
				|| issueStart == null || issueEnd == null) {
			decision = "UNMAPPABLE";
		} else {
			long itemStartMs = start * 1000L, itemEndMs = end * 1000L;
			overlapMs = Math.max(0, Math.min(itemEndMs, issueEnd) - Math.max(itemStartMs, issueStart));
			decision = overlapMs > 0 ? "OVERLAP" : "NO_OVERLAP";
		}
		String evidenceVersion = StringUtils.hasText(summary.getSummaryEvidenceVersion())
				? summary.getSummaryEvidenceVersion()
				: evidenceVersion(summary);
		String id = ValidationEventService
				.digest(summary.getSummaryId() + ":" + evidenceVersion + ":" + itemId + ":" + issue.getIssueId());
		Map<String, Object> row = new java.util.LinkedHashMap<>();
		row.put("mappingId", id);
		row.put("summaryId", summary.getSummaryId());
		row.put("summaryEvidenceVersion", evidenceVersion);
		row.put("stale", false);
		row.put("evidenceSource", evidenceSource);
		row.put("expiresAt", new java.util.Date(System.currentTimeMillis() + retentionDays * 86_400_000L));
		row.put("recordingId", summary.getRecordingId());
		row.put("itemId", itemId);
		row.put("keyPointId", keyPointId);
		row.put("cardId", cardId);
		row.put("sourceFileId", sourceFileId);
		row.put("segmentId", segmentId);
		row.put("issueId", issue.getIssueId());
		row.put("itemStartMs", start == null ? null : start * 1000L);
		row.put("itemEndMs", end == null ? null : end * 1000L);
		row.put("issueStartMs", issueStart);
		row.put("issueEndMs", issueEnd);
		row.put("overlapMs", overlapMs);
		row.put("decision", decision);
		row.put("algorithmVersion", "strict-interval-overlap-v1");
		firebaseService.saveData("warningMappings", id, row);
		Map<String, Object> multiWorkflow = StringUtils.hasText(summary.getRecordingId())
				? firebaseService.getData("multiSourceJobs", summary.getRecordingId())
				: null;
		String workflowCollection = multiWorkflow != null ? "multiSourceJobs" : "audio_metadata";
		String workflowType = multiWorkflow != null ? "MULTI_SOURCE" : "SINGLE_SOURCE";
		Map<String, Object> workflow = multiWorkflow != null
				? multiWorkflow
				: StringUtils.hasText(summary.getRecordingId())
						? firebaseService.getData("audio_metadata", summary.getRecordingId())
						: null;
		if ("UNMAPPABLE".equals(decision) && StringUtils.hasText(summary.getRecordingId()))
			firebaseService.updateDataWithMap(workflowCollection, summary.getRecordingId(),
					Map.of("measurementIncomplete", true));
		String clientSource = workflow != null
				? String.valueOf(workflow.getOrDefault("clientSource", "UNKNOWN"))
				: "UNKNOWN";
		if (validationEvents != null && StringUtils.hasText(summary.getRecordingId()))
			validationEvents.emit(summary.getRecordingId(), workflowType, clientSource, "WARNING_MAPPING_PERSISTED",
					List.of(7), id, Map.of("summaryId", summary.getSummaryId(), "decisionId", id, "algorithmVersion",
							"strict-interval-overlap-v1"),
					workflowCollection);
		return decision;
	}

	private List<SummaryKeyPoint> getSummaryKeyPoints(Summary summary) {
		if (summary.getSummaryKeyPoints() != null && !summary.getSummaryKeyPoints().isEmpty()) {
			return summary.getSummaryKeyPoints();
		}
		if (summary.isWarningEvidenceStale()) {
			return generatedSummaryKeyPoints(summary);
		}
		List<Map<String, Object>> stored = firebaseService.queryCollection(SUMMARY_KEY_POINTS_COLLECTION, "summaryId",
				summary.getSummaryId());
		if (!stored.isEmpty()) {
			return stored.stream().map(SummaryKeyPoint::fromMap).filter(java.util.Objects::nonNull).toList();
		}
		return generatedSummaryKeyPoints(summary);
	}

	private List<SummaryKeyPoint> generatedSummaryKeyPoints(Summary summary) {
		List<SummaryKeyPoint> generated = new ArrayList<>();
		int index = 0;
		for (String text : summary.getKeyPoints()) {
			SummaryKeyPoint keyPoint = new SummaryKeyPoint();
			keyPoint.setSummaryId(summary.getSummaryId());
			keyPoint.setKeyPointId(ValidationEventService.digest(
					summary.getSummaryId() + ":key-point:" + index++ + ":" + ValidationEventService.digest(text)));
			keyPoint.setText(text);
			generated.add(keyPoint);
		}
		return generated;
	}

	private void persistSummaryKeyPoints(String summaryId, List<SummaryKeyPoint> keyPoints) {
		if (keyPoints == null) {
			return;
		}
		for (SummaryKeyPoint keyPoint : keyPoints) {
			if (keyPoint == null || keyPoint.getKeyPointId() == null) {
				continue;
			}
			keyPoint.setSummaryId(summaryId);
			firebaseService.saveData(SUMMARY_KEY_POINTS_COLLECTION, keyPoint.getKeyPointId(), keyPoint.toMap());
		}
	}

	private Set<String> existingWarningLinks(List<SummaryKeyPoint> keyPoints) {
		Set<String> links = new HashSet<>();
		if (keyPoints == null) {
			return links;
		}
		for (SummaryKeyPoint keyPoint : keyPoints) {
			if (keyPoint == null || keyPoint.getKeyPointId() == null) {
				continue;
			}
			List<Map<String, Object>> stored = firebaseService.queryCollection(WARNING_INDICATORS_COLLECTION,
					"keyPointId", keyPoint.getKeyPointId());
			for (Map<String, Object> item : stored) {
				WarningIndicator warning = WarningIndicator.fromMap(item);
				if (warning != null && warning.getIssueId() != null) {
					links.add(keyPoint.getKeyPointId() + "::" + warning.getIssueId());
				}
			}
		}
		return links;
	}

	private Set<String> existingFlashcardWarningLinks(List<Flashcard> flashcards) {
		Set<String> links = new HashSet<>();
		if (flashcards == null) {
			return links;
		}
		for (Flashcard flashcard : flashcards) {
			if (flashcard == null || flashcard.getCardId() == null) {
				continue;
			}
			List<Map<String, Object>> stored = firebaseService.queryCollection(WARNING_INDICATORS_COLLECTION, "cardId",
					flashcard.getCardId());
			for (Map<String, Object> item : stored) {
				WarningIndicator warning = WarningIndicator.fromMap(item);
				if (warning != null && warning.getIssueId() != null) {
					links.add(flashcard.getCardId() + "::" + warning.getIssueId());
				}
			}
		}
		return links;
	}

	private Integer parseTimestamp(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String[] parts = value.trim().split(":");
		try {
			if (parts.length == 2) {
				return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
			}
			if (parts.length == 3) {
				return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
			}
		} catch (NumberFormatException ignored) {
			return null;
		}
		return null;
	}
}
