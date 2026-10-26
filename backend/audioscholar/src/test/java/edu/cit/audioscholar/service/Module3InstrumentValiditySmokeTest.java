package edu.cit.audioscholar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.controller.ValidationExportController;
import edu.cit.audioscholar.model.MultiSourceJob;
import edu.cit.audioscholar.model.ProcessingStatus;
import edu.cit.audioscholar.model.SourceFile;
import edu.cit.audioscholar.model.SourceKind;
import edu.cit.audioscholar.model.Summary;

/**
 * Deterministic worker smoke test for the complete Module 3 evidence path. The
 * provider and persistence boundaries are in-memory so this test cannot mutate
 * production data, while PDFBox/POI extraction and the production
 * orchestration, semantic evidence, attribution, alignment, event, and export
 * code remain real.
 */
class Module3InstrumentValiditySmokeTest {

	@TempDir
	Path tempDir;

	@ParameterizedTest(name = "controlled {0}-source batch completes with coherent evidence")
	@ValueSource(ints = {2, 3, 5})
	void controlledBatchesCompleteWithInstrumentReadyEvidence(int sourceCount) throws Exception {
		Harness harness = new Harness(sourceCount);

		MultiSourceJob accepted = harness.service.createAndProcess("smoke-user", harness.mediaFiles,
				harness.documentFiles, "Controlled smoke batch", "Synthetic evidence only", "NOTES", "WEB");
		assertThat(accepted.getStatus()).isEqualTo(ProcessingStatus.PROCESSING_QUEUED.name());

		harness.service.processQueuedJob(accepted.getJobId());

		Map<String, Object> completed = harness.jobRows.get(accepted.getJobId());
		assertThat(completed.get("status")).isEqualTo(ProcessingStatus.COMPLETE.name());
		assertThat(completed.get("sourceCount")).isEqualTo(sourceCount);
		assertThat(completed.get("finalAttemptCount")).isEqualTo(1);
		assertThat(completed.get("measurementIncomplete")).isEqualTo(false);
		assertThat(completed).containsKeys("finalTranscriptionCompletedAt", "mergeStartedAt",
				"mergedSummaryAvailableAt", "mergeLatencyMs");
		assertThat(((Number) completed.get("mergeLatencyMs")).longValue()).isNotNegative();
		assertThat(harness.distinctStatusHistory()).containsExactly("ACCEPTED", "PROCESSING_QUEUED", "PROCESSING",
				"SUMMARIZING", "COMPLETE");

		List<Map<String, Object>> sourceRows = harness.rows("sourceFiles");
		assertThat(sourceRows).hasSize(sourceCount).allSatisfy(source -> {
			assertThat(source.get("storageObjectId")).isNotNull();
			assertThat(source.get("retrievedAt")).isNotNull();
			assertThat(source.get("transcriptionCompletedAt")).isNotNull();
			assertThat(source.get("transcriptText")).isInstanceOf(String.class);
		});
		if (sourceCount >= 3)
			assertThat(transcriptFor(sourceRows, "lecture.pdf")).contains("PDF evidence covers chlorophyll absorption");
		if (sourceCount == 5)
			assertThat(transcriptFor(sourceRows, "lecture.pptx"))
					.contains("PPT evidence explains quantum energy levels");

		List<Map<String, Object>> events = harness.rows("validationEvents");
		assertThat(eventTypes(events)).containsSubsequence("BATCH_SUBMITTED", "CONFIG_CONFIRMED", "BATCH_VALIDATED",
				"JOB_ACCEPTED", "ALL_SOURCES_UPLOADED", "JOB_QUEUED", "PROCESSING_STARTED",
				"ALL_TRANSCRIPTIONS_COMPLETED", "DEDUPLICATION_COMPLETED", "MERGED_SUMMARY_AVAILABLE", "JOB_COMPLETED");
		assertThat(events.stream().filter(e -> "SOURCE_RETRIEVED".equals(e.get("eventType")))).hasSize(sourceCount);
		assertThat(events.stream().filter(e -> "SOURCE_TRANSCRIPTION_COMPLETED".equals(e.get("eventType"))))
				.hasSize(sourceCount);
		assertThat(events.stream().filter(e -> "JOB_COMPLETED".equals(e.get("eventType")))).hasSize(1);
		assertThat(events).allSatisfy(event -> assertThat(event).containsKeys("eventId", "occurredAt", "workflowId",
				"applicationVersion", "objectiveCodes"));

		assertThat(harness.rows("sourceKeyPoints")).hasSizeGreaterThanOrEqualTo(sourceCount);
		assertThat(harness.rows("semanticDecisions")).isNotEmpty();
		assertThat(harness.rows("mergedKeyPointLineage")).isNotEmpty();
		assertThat(harness.rows("sourceAttributions")).isNotEmpty();
		assertThat(harness.rows("crossSourceAlignments")).hasSize(1);
		assertThat(harness.rows("multiSourceStageRuns")).extracting(row -> row.get("stage"))
				.containsExactlyInAnyOrder("SOURCE_KEY_POINT_EXTRACTION", "DEDUPLICATION", "ATTRIBUTION", "ALIGNMENT");

		JsonNode manifest = harness.exportManifest();
		assertThat(manifest.path("integrityIssues")).isEmpty();
		assertThat(manifest.path("measurementIncompleteWorkflows")).isEmpty();
		assertThat(manifest.path("objectiveEvidenceStatus").path("9").asText()).isEqualTo("COMPLETE");
		assertThat(manifest.path("objectiveEvidenceStatus").path("12").asText()).isEqualTo("COMPLETE");
		for (String objective : List.of("10", "11", "13", "14"))
			assertThat(manifest.path("objectiveEvidenceStatus").path(objective).asText())
					.isEqualTo("REVIEWER_EVIDENCE_REQUIRED");
		assertThat(manifest.path("module3OutcomeMetrics").path("numeratorCompleted").asInt()).isEqualTo(1);
		assertThat(manifest.path("module3OutcomeMetrics").path("denominatorValid").asInt()).isEqualTo(1);
		assertThat(manifest.path("sha256").size()).isGreaterThan(10);
	}

	private String transcriptFor(List<Map<String, Object>> rows, String filename) {
		return rows.stream().filter(row -> filename.equals(row.get("fileName")))
				.map(row -> (String) row.get("transcriptText")).findFirst().orElseThrow();
	}

	private List<String> eventTypes(List<Map<String, Object>> events) {
		return events.stream().map(event -> String.valueOf(event.get("eventType"))).toList();
	}

	private final class Harness {
		private final ObjectMapper json = new ObjectMapper();
		private final Map<String, LinkedHashMap<String, Map<String, Object>>> database = new LinkedHashMap<>();
		private final Map<String, Map<String, Object>> jobRows = collection("multiSourceJobs");
		private final Map<String, Path> storedObjects = new LinkedHashMap<>();
		private final List<String> statusHistory = new ArrayList<>();
		private final List<MultipartFile> mediaFiles = new ArrayList<>();
		private final List<MultipartFile> documentFiles = new ArrayList<>();
		private final FirebaseService firebase = mock(FirebaseService.class);
		private final MultiSourceJobRepository jobs = mock(MultiSourceJobRepository.class);
		private final SourceFileService sourceFiles = mock(SourceFileService.class);
		private final GeminiService gemini = mock(GeminiService.class);
		private final MultiSourceJobService service;

		private Harness(int sourceCount) throws Exception {
			configurePersistence();
			configureSources(sourceCount);
			configureProvider();

			ValidationEventService validationEvents = new ValidationEventService(firebase, json, true, 180,
					"module3-smoke-v1");
			SourceTranscriptService transcripts = new SourceTranscriptService(firebase);
			TimedTranscriptSegmentService segments = new TimedTranscriptSegmentService(firebase);
			SemanticEvidenceService semantic = new SemanticEvidenceService(gemini, firebase, json, validationEvents,
					.80, "module3-smoke-v1", "summary-smoke-v1", "semantic-smoke-v1");
			SourceAttributionService attributions = new SourceAttributionService(firebase);
			SummaryService summaries = mock(SummaryService.class);
			when(summaries.createSummary(any(Summary.class))).thenAnswer(invocation -> {
				Summary summary = invocation.getArgument(0);
				put("summaries", summary.getSummaryId(), summary.toMap());
				return summary;
			});
			AudioProcessingGuardrailService guardrails = mock(AudioProcessingGuardrailService.class);
			when(guardrails.validateAudioFile(any(Path.class), anyString()))
					.thenAnswer(invocation -> new AudioProcessingGuardrailService.GuardrailResult(3, 96,
							ValidationEventService.digest(invocation.getArgument(1)), invocation.getArgument(1)));
			QualityReportService quality = mock(QualityReportService.class);
			when(quality.analyzeAndSave(anyString(), any(Path.class), anyString(), anyString(), anyString(),
					anyString())).thenReturn(null);

			service = new MultiSourceJobService(gemini, quality, summaries, new DeduplicationService(), attributions,
					sourceFiles, transcripts, new DocumentTextExtractionService(),
					new MergedSummaryRepository(firebase), jobs, guardrails, tempDir.toString(), "20MB",
					mock(ConfirmedRabbitPublisher.class), validationEvents, segments, semantic);
		}

		private void configurePersistence() {
			when(firebase.saveData(anyString(), anyString(), any())).thenAnswer(invocation -> {
				put(invocation.getArgument(0), invocation.getArgument(1), map(invocation.getArgument(2)));
				return "stored";
			});
			doAnswer(invocation -> {
				put(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
				return null;
			}).when(firebase).createEvidence(anyString(), anyString(), anyMap());
			when(firebase.queryCollection(anyString(), anyString(), any()))
					.thenAnswer(invocation -> rows(invocation.getArgument(0)).stream().filter(row -> java.util.Objects
							.equals(row.get(invocation.getArgument(1)), invocation.getArgument(2))).toList());
			when(firebase.getAllData(anyString())).thenAnswer(invocation -> rows(invocation.getArgument(0)));
			when(firebase.updateDataWithMap(anyString(), anyString(), anyMap())).thenAnswer(invocation -> {
				String collection = invocation.getArgument(0), id = invocation.getArgument(1);
				Map<String, Object> merged = new LinkedHashMap<>(collection(collection).getOrDefault(id, Map.of()));
				merged.putAll(invocation.getArgument(2));
				put(collection, id, merged);
				return "updated";
			});

			when(jobs.save(any(MultiSourceJob.class))).thenAnswer(invocation -> persistJob(invocation.getArgument(0)));
			when(jobs.findById(anyString())).thenAnswer(invocation -> jobRows.get(invocation.getArgument(0)));
			when(jobs.claim(anyString())).thenAnswer(invocation -> {
				String id = invocation.getArgument(0);
				Map<String, Object> current = jobRows.get(id);
				if (current == null || !"PROCESSING_QUEUED".equals(current.get("status")))
					return false;
				Map<String, Object> claimed = new LinkedHashMap<>(current);
				claimed.put("status", "PROCESSING");
				put("multiSourceJobs", id, claimed);
				recordStatus("PROCESSING");
				return true;
			});
			when(jobs.saveIfNonterminal(any(MultiSourceJob.class))).thenAnswer(invocation -> {
				MultiSourceJob job = invocation.getArgument(0);
				Map<String, Object> current = jobRows.get(job.getJobId());
				if (current != null && List.of("COMPLETE", "FAILED").contains(current.get("status")))
					return false;
				persistJob(job);
				return true;
			});
			when(jobs.transitionTerminal(any(MultiSourceJob.class), anyString(), anyString(), anyList(), anyMap()))
					.thenAnswer(invocation -> {
						MultiSourceJob job = invocation.getArgument(0);
						Map<String, Object> current = jobRows.get(job.getJobId());
						if (current != null && List.of("COMPLETE", "FAILED").contains(current.get("status")))
							return false;
						Map<String, Object> terminal = new LinkedHashMap<>(job.toMap());
						terminal.put("terminalEventPending", true);
						put("multiSourceJobs", job.getJobId(), terminal);
						recordStatus(job.getStatus());
						return true;
					});
			doAnswer(invocation -> {
				String id = invocation.getArgument(0);
				Map<String, Object> terminal = new LinkedHashMap<>(jobRows.get(id));
				terminal.put("terminalEventPending", false);
				put("multiSourceJobs", id, terminal);
				return null;
			}).when(jobs).markTerminalEventPublished(anyString());
		}

		private void configureSources(int sourceCount) throws Exception {
			mediaFiles.add(media("source-a.mp3"));
			mediaFiles.add(media("source-b.mp3"));
			if (sourceCount == 5)
				mediaFiles.add(media("source-c.mp3"));
			if (sourceCount >= 3)
				documentFiles.add(new MockMultipartFile("documentFiles", "lecture.pdf", "application/pdf", pdfBytes()));
			if (sourceCount == 5)
				documentFiles.add(new MockMultipartFile("documentFiles", "lecture.pptx",
						"application/vnd.openxmlformats-officedocument.presentationml.presentation", pptxBytes()));

			AtomicInteger sequence = new AtomicInteger();
			when(sourceFiles.createSourceFile(anyString(), anyString(), any(SourceKind.class), any(MultipartFile.class),
					any(Path.class))).thenAnswer(invocation -> {
						int index = sequence.getAndIncrement();
						MultipartFile upload = invocation.getArgument(3);
						Path local = invocation.getArgument(4);
						String id = "source-" + (char) ('a' + index);
						String objectId = "object-" + id;
						Path stored = tempDir.resolve("stored-" + id + extension(upload.getOriginalFilename()));
						Files.copy(local, stored, StandardCopyOption.REPLACE_EXISTING);
						storedObjects.put(objectId, stored);
						SourceFile source = new SourceFile();
						source.setSourceFileId(id);
						source.setJobId(invocation.getArgument(0));
						source.setSourceLabel(invocation.getArgument(1));
						source.setSourceKind(((SourceKind) invocation.getArgument(2)).name());
						source.setFileName(upload.getOriginalFilename());
						source.setContentType(upload.getContentType());
						source.setFileType(upload.getContentType());
						source.setFileSize(upload.getSize());
						source.setStorageObjectId(objectId);
						source.setUploadStatus("UPLOADED");
						source.setUploadCompletedAt(new Date());
						return source;
					});
			when(sourceFiles.save(any(SourceFile.class))).thenAnswer(invocation -> {
				SourceFile source = invocation.getArgument(0);
				put("sourceFiles", source.getSourceFileId(), source.toMap());
				return source;
			});
			when(sourceFiles.findByJobId(anyString())).thenAnswer(invocation -> rows("sourceFiles").stream()
					.filter(row -> invocation.getArgument(0).equals(row.get("jobId"))).toList());
			doAnswer(invocation -> {
				Path stored = storedObjects.get(invocation.getArgument(0));
				if (stored == null)
					throw new java.io.IOException("missing smoke object");
				Files.copy(stored, invocation.getArgument(1), StandardCopyOption.REPLACE_EXISTING);
				return null;
			}).when(sourceFiles).retrieveToPath(anyString(), any(Path.class));
		}

		private void configureProvider() throws Exception {
			when(gemini.callGeminiTranscriptionAPIWithFallback(any(Path.class), anyString()))
					.thenAnswer(invocation -> switch ((String) invocation.getArgument(1)) {
						case "source-a.mp3" ->
							"Shared lecture claim. Tachycardia means rapid heartbeat. Alpha unique insight.";
						case "source-b.mp3" ->
							"Shared lecture claim. Rapid heart rate is tachycardia. Beta unique insight.";
						default -> "Gamma unique insight.";
					});
			when(gemini.generateTranscriptOnlySummary(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
				String id = invocation.getArgument(1);
				return switch (id) {
					case "source-a" ->
						points("Shared lecture claim", "Tachycardia means rapid heartbeat", "Alpha unique insight");
					case "source-b" ->
						points("Shared lecture claim", "Rapid heart rate is tachycardia", "Beta unique insight");
					case "source-c" -> points(mediaFiles.size() == 3
							? "Gamma unique insight"
							: "PDF evidence covers chlorophyll absorption");
					case "source-d" -> points("PDF evidence covers chlorophyll absorption");
					case "source-e" -> points("PPT evidence explains quantum energy levels");
					default ->
						"{\"summaryText\":\"Controlled merged summary\",\"keyPoints\":[],\"topics\":[],\"glossary\":[],\"flashcards\":[]}";
				};
			});
			when(gemini.callSimpleTextAPI(anyString())).thenAnswer(invocation -> {
				String prompt = invocation.getArgument(0);
				boolean paraphrase = prompt.contains("Tachycardia means rapid heartbeat")
						&& prompt.contains("Rapid heart rate is tachycardia");
				return paraphrase
						? "{\"duplicate\":true,\"confidence\":0.94}"
						: "{\"duplicate\":false,\"confidence\":0.12}";
			});
		}

		private JsonNode exportManifest() throws Exception {
			ValidationExportController exporter = new ValidationExportController(firebase, json);
			byte[] body = exporter.export(Instant.now().minusSeconds(3600).toString(),
					Instant.now().plusSeconds(3600).toString(), null, "WEB", "jsonl", false).getBody();
			return json.readTree(new String(body, StandardCharsets.UTF_8).lines().findFirst().orElseThrow())
					.path("data");
		}

		private MultiSourceJob persistJob(MultiSourceJob job) {
			put("multiSourceJobs", job.getJobId(), job.toMap());
			recordStatus(job.getStatus());
			return job;
		}

		private void recordStatus(String status) {
			if (statusHistory.isEmpty() || !status.equals(statusHistory.get(statusHistory.size() - 1)))
				statusHistory.add(status);
		}

		private List<String> distinctStatusHistory() {
			return List.copyOf(statusHistory);
		}

		private Map<String, LinkedHashMap<String, Map<String, Object>>> database() {
			return database;
		}

		@SuppressWarnings("unchecked")
		private Map<String, Object> map(Object value) {
			return value instanceof Map<?, ?> raw ? new LinkedHashMap<>((Map<String, Object>) raw) : Map.of();
		}

		private LinkedHashMap<String, Map<String, Object>> collection(String name) {
			return database().computeIfAbsent(name, ignored -> new LinkedHashMap<>());
		}

		private void put(String collection, String id, Map<String, Object> row) {
			collection(collection).put(id, new LinkedHashMap<>(row));
		}

		private List<Map<String, Object>> rows(String collection) {
			return collection(collection).values().stream().map(LinkedHashMap::new)
					.map(row -> (Map<String, Object>) row).toList();
		}

		private MockMultipartFile media(String filename) {
			return new MockMultipartFile("mediaFiles", filename, "audio/mpeg", ("synthetic-" + filename).getBytes());
		}

		private byte[] pdfBytes() throws Exception {
			Path pdf = tempDir.resolve("fixture.pdf");
			try (PDDocument document = new PDDocument()) {
				PDPage page = new PDPage();
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					content.beginText();
					content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					content.newLineAtOffset(50, 700);
					content.showText("PDF evidence covers chlorophyll absorption.");
					content.endText();
				}
				document.save(pdf.toFile());
			}
			return Files.readAllBytes(pdf);
		}

		private byte[] pptxBytes() throws Exception {
			try (XMLSlideShow presentation = new XMLSlideShow();
					ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				XSLFSlide slide = presentation.createSlide();
				XSLFTextBox textBox = slide.createTextBox();
				textBox.setText("PPT evidence explains quantum energy levels.");
				presentation.write(output);
				return output.toByteArray();
			}
		}

		private String points(String... points) throws Exception {
			return json.writeValueAsString(Map.of("keyPoints", List.of(points)));
		}

		private String extension(String filename) {
			int dot = filename.lastIndexOf('.');
			return dot < 0 ? "" : filename.substring(dot);
		}
	}
}
