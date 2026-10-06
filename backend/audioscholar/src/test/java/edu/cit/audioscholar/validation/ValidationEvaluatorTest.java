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
