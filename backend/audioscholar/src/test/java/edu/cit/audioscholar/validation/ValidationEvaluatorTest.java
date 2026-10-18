package edu.cit.audioscholar.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ValidationEvaluatorTest {
	@TempDir
	Path directory;

	@Test
	void oneToOneMatchingPreventsDoubleCounting() throws Exception {
		String export = "{\"dataset\":\"qualityIssues\",\"data\":{\"recordingId\":\"r1\",\"issueType\":\"NOISE\",\"startMs\":0,\"endMs\":10000}}\n"
				+ "{\"dataset\":\"qualityIssues\",\"data\":{\"recordingId\":\"r1\",\"issueType\":\"NOISE\",\"startMs\":0,\"endMs\":10000}}\n";
		JsonNode metrics = evaluate(export,
				"{\"schemaVersion\":1,\"problemSegments\":[{\"recordingId\":\"r1\",\"issueType\":\"NOISE\",\"startMs\":0,\"endMs\":10000}]}");
		assertThat(metrics.path("3").path("numerator").asInt()).isEqualTo(1);
		assertThat(metrics.path("3").path("denominator").asInt()).isEqualTo(2);
		assertThat(metrics.path("3").path("status").asText()).isEqualTo("FAIL");
	}

	@Test
	void malformedTruthAndIncompleteTrailsAreNotEvaluable() throws Exception {
		String export = "{\"dataset\":\"events\",\"data\":{\"workflowId\":\"r1\",\"clientSource\":\"WEB\",\"eventType\":\"EVIDENCE_WRITE_FAILED\",\"occurredAt\":\"2026-01-01T00:00:00Z\"}}\n"
				+ "{\"dataset\":\"qualityIssues\",\"data\":{\"recordingId\":\"r1\",\"issueType\":\"NOISE\",\"startMs\":0,\"endMs\":10000}}\n";
		JsonNode metrics = evaluate(export,
				"{\"schemaVersion\":1,\"problemSegments\":[{\"recordingId\":\"r1\",\"issueType\":\"NOISE\",\"startMs\":10000,\"endMs\":0}]}");
		assertThat(metrics.path("3").path("status").asText()).isEqualTo("NOT_EVALUABLE");
	}

	@Test
	void inaccurateAlignmentCountsAsFailure() throws Exception {
		String export = "{\"dataset\":\"crossSourceAlignments\",\"data\":{\"sourcePairId\":\"a:b\",\"sourceAStartMs\":9000,\"sourceAEndMs\":19000,\"sourceBStartMs\":9000,\"sourceBEndMs\":19000}}";
		JsonNode metrics = evaluate(export,
				"{\"schemaVersion\":1,\"overlapBoundaries\":[{\"sourcePairId\":\"a:b\",\"sourceAStartMs\":0,\"sourceAEndMs\":10000,\"sourceBStartMs\":0,\"sourceBEndMs\":10000}]}");
		assertThat(metrics.path("14").path("denominator").asInt()).isEqualTo(1);
		assertThat(metrics.path("14").path("status").asText()).isEqualTo("FAIL");
	}

	@Test
	void schemaV2ExcludesPartialReportsFromT3ToT5() throws Exception {
		String export = manifest("bound")
				+ row("qualityReports",
						"{\"reportId\":\"full\",\"analysisStatus\":\"COMPLETED\",\"analysisMode\":\"FULL_PCM\"}")
				+ row("qualityReports",
						"{\"reportId\":\"partial\",\"analysisStatus\":\"PARTIAL\",\"analysisMode\":\"METADATA_ONLY\"}")
				+ row("qualityIssues",
						"{\"reportId\":\"full\",\"issueId\":\"f1\",\"issueType\":\"UNCLEAR_AUDIO\",\"startMs\":0,\"endMs\":10000}")
				+ row("qualityIssues",
						"{\"reportId\":\"partial\",\"issueId\":\"p1\",\"issueType\":\"UNCLEAR_AUDIO\",\"startMs\":0,\"endMs\":10000}");
		String truth = "{\"schemaVersion\":2,\"exportManifestHash\":\"bound\","
				+ "\"reviewedRecordings\":[{\"reportId\":\"full\",\"reviewComplete\":true}],"
				+ "\"problemSegments\":[{\"annotationId\":\"a1\",\"reportId\":\"full\",\"issueType\":\"UNCLEAR_AUDIO\",\"startMs\":0,\"endMs\":10000}]}";

		JsonNode metrics = evaluate(export, truth);

		assertThat(metrics.path("3").path("numerator").asInt()).isEqualTo(1);
		assertThat(metrics.path("3").path("denominator").asInt()).isEqualTo(1);
		assertThat(metrics.path("4").path("status").asText()).isEqualTo("PASS");
		assertThat(metrics.path("5").path("status").asText()).isEqualTo("PASS");
	}

	@Test
	void t7RequiresViewportReceiptAndT8CountsExpertRejectionAsFailure() throws Exception {
		String export = manifest("warnings") + row("warningMappings",
				"{\"summaryId\":\"s1\",\"summaryEvidenceVersion\":\"v1\",\"itemId\":\"k1\",\"decision\":\"OVERLAP\",\"evidenceSource\":\"PROCESSING\",\"stale\":false}")
				+ row("warningIndicators",
						"{\"warningId\":\"w1\",\"summaryId\":\"s1\",\"summaryEvidenceVersion\":\"v1\",\"keyPointId\":\"k1\",\"generationStatus\":\"DISPLAYABLE\",\"evidenceSource\":\"PROCESSING\",\"stale\":false}")
				+ row("warningDisplayReceipts",
						"{\"warningId\":\"w1\",\"summaryEvidenceVersion\":\"v1\",\"displayState\":\"DETAILS_EXPANDED\",\"stale\":false}")
				+ row("warningCandidates",
						"{\"warningId\":\"w1\",\"templateId\":\"quality-unclear-audio\",\"templateVersion\":\"1\",\"automatedConformancePassed\":true,\"evidenceSource\":\"PROCESSING\",\"stale\":false}");
		String truth = "{\"schemaVersion\":2,\"exportManifestHash\":\"warnings\","
				+ "\"warningTemplateReviews\":[{\"templateId\":\"quality-unclear-audio\",\"templateVersion\":\"1\",\"reviewerConforms\":false}]}";

		JsonNode metrics = evaluate(export, truth);

		assertThat(metrics.path("7").path("numerator").asInt()).isZero();
		assertThat(metrics.path("7").path("denominator").asInt()).isEqualTo(1);
		assertThat(metrics.path("7").path("status").asText()).isEqualTo("FAIL");
		assertThat(metrics.path("8").path("numerator").asInt()).isZero();
		assertThat(metrics.path("8").path("denominator").asInt()).isEqualTo(1);
		assertThat(metrics.path("8").path("status").asText()).isEqualTo("FAIL");
	}

	@Test
	void t6DenominatorContainsOnlyStartedSingleSourceAttempts() throws Exception {
		String export = manifest("delivery") + row("events",
				"{\"eventType\":\"PROCESSING_STARTED\",\"workflowType\":\"SINGLE_SOURCE\",\"clientSource\":\"WEB\",\"payload\":{\"attemptId\":\"r1:1\"}}")
				+ row("events",
						"{\"eventType\":\"REQUEST_REJECTED\",\"workflowType\":\"SINGLE_SOURCE\",\"clientSource\":\"WEB\",\"payload\":{\"attemptId\":\"rejected:1\"}}")
				+ row("qualityReports",
						"{\"reportId\":\"report-1\",\"attemptId\":\"r1:1\",\"status\":\"ALL_CLEAR\",\"analysisStatus\":\"COMPLETED\",\"analysisMode\":\"FULL_PCM\"}");

		JsonNode metrics = evaluate(export, "{\"schemaVersion\":2,\"exportManifestHash\":\"delivery\"}");

		assertThat(metrics.path("6").path("numerator").asInt()).isEqualTo(1);
		assertThat(metrics.path("6").path("denominator").asInt()).isEqualTo(1);
		assertThat(metrics.path("6").path("status").asText()).isEqualTo("PASS");
		assertThat(metrics.path("6").path("diagnostics").path("rejectedBeforeProcessing").asInt()).isEqualTo(1);
	}

	@Test
	void t6DoesNotCountPersistenceEventWhenExportedReportIsMissing() throws Exception {
		String export = manifest("missing-report") + row("events",
				"{\"eventType\":\"PROCESSING_STARTED\",\"workflowType\":\"SINGLE_SOURCE\",\"clientSource\":\"WEB\",\"payload\":{\"attemptId\":\"r1:1\"}}")
				+ row("events",
						"{\"eventType\":\"QUALITY_REPORT_PERSISTED\",\"workflowType\":\"SINGLE_SOURCE\",\"clientSource\":\"WEB\",\"payload\":{\"attemptId\":\"r1:1\",\"status\":\"ALL_CLEAR\"}}");

		JsonNode metrics = evaluate(export, "{\"schemaVersion\":2,\"exportManifestHash\":\"missing-report\"}");

		assertThat(metrics.path("6").path("numerator").asInt()).isZero();
		assertThat(metrics.path("6").path("denominator").asInt()).isEqualTo(1);
		assertThat(metrics.path("6").path("status").asText()).isEqualTo("FAIL");
	}

	@Test
	void legacyReadBackfillEvidenceIsExcludedFromT7AndT8() throws Exception {
		String export = manifest("legacy") + row("warningMappings",
				"{\"summaryId\":\"s1\",\"summaryEvidenceVersion\":\"v1\",\"itemId\":\"k1\",\"decision\":\"OVERLAP\",\"evidenceSource\":\"LEGACY_READ_BACKFILL\",\"stale\":false}")
				+ row("warningIndicators",
						"{\"warningId\":\"w1\",\"summaryId\":\"s1\",\"summaryEvidenceVersion\":\"v1\",\"keyPointId\":\"k1\",\"generationStatus\":\"DISPLAYABLE\",\"evidenceSource\":\"LEGACY_READ_BACKFILL\",\"stale\":false}")
				+ row("warningDisplayReceipts",
						"{\"warningId\":\"w1\",\"summaryEvidenceVersion\":\"v1\",\"displayState\":\"INDICATOR_VISIBLE\",\"stale\":false}")
				+ row("warningCandidates",
						"{\"warningId\":\"w1\",\"templateId\":\"quality-noise\",\"templateVersion\":\"1\",\"automatedConformancePassed\":true,\"evidenceSource\":\"LEGACY_READ_BACKFILL\",\"stale\":false}");
		String truth = "{\"schemaVersion\":2,\"exportManifestHash\":\"legacy\","
				+ "\"warningTemplateReviews\":[{\"templateId\":\"quality-noise\",\"templateVersion\":\"1\",\"reviewerConforms\":true}]}";

		JsonNode metrics = evaluate(export, truth);

		assertThat(metrics.path("7").path("denominator").asInt()).isZero();
		assertThat(metrics.path("7").path("status").asText()).isEqualTo("NOT_EVALUABLE");
		assertThat(metrics.path("8").path("denominator").asInt()).isZero();
		assertThat(metrics.path("8").path("status").asText()).isEqualTo("NOT_EVALUABLE");
	}

	private String manifest(String hash) {
		return row("manifest", "{\"exportBindingHash\":\"" + hash + "\"}");
	}

	private String row(String dataset, String data) {
		return "{\"dataset\":\"" + dataset + "\",\"data\":" + data + "}\n";
	}

	private JsonNode evaluate(String exportText, String truthText) throws Exception {
		Path export = directory.resolve("export-" + System.nanoTime() + ".jsonl");
		Path truth = directory.resolve("truth-" + System.nanoTime() + ".json");
		Path output = directory.resolve("out-" + System.nanoTime());
		Files.writeString(export, exportText);
		Files.writeString(truth, truthText);
		ValidationEvaluator.main(new String[]{export.toString(), truth.toString(), output.toString()});
		return new ObjectMapper().readTree(Files.readString(output.resolve("metrics.json")));
	}
}
