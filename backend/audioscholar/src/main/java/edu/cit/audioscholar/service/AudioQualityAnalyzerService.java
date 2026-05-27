package edu.cit.audioscholar.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;

import edu.cit.audioscholar.model.AudioQualityIssue;
import edu.cit.audioscholar.model.AudioQualityReport;

@Service
public class AudioQualityAnalyzerService {

	private static final Logger log = LoggerFactory.getLogger(AudioQualityAnalyzerService.class);

	private static final String QUALITY_REPORTS_COLLECTION = "quality_reports";

	private final GeminiService geminiService;
	private final FirebaseService firebaseService;
	private final ObjectMapper objectMapper;

	public AudioQualityAnalyzerService(GeminiService geminiService, FirebaseService firebaseService,
			ObjectMapper objectMapper) {
		this.geminiService = geminiService;
		this.firebaseService = firebaseService;
		this.objectMapper = objectMapper;
	}

	/**
	 * Analyzes a transcript for audio quality issues and saves the report to
	 * Firestore.
	 *
	 * @param recordingId
	 *            the recording ID (used for lookup)
	 * @param metadataId
	 *            the audio metadata document ID
	 * @param transcript
	 *            the full transcript text to analyze
	 * @return the saved AudioQualityReport, or null if analysis failed
	 */
	public AudioQualityReport analyzeAndSave(String recordingId, String metadataId, String transcript) {
		log.info("[{}] Starting audio quality analysis.", metadataId);

		if (transcript == null || transcript.isBlank()) {
			log.warn("[{}] Transcript is null or blank — skipping quality analysis.", metadataId);
			return null;
		}

		String prompt = buildAnalysisPrompt();

		String rawJson;
		try {
			rawJson = geminiService.callGeminiSummarizationAPIWithFallback(prompt, transcript);
		} catch (Exception e) {
			log.error("[{}] Gemini call for quality analysis failed: {}", metadataId, e.getMessage(), e);
			return null;
		}

		if (rawJson == null || rawJson.isBlank()) {
			log.warn("[{}] Gemini returned empty response for quality analysis.", metadataId);
			return null;
		}

		AudioQualityReport report = parseGeminiResponse(rawJson, recordingId, metadataId);
		if (report == null) {
			log.warn("[{}] Could not parse Gemini quality analysis response.", metadataId);
			return null;
		}

		String reportId = UUID.randomUUID().toString();
		report.setReportId(reportId);
		report.setCreatedAt(Timestamp.now());

		try {
			firebaseService.saveData(QUALITY_REPORTS_COLLECTION, reportId, report.toMap());
			log.info("[{}] Audio quality report saved with ID: {}", metadataId, reportId);
		} catch (Exception e) {
			log.error("[{}] Failed to save quality report to Firestore: {}", metadataId, e.getMessage(), e);
			return null;
		}

		return report;
	}

	/**
	 * Retrieves the latest quality report for a given recording ID.
	 *
	 * @param recordingId
	 *            the recording ID to look up
	 * @return the AudioQualityReport, or null if not found
	 */
	public AudioQualityReport getReportByRecordingId(String recordingId) {
		log.debug("Querying quality report for recordingId: {}", recordingId);
		try {
			List<Map<String, Object>> results = firebaseService.queryCollection(QUALITY_REPORTS_COLLECTION,
					"recordingId", recordingId);
			if (results == null || results.isEmpty()) {
				log.info("No quality report found for recordingId: {}", recordingId);
				return null;
			}
			return AudioQualityReport.fromMap(results.get(0));
		} catch (Exception e) {
			log.error("Error querying quality report for recordingId {}: {}", recordingId, e.getMessage(), e);
			return null;
		}
	}

	// ─── Private Helpers ────────────────────────────────────────────────────────

	private String buildAnalysisPrompt() {
		return """
				You are an expert audio quality assessment AI. Analyze the following lecture transcript \
				for audio quality issues and return a structured JSON object.

				Detect any of these issue types if present:
				- BACKGROUND_NOISE: audible interference, static, or environmental noise affecting clarity
				- LOW_VOLUME: sections where the speaker is too quiet or barely audible
				- UNCLEAR_SPEECH: mumbling, fast speech, or heavily accented speech that reduces comprehension
				- EXCESSIVE_FILLER_WORDS: overuse of "um", "uh", "like", "you know", etc.
				- INCOHERENT_SEGMENT: sentences or passages that make no logical sense
				- ABRUPT_CUT: sudden breaks or jumps in the transcript suggesting a recording cut
				- OVERLAPPING_SPEECH: multiple speakers talking at once, producing garbled text

				Return ONLY a valid JSON object with this exact structure (no markdown, no explanation):
				{
				  "overallQuality": "GOOD" | "FAIR" | "POOR",
				  "overallSummary": "<1-2 sentence plain English summary of the recording quality>",
				  "issues": [
				    {
				      "issueType": "<IssueType>",
				      "severity": "LOW" | "MEDIUM" | "HIGH",
				      "startTime": <approximate start in seconds as a number, or null>,
				      "endTime": <approximate end in seconds as a number, or null>,
				      "description": "<brief description of the specific issue>",
				      "recommendedAction": "<actionable suggestion for the student or instructor>"
				    }
				  ]
				}

				If there are no issues, return an empty array for "issues".
				Assign overallQuality as: GOOD (0 issues or only LOW severity), FAIR (1-3 MEDIUM issues), \
				POOR (any HIGH severity or 4+ issues).
				""";
	}

	@SuppressWarnings("unchecked")
	private AudioQualityReport parseGeminiResponse(String rawJson, String recordingId, String metadataId) {
		try {
			// Strip any accidental markdown fences
			String cleaned = rawJson.strip();
			if (cleaned.startsWith("```")) {
				cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "").strip();
			}

			Map<String, Object> data = objectMapper.readValue(cleaned, new TypeReference<>() {
			});

			AudioQualityReport report = new AudioQualityReport();
			report.setRecordingId(recordingId);
			report.setMetadataId(metadataId);

			String qualityStr = (String) data.get("overallQuality");
			if (qualityStr != null) {
				try {
					report.setOverallQuality(AudioQualityReport.OverallQuality.valueOf(qualityStr.toUpperCase()));
				} catch (IllegalArgumentException e) {
					log.warn("[{}] Unknown overallQuality value '{}', defaulting to FAIR", metadataId, qualityStr);
					report.setOverallQuality(AudioQualityReport.OverallQuality.FAIR);
				}
			} else {
				report.setOverallQuality(AudioQualityReport.OverallQuality.FAIR);
			}

			report.setOverallSummary((String) data.get("overallSummary"));

			List<AudioQualityIssue> issues = new ArrayList<>();
			Object issuesObj = data.get("issues");
			if (issuesObj instanceof List) {
				List<Map<String, Object>> rawIssues = (List<Map<String, Object>>) issuesObj;
				for (Map<String, Object> rawIssue : rawIssues) {
					try {
						AudioQualityIssue issue = new AudioQualityIssue();
						issue.setIssueId(UUID.randomUUID().toString());

						String issueTypeStr = (String) rawIssue.get("issueType");
						if (issueTypeStr != null) {
							issue.setIssueType(AudioQualityIssue.IssueType.valueOf(issueTypeStr.toUpperCase()));
						}

						String severityStr = (String) rawIssue.get("severity");
						if (severityStr != null) {
							issue.setSeverity(AudioQualityIssue.Severity.valueOf(severityStr.toUpperCase()));
						}

						Object start = rawIssue.get("startTime");
						if (start instanceof Number)
							issue.setStartTime(((Number) start).doubleValue());

						Object end = rawIssue.get("endTime");
						if (end instanceof Number)
							issue.setEndTime(((Number) end).doubleValue());

						issue.setDescription((String) rawIssue.get("description"));
						issue.setRecommendedAction((String) rawIssue.get("recommendedAction"));
						issues.add(issue);
					} catch (Exception e) {
						log.warn("[{}] Skipping malformed issue entry: {}", metadataId, e.getMessage());
					}
				}
			}
			report.setIssues(issues);

			return report;
		} catch (Exception e) {
			log.error("[{}] Failed to parse Gemini quality analysis JSON: {}", metadataId, e.getMessage(), e);
			return null;
		}
	}
}
