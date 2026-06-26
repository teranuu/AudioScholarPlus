package edu.cit.audioscholar.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import edu.cit.audioscholar.model.QualityIssue;
import edu.cit.audioscholar.model.QualityReport;
import edu.cit.audioscholar.model.TranscriptChunk;
import edu.cit.audioscholar.model.TranscriptSegment;

class TranscriptClarityServiceTest {
	private final TranscriptClarityService service = new TranscriptClarityService();

	@Test
	void buildSegmentsLabelsBackgroundNoiseAsUnclearAudio() {
		QualityReport report = reportWithIssue("00:15", "00:30", "HIGH_BACKGROUND_NOISE");

		List<TranscriptSegment> segments = service.buildSegments("This is a transcript.", report);

		assertThat(segments).hasSize(1);
		assertThat(segments.get(0).getClarityLabel()).isEqualTo("(unclear audio)");
		assertThat(segments.get(0).getStartTime()).isEqualTo("00:15");
		assertThat(segments.get(0).getEndTime()).isEqualTo("00:30");
	}

	@Test
	void buildSegmentsLabelsClippingAsGarbledAudio() {
		QualityReport report = reportWithIssue("01:00", "01:15", "AUDIO_CLIPPING");

		List<TranscriptSegment> segments = service.buildSegments("This is a transcript.", report);

		assertThat(segments).hasSize(1);
		assertThat(segments.get(0).getClarityLabel()).isEqualTo("(garbled audio)");
	}

	@Test
	void ensureClarityNotesPrependsSectionWhenMissing() {
		QualityReport report = reportWithIssue("00:15", "00:30", "UNCLEAR_AUDIO");

		String summary = service.ensureClarityNotes("## Summary\nClear material.", report);

		assertThat(summary).startsWith("## Audio Clarity Notes");
		assertThat(summary).contains("00:15-00:30");
		assertThat(summary).contains("(unclear audio)");
		assertThat(summary).contains("## Summary");
	}

	@Test
	void ensureClarityNotesLeavesCleanReportUnchanged() {
		QualityReport report = QualityReport.allClear("recording-id");

		String summary = service.ensureClarityNotes("## Summary\nClear material.", report);

		assertThat(summary).isEqualTo("## Summary\nClear material.");
	}

	@Test
	void buildSegmentsSplitsChunkAtQualityIssueBoundaries() {
		TranscriptChunk chunk = chunk(0, 0, 60_000, "one two three four five six seven eight nine ten eleven twelve");
		QualityReport report = reportWithIssue("00:15", "00:30", "HIGH_BACKGROUND_NOISE");

		List<TranscriptSegment> segments = service.buildSegments(List.of(chunk), report);

		assertThat(segments).hasSize(3);
		assertThat(segments.get(0).getClarityLabel()).isNull();
		assertThat(segments.get(1).getClarityLabel()).isEqualTo("(unclear audio)");
		assertThat(segments.get(1).getStartTime()).isEqualTo("00:15");
		assertThat(segments.get(1).getEndTime()).isEqualTo("00:30");
		assertThat(segments.get(2).getClarityLabel()).isNull();
	}

	@Test
	void annotateTranscriptIncludesSegmentTextAndLabels() {
		TranscriptChunk chunk = chunk(0, 0, 30_000, "clear words noisy words");
		QualityReport report = reportWithIssue("00:15", "00:30", "AUDIO_CLIPPING");
		List<TranscriptSegment> segments = service.buildSegments(List.of(chunk), report);

		String annotated = service.annotateTranscript(chunk.getText(), segments);

		assertThat(annotated).contains("AUDIO CLARITY ANNOTATIONS");
		assertThat(annotated).contains("[00:15-00:30] (garbled audio)");
		assertThat(annotated).contains("noisy words");
	}

	private QualityReport reportWithIssue(String startTime, String endTime, String issueType) {
		QualityReport report = new QualityReport();
		report.setRecordingId("recording-id");
		report.setStatus("ISSUES_DETECTED");
		report.setIssues(List.of(new QualityIssue(startTime, endTime, issueType, "MODERATE", "Review this part.")));
		return report;
	}

	private TranscriptChunk chunk(int index, long startMs, long endMs, String text) {
		TranscriptChunk chunk = new TranscriptChunk();
		chunk.setIndex(index);
		chunk.setStartMs(startMs);
		chunk.setEndMs(endMs);
		chunk.setStatus("COMPLETE");
		chunk.setText(text);
		return chunk;
	}
}
