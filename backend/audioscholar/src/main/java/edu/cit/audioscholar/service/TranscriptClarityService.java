package edu.cit.audioscholar.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import edu.cit.audioscholar.model.QualityIssue;
import edu.cit.audioscholar.model.QualityReport;
import edu.cit.audioscholar.model.TranscriptChunk;
import edu.cit.audioscholar.model.TranscriptSegment;

@Service
public class TranscriptClarityService {
	public static final String CLARITY_SECTION_HEADING = "## Audio Clarity Notes";

	public List<TranscriptSegment> buildSegments(String transcriptText, QualityReport qualityReport) {
		if (!hasQualityWarnings(qualityReport)) {
			return List.of();
		}
		List<TranscriptSegment> segments = new ArrayList<>();
		String transcriptExcerpt = excerpt(transcriptText);
		for (QualityIssue issue : qualityReport.getIssues()) {
			if (issue == null || !StringUtils.hasText(issue.getIssueType())) {
				continue;
			}
			TranscriptSegment segment = new TranscriptSegment();
			segment.setStartTime(defaultTime(issue.getStartTime(), "00:00"));
			segment.setEndTime(defaultTime(issue.getEndTime(), segment.getStartTime()));
			segment.setClarityLabel(labelForIssueType(issue.getIssueType()));
			segment.setQualityIssueTypes(List.of(issue.getIssueType()));
			segment.setText(transcriptExcerpt);
			segments.add(segment);
		}
		return segments;
	}

	public List<TranscriptSegment> buildSegments(List<TranscriptChunk> chunks, QualityReport qualityReport) {
		if (chunks == null || chunks.isEmpty()) {
			return buildSegments((String) null, qualityReport);
		}
		List<IssueRange> issues = issueRanges(qualityReport);
		List<TranscriptSegment> segments = new ArrayList<>();
		for (TranscriptChunk chunk : chunks.stream().sorted(Comparator.comparingInt(TranscriptChunk::getIndex))
				.toList()) {
			if (chunk == null || !StringUtils.hasText(chunk.getText())) {
				continue;
			}
			long chunkStart = Math.max(0, chunk.getStartMs());
			long chunkEnd = Math.max(chunkStart + 1, chunk.getEndMs());
			Set<Long> boundaries = new HashSet<>();
			boundaries.add(chunkStart);
			boundaries.add(chunkEnd);
			for (IssueRange issue : issues) {
				if (overlaps(chunkStart, chunkEnd, issue.startMs(), issue.endMs())) {
					boundaries.add(clamp(issue.startMs(), chunkStart, chunkEnd));
					boundaries.add(clamp(issue.endMs(), chunkStart, chunkEnd));
				}
			}
			List<Long> ordered = boundaries.stream().sorted().toList();
			for (int index = 0; index < ordered.size() - 1; index++) {
				long start = ordered.get(index);
				long end = ordered.get(index + 1);
				if (end <= start) {
					continue;
				}
				List<IssueRange> overlappingIssues = issues.stream()
						.filter(issue -> overlaps(start, end, issue.startMs(), issue.endMs())).toList();
				TranscriptSegment segment = new TranscriptSegment();
				segment.setStartTime(formatTime(start));
				segment.setEndTime(formatTime(end));
				segment.setText(textForRange(chunk.getText(), chunkStart, chunkEnd, start, end));
				segment.setQualityIssueTypes(overlappingIssues.stream().map(IssueRange::issueType).distinct().toList());
				segment.setClarityLabel(labelForIssues(overlappingIssues));
				segments.add(segment);
			}
		}
		return mergeAdjacentEquivalentSegments(segments);
	}

	public String buildQualityNotice(QualityReport qualityReport) {
		List<TranscriptSegment> segments = buildSegments((String) null, qualityReport);
		if (segments.isEmpty()) {
			return null;
		}
		Set<String> ranges = segments.stream()
				.map(segment -> segment.getStartTime() + "-" + segment.getEndTime() + " " + segment.getClarityLabel())
				.collect(Collectors.toCollection(LinkedHashSet::new));
		return "Some transcript sections may be unreliable: " + String.join("; ", ranges) + ".";
	}

	public String annotateTranscript(String transcriptText, QualityReport qualityReport) {
		return annotateTranscript(transcriptText, buildSegments(transcriptText, qualityReport));
	}

	public String annotateTranscript(String transcriptText, List<TranscriptSegment> segments) {
		if (segments == null || segments.isEmpty()) {
			return transcriptText;
		}
		StringBuilder builder = new StringBuilder();
		builder.append("AUDIO CLARITY ANNOTATIONS\n");
		builder.append("Use these labels when creating the summary. Preserve clear material normally, but qualify ")
				.append("any claim that comes from unclear or garbled ranges.\n");
		for (TranscriptSegment segment : segments) {
			builder.append("[").append(segment.getStartTime()).append("-").append(segment.getEndTime()).append("] ");
			if (StringUtils.hasText(segment.getClarityLabel())) {
				builder.append(segment.getClarityLabel()).append(" ");
			}
			builder.append(StringUtils.hasText(segment.getText())
					? segment.getText()
					: issueLabels(segment.getQualityIssueTypes())).append("\n");
		}
		builder.append("\nTRANSCRIPT\n");
		builder.append(transcriptText);
		return builder.toString();
	}

	public String ensureClarityNotes(String summaryText, QualityReport qualityReport) {
		return ensureClarityNotes(summaryText, buildSegments((String) null, qualityReport));
	}

	public String ensureClarityNotes(String summaryText, List<TranscriptSegment> segments) {
		String baseSummary = summaryText != null ? summaryText : "";
		boolean hasLabeledSegments = segments != null
				&& segments.stream().anyMatch(segment -> StringUtils.hasText(segment.getClarityLabel()));
		if (!hasLabeledSegments || baseSummary.contains(CLARITY_SECTION_HEADING)) {
			return baseSummary;
		}
		String notes = buildClarityNotesMarkdown(segments);
		if (!StringUtils.hasText(notes)) {
			return baseSummary;
		}
		if (!StringUtils.hasText(baseSummary)) {
			return notes;
		}
		return notes + "\n\n" + baseSummary;
	}

	public String buildClarityNotesMarkdown(QualityReport qualityReport) {
		return buildClarityNotesMarkdown(buildSegments((String) null, qualityReport));
	}

	public String buildClarityNotesMarkdown(List<TranscriptSegment> segments) {
		if (segments == null || segments.isEmpty()) {
			return null;
		}
		StringBuilder builder = new StringBuilder(CLARITY_SECTION_HEADING).append("\n");
		for (TranscriptSegment segment : segments) {
			if (!StringUtils.hasText(segment.getClarityLabel())) {
				continue;
			}
			builder.append("- ").append(segment.getStartTime()).append("-").append(segment.getEndTime()).append(": ")
					.append(segment.getClarityLabel()).append(" ").append(issueLabels(segment.getQualityIssueTypes()))
					.append(" may make this part of the transcription unreliable. Review the original audio before ")
					.append("relying on details from this range.\n");
		}
		String notes = builder.toString().trim();
		return CLARITY_SECTION_HEADING.equals(notes) ? null : notes;
	}

	public boolean hasQualityWarnings(QualityReport qualityReport) {
		return qualityReport != null && qualityReport.getIssues() != null && !qualityReport.getIssues().isEmpty()
				&& !"ALL_CLEAR".equalsIgnoreCase(String.valueOf(qualityReport.getStatus()))
				&& !"UNAVAILABLE".equalsIgnoreCase(String.valueOf(qualityReport.getStatus()));
	}

	private String labelForIssueType(String issueType) {
		return switch (String.valueOf(issueType).toUpperCase(Locale.ROOT)) {
			case "AUDIO_CLIPPING", "INAUDIBLE_SPEECH", "MISSING_AUDIO" -> "(garbled audio)";
			default -> "(unclear audio)";
		};
	}

	private String labelForIssues(List<IssueRange> issues) {
		if (issues == null || issues.isEmpty()) {
			return null;
		}
		boolean hasGarbled = issues.stream().map(IssueRange::issueType)
				.anyMatch(issueType -> "(garbled audio)".equals(labelForIssueType(issueType)));
		return hasGarbled ? "(garbled audio)" : "(unclear audio)";
	}

	private String issueLabels(List<String> issueTypes) {
		if (issueTypes == null || issueTypes.isEmpty()) {
			return "Audio quality issues";
		}
		return issueTypes.stream().map(this::humanizeIssueType).collect(Collectors.joining(", "));
	}

	private String humanizeIssueType(String issueType) {
		if (!StringUtils.hasText(issueType)) {
			return "Audio quality issue";
		}
		String normalized = issueType.toLowerCase(Locale.ROOT).replace('_', ' ');
		return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
	}

	private List<IssueRange> issueRanges(QualityReport qualityReport) {
		if (!hasQualityWarnings(qualityReport)) {
			return List.of();
		}
		List<IssueRange> ranges = new ArrayList<>();
		for (QualityIssue issue : qualityReport.getIssues()) {
			if (issue == null || !StringUtils.hasText(issue.getIssueType())) {
				continue;
			}
			long start = parseTimeMillis(issue.getStartTime(), 0);
			long end = parseTimeMillis(issue.getEndTime(), start + 15_000);
			if (end <= start) {
				end = start + 15_000;
			}
			ranges.add(new IssueRange(start, end, issue.getIssueType()));
		}
		return ranges;
	}

	private boolean overlaps(long leftStart, long leftEnd, long rightStart, long rightEnd) {
		return leftStart < rightEnd && rightStart < leftEnd;
	}

	private long clamp(long value, long min, long max) {
		return Math.max(min, Math.min(max, value));
	}

	private String textForRange(String text, long chunkStart, long chunkEnd, long rangeStart, long rangeEnd) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		String[] words = text.trim().split("\\s+");
		if (words.length <= 1 || chunkEnd <= chunkStart) {
			return text.trim();
		}
		double startRatio = (rangeStart - chunkStart) / (double) (chunkEnd - chunkStart);
		double endRatio = (rangeEnd - chunkStart) / (double) (chunkEnd - chunkStart);
		int startIndex = Math.max(0, Math.min(words.length - 1, (int) Math.floor(startRatio * words.length)));
		int endIndex = Math.max(startIndex + 1, Math.min(words.length, (int) Math.ceil(endRatio * words.length)));
		return String.join(" ", java.util.Arrays.copyOfRange(words, startIndex, endIndex));
	}

	private List<TranscriptSegment> mergeAdjacentEquivalentSegments(List<TranscriptSegment> segments) {
		List<TranscriptSegment> merged = new ArrayList<>();
		for (TranscriptSegment segment : segments) {
			if (merged.isEmpty()) {
				merged.add(segment);
				continue;
			}
			TranscriptSegment previous = merged.get(merged.size() - 1);
			if (java.util.Objects.equals(previous.getClarityLabel(), segment.getClarityLabel())
					&& java.util.Objects.equals(previous.getQualityIssueTypes(), segment.getQualityIssueTypes())
					&& java.util.Objects.equals(previous.getEndTime(), segment.getStartTime())) {
				previous.setEndTime(segment.getEndTime());
				previous.setText((StringUtils.hasText(previous.getText()) ? previous.getText() + " " : "")
						+ (segment.getText() != null ? segment.getText() : ""));
			} else {
				merged.add(segment);
			}
		}
		return merged;
	}

	private long parseTimeMillis(String value, long fallback) {
		if (!StringUtils.hasText(value)) {
			return fallback;
		}
		String[] parts = value.trim().split(":");
		try {
			long seconds = 0;
			for (String part : parts) {
				seconds = seconds * 60 + Long.parseLong(part.trim());
			}
			return seconds * 1000;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private String formatTime(long millis) {
		long totalSeconds = Math.max(0, Math.round(millis / 1000.0));
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;
		if (hours > 0) {
			return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
		}
		return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
	}

	private String defaultTime(String value, String fallback) {
		return StringUtils.hasText(value) ? value : fallback;
	}

	private String excerpt(String transcriptText) {
		if (!StringUtils.hasText(transcriptText)) {
			return "Transcript text for this exact time range is unavailable; use the timestamp label to verify it.";
		}
		String compact = transcriptText.trim().replaceAll("\\s+", " ");
		if (compact.length() <= 240) {
			return compact;
		}
		return compact.substring(0, 240) + "...";
	}

	private record IssueRange(long startMs, long endMs, String issueType) {
	}
}
