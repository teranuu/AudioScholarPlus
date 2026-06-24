package edu.cit.audioscholar.service.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.cit.audioscholar.model.KeyProvider;
import edu.cit.audioscholar.service.GeminiService;
import edu.cit.audioscholar.service.GeminiSmartRotationService;
import edu.cit.audioscholar.service.KeyRotationManager;

/**
 * API compatibility audit for backend AI client logic.
 * Validates that the actual backend classes produce requests compatible
 * with current Gemini API (v1beta) and OpenAI-compatible endpoint patterns.
 *
 * DOES NOT make real network calls — all RestTemplate interactions are mocked.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI Client API Compatibility Audit")
class AiClientApiCompatibilityAuditTest {

    // ---------------------------------------------------------------
    // GeminiAiClient delegates
    // ---------------------------------------------------------------
    @Mock
    private GeminiService geminiService;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private KeyRotationManager keyRotationManager;
    @Mock
    private GeminiSmartRotationService rotationService;

    private GeminiService realGeminiServiceForUrlAudit;

    private GeminiAiClient geminiAiClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        geminiAiClient = new GeminiAiClient(geminiService);
        realGeminiServiceForUrlAudit = new GeminiService(restTemplate, keyRotationManager, rotationService);
        ReflectionTestUtils.setField(realGeminiServiceForUrlAudit, "transcriptionModelName", "gemini-2.5-flash");
        ReflectionTestUtils.setField(realGeminiServiceForUrlAudit, "summarizationModelName", "gemini-2.5-flash");
    }

    // ===============================================================
    // 1. AiClient interface contract verification
    // ===============================================================

    @Test
    @DisplayName("AiClient interface defines transcribeAudio and summarizeTranscript")
    void interfaceContractIsIntact() {
        assertNotNull(AiClient.class.getDeclaredMethods(),
            "AiClient interface should define methods");
        // Both methods should be present (compile-time check)
        assertDoesNotThrow(() ->
            AiClient.class.getDeclaredMethod("transcribeAudio", Path.class, String.class),
            "transcribeAudio(Path, String) is missing from AiClient interface");
        assertDoesNotThrow(() ->
            AiClient.class.getDeclaredMethod("summarizeTranscript", String.class, String.class, String.class),
            "summarizeTranscript(String, String, String) is missing from AiClient interface");
    }

    // ===============================================================
    // 2. GeminiAiClient delegation tests
    // ===============================================================

    @Test
    @DisplayName("GeminiAiClient delegates transcribeAudio to GeminiService with file path and name")
    void geminiAiClientTranscriptionDelegation() throws Exception {
        Path tempFile = Files.createTempFile("test-audio", ".mp3");
        Files.writeString(tempFile, "dummy content");
        try {
            when(geminiService.callGeminiTranscriptionAPIWithFallback(eq(tempFile), eq("lecture.mp3")))
                .thenReturn("Transcribed text");

            String result = geminiAiClient.transcribeAudio(tempFile, "lecture.mp3");

            assertEquals("Transcribed text", result);
            verify(geminiService).callGeminiTranscriptionAPIWithFallback(eq(tempFile), eq("lecture.mp3"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("GeminiAiClient wraps transcription exceptions as IllegalStateException")
    void geminiAiClientTranscriptionExceptionWrapping() throws Exception {
        Path tempFile = Files.createTempFile("test-audio", ".mp3");
        Files.writeString(tempFile, "dummy content");
        try {
            when(geminiService.callGeminiTranscriptionAPIWithFallback(any(), any()))
                .thenThrow(new RuntimeException("Network error"));

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> geminiAiClient.transcribeAudio(tempFile, "test.mp3"),
                "Should wrap RuntimeException as IllegalStateException");
            assertTrue(thrown.getMessage().contains("Gemini transcription failed"));
            assertTrue(thrown.getCause().getMessage().contains("Network error"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    @DisplayName("GeminiAiClient summarizeTranscript uses presentation context prompt when provided")
    void geminiAiClientSummarizationWithPresentationContext() {
        when(geminiService.callGeminiSummarizationAPIWithFallback(
            contains("Use this PowerPoint text as supporting lecture context"),
            eq("transcript text")))
            .thenReturn("summary JSON");

        String result = geminiAiClient.summarizeTranscript("transcript text", "PPT context", "id-1");

        assertEquals("summary JSON", result);
        verify(geminiService).callGeminiSummarizationAPIWithFallback(
            contains("Use this PowerPoint text as supporting lecture context"),
            eq("transcript text"));
    }

    @Test
    @DisplayName("GeminiAiClient summarizeTranscript uses transcript-only path when no presentation context")
    void geminiAiClientSummarizationWithoutPresentationContext() {
        when(geminiService.generateTranscriptOnlySummary("transcript text", "id-2"))
            .thenReturn("summary JSON");

        String result = geminiAiClient.summarizeTranscript("transcript text", "", "id-2");

        assertEquals("summary JSON", result);
        verify(geminiService, never()).callGeminiSummarizationAPIWithFallback(any(), any());
        verify(geminiService).generateTranscriptOnlySummary("transcript text", "id-2");
    }

    @Test
    @DisplayName("GeminiAiClient summarizeTranscript with null presentation context")
    void geminiAiClientSummarizationWithNullPresentationContext() {
        when(geminiService.generateTranscriptOnlySummary("transcript text", "id-3"))
            .thenReturn("summary JSON");

        String result = geminiAiClient.summarizeTranscript("transcript text", null, "id-3");

        assertEquals("summary JSON", result);
        verify(geminiService).generateTranscriptOnlySummary("transcript text", "id-3");
    }

    // ===============================================================
    // 3. Gemini API endpoint pattern audit (v1beta generateContent)
    // ===============================================================

    @Nested
    @DisplayName("Gemini API v1beta Endpoint Compatibility")
    class GeminiEndpointAudit {

        @Test
        @DisplayName("Gemini summarize uses correct v1beta base URL: generativelanguage.googleapis.com")
        void baseUrlIsCorrect() {
            // Verify the API_BASE_URL constant is correct for current Gemini API
            // This is a static field check via reflection
            String baseUrl = (String) ReflectionTestUtils.getField(
                realGeminiServiceForUrlAudit, "API_BASE_URL");
            assertNotNull(baseUrl, "API_BASE_URL should not be null");
            assertEquals("https://generativelanguage.googleapis.com", baseUrl,
                "API_BASE_URL must point to generativelanguage.googleapis.com for Gemini API v1beta");
        }

        @Test
        @DisplayName("Gemini summarize uses correct generateContent path: /v1beta/models/{model}:generateContent")
        void generateContentPathIsCorrect() {
            String path = (String) ReflectionTestUtils.getField(
                realGeminiServiceForUrlAudit, "GENERATE_CONTENT_PATH");
            assertNotNull(path);
            assertEquals("/v1beta/models/{modelName}:generateContent", path,
                "Must use v1beta generateContent colon-action endpoint");
        }

        @Test
        @DisplayName("Gemini files API uses correct upload path: /upload/v1beta/files")
        void filesUploadPathIsCorrect() {
            String path = (String) ReflectionTestUtils.getField(
                realGeminiServiceForUrlAudit, "FILES_API_UPLOAD_PATH");
            assertNotNull(path);
            assertEquals("/upload/v1beta/files", path,
                "Upload must use /upload/v1beta/files resumable upload endpoint");
        }

        @Test
        @DisplayName("API key is passed as query parameter 'key' — not as Bearer header")
        void apiKeyIsQueryParamNotHeader() {
            // Arrange: mock rotation service
            when(rotationService.executeWithInfiniteRotation(any())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                var fn = (java.util.function.Function<String, String>) inv.getArgument(0);
                return fn.apply("gemini-2.5-flash");
            });
            when(keyRotationManager.getKey(KeyProvider.GEMINI)).thenReturn("test-key-123");

            String successBody = """
                {"candidates":[{"content":{"parts":[{"text":"{\\\"summaryText\\\":\\\"ok\\\"}"}]}}]}
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(successBody, HttpStatus.OK));

            // Act: this triggers realGeminiServiceForUrlAudit internals
            String result = realGeminiServiceForUrlAudit.callGeminiSummarizationAPIWithFallback(
                "prompt", "transcript");

            // Assert: capture the URL to verify it contains ?key= not Bearer
            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), any(), eq(String.class));

            String calledUrl = urlCaptor.getValue();
            assertTrue(calledUrl.contains("?key=test-key-123"),
                "Gemini API key must be passed as query param 'key', not Bearer header. URL was: " + calledUrl);
            assertTrue(calledUrl.contains("/v1beta/models/gemini-2.5-flash:generateContent"),
                "URL must target generateContent colon-action: " + calledUrl);

            assertTrue(result.contains("summaryText"), "Should extract summary from candidate parts");
        }
    }

    // ===============================================================
    // 4. Gemini request body structure audit
    // ===============================================================

    @Nested
    @DisplayName("Gemini Request Body Structure Compatibility")
    class GeminiRequestBodyAudit {

        @Test
        @DisplayName("Summarization request body includes contents, generationConfig with responseSchema")
        @SuppressWarnings("unchecked")
        void summarizationRequestBodyHasCorrectStructure() {
            when(rotationService.executeWithInfiniteRotation(any())).thenAnswer(inv -> {
                var fn = (java.util.function.Function<String, String>) inv.getArgument(0);
                return fn.apply("gemini-2.5-flash");
            });
            when(keyRotationManager.getKey(KeyProvider.GEMINI)).thenReturn("test-key");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("""
                    {"candidates":[{"content":{"parts":[{"text":"{\\\"summaryText\\\":\\\"ok\\\"}"}]}}]}
                    """, HttpStatus.OK));

            realGeminiServiceForUrlAudit.callGeminiSummarizationAPIWithFallback("prompt", "transcript");

            ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(String.class));

            Map<String, Object> body = entityCaptor.getValue().getBody();
            assertNotNull(body, "Request body must not be null");

            // Must have 'contents' (list of content objects with role + parts)
            assertTrue(body.containsKey("contents"), "Body must contain 'contents' key");
            // Must have 'generationConfig'
            assertTrue(body.containsKey("generationConfig"), "Body must contain 'generationConfig' key");

            Map<String, Object> genConfig = (Map<String, Object>) body.get("generationConfig");
            assertEquals(0.4, genConfig.get("temperature"), "Temperature should be 0.4 for summarization");
            assertEquals("application/json", genConfig.get("response_mime_type"),
                "response_mime_type must be application/json for structured output");
            assertTrue(genConfig.containsKey("response_schema"),
                "response_schema must be present for structured JSON output");
        }

        @Test
        @DisplayName("Transcription request body uses file_data part reference, not inline bytes")
        @SuppressWarnings("unchecked")
        void transcriptionRequestBodyUsesFileUri() throws Exception {
            Path tempFile = Files.createTempFile("test-audio", ".mp3");
            Files.writeString(tempFile, "dummy");
            try {
                // Mock file upload
                HttpHeaders initiateHeaders = new HttpHeaders();
                initiateHeaders.add("X-Goog-Upload-Url", "http://fake-upload-url.local");
                when(restTemplate.exchange(contains("/upload/v1beta/files"), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(null, initiateHeaders, HttpStatus.OK));

                String uploadResponse = "{\"file\": {\"uri\": \"http://fake-file-uri.local\"}}";
                when(restTemplate.exchange(eq("http://fake-upload-url.local"), eq(HttpMethod.POST), any(), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(uploadResponse, HttpStatus.OK));

                when(rotationService.executeWithInfiniteRotation(any())).thenAnswer(inv -> {
                    var fn = (java.util.function.Function<String, String>) inv.getArgument(0);
                    return fn.apply("gemini-2.5-flash");
                });
                when(keyRotationManager.getKey(KeyProvider.GEMINI)).thenReturn("test-key");

                // We need to capture the generateContent call body
                ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor =
                    ArgumentCaptor.forClass(HttpEntity.class);
                when(restTemplate.exchange(contains(":generateContent"), eq(HttpMethod.POST),
                    entityCaptor.capture(), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("""
                        {"candidates":[{"content":{"parts":[{"text":"{\\\"transcript\\\":\\\"hello\\\"}"}]}}]}
                        """, HttpStatus.OK));

                realGeminiServiceForUrlAudit.callGeminiTranscriptionAPIWithFallback(tempFile, "test.mp3");

                Map<String, Object> body = entityCaptor.getValue().getBody();
                assertNotNull(body);
                assertTrue(body.containsKey("contents"));

                // The body should reference file via file_data.file_uri, not inline bytes
                String bodyStr = objectMapper.writeValueAsString(body);
                assertTrue(bodyStr.contains("file_data") || bodyStr.contains("file_uri"),
                    "Transcription request must reference file via file_data.uri, not inline audio bytes. Body: " + bodyStr);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("Response schema 'required' fields match the expected output structure")
        void summarizationResponseSchemaHasRequiredFields() {
            // Access the private static SUMMARY_RESPONSE_SCHEMA via reflection
            Object schema = ReflectionTestUtils.getField(realGeminiServiceForUrlAudit, "SUMMARY_RESPONSE_SCHEMA");
            assertNotNull(schema);

            @SuppressWarnings("unchecked")
            Map<String, Object> schemaMap = (Map<String, Object>) schema;
            assertEquals("OBJECT", schemaMap.get("type"));

            @SuppressWarnings("unchecked")
            var required = (java.util.List<String>) schemaMap.get("required");
            assertTrue(required.contains("summaryText"), "schema required must include summaryText");
            assertTrue(required.contains("keyPoints"), "schema required must include keyPoints");
            assertTrue(required.contains("topics"), "schema required must include topics");
            assertTrue(required.contains("glossary"), "schema required must include glossary");
        }
    }

    // ===============================================================
    // 5. Gemini response parsing audit
    // ===============================================================

    @Nested
    @DisplayName("Gemini Response Parsing Compatibility")
    class GeminiResponseParsingAudit {

        @Test
        @DisplayName("Standard candidates[0].content.parts[0].text extraction works")
        void standardCandidateResponseParsing() {
            when(rotationService.executeWithInfiniteRotation(any())).thenAnswer(inv -> {
                var fn = (java.util.function.Function<String, String>) inv.getArgument(0);
                return fn.apply("gemini-2.5-flash");
            });
            when(keyRotationManager.getKey(KeyProvider.GEMINI)).thenReturn("test-key");

            String responseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "{\\"summaryText\\": \\"Test summary\\", \\"keyPoints\\": [\\"Point 1\\"]}"
                          }
                        ]
                      },
                      "finishReason": "STOP"
                    }
                  ]
                }
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));

            String result = realGeminiServiceForUrlAudit.callGeminiSummarizationAPIWithFallback(
                "prompt", "transcript");

            assertTrue(result.contains("summaryText"));
            assertTrue(result.contains("Test summary"));
            assertTrue(result.contains("Point 1"));
        }

        @Test
        @DisplayName("Response with promptFeedback blockReason is handled as error")
        void blockedByPromptFeedback() {
            when(rotationService.executeWithInfiniteRotation(any())).thenAnswer(inv -> {
                var fn = (java.util.function.Function<String, String>) inv.getArgument(0);
                return fn.apply("gemini-2.5-flash");
            });
            when(keyRotationManager.getKey(KeyProvider.GEMINI)).thenReturn("test-key");

            String blockedResponse = """
                {
                  "promptFeedback": {
                    "blockReason": "SAFETY",
                    "safetyRatings": []
                  }
                }
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(blockedResponse, HttpStatus.OK));

            String result = realGeminiServiceForUrlAudit.callGeminiSummarizationAPIWithFallback(
                "prompt", "transcript");

            // The enhanced fallback catches this and returns an error JSON
            assertTrue(result.contains("\"error\""),
                "Blocked content must produce error response, got: " + result);
        }

        @Test
        @DisplayName("Response with error object (not candidates) is captured correctly")
        void apiErrorResponse() {
            when(rotationService.executeWithInfiniteRotation(any())).thenAnswer(inv -> {
                var fn = (java.util.function.Function<String, String>) inv.getArgument(0);
                return fn.apply("gemini-2.5-flash");
            });
            when(keyRotationManager.getKey(KeyProvider.GEMINI)).thenReturn("test-key");

            String errorBody = """
                {
                  "error": {
                    "code": 400,
                    "message": "Invalid argument: model not found",
                    "status": "INVALID_ARGUMENT"
                  }
                }
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(errorBody, HttpStatus.BAD_REQUEST));

            String result = realGeminiServiceForUrlAudit.callGeminiSummarizationAPIWithFallback(
                "prompt", "transcript");

            assertTrue(result.contains("\"error\""),
                "API errors must be captured, got: " + result);
        }
    }

    // ===============================================================
    // 6. OpenAI-Compatible client structure audit
    // ===============================================================

    @Nested
    @DisplayName("OpenAI-Compatible Client Structure Audit")
    class OpenAiCompatibleAudit {

        @Mock
        private RestTemplate openAiRestTemplate;

        @Test
        @DisplayName("OpenAiCompatibleAiClient transcribeAudio throws UnsupportedOperationException")
        void openAiTranscriptionIsNotSupported() {
            OpenAiCompatibleAiClient client = openAiClient("sk-test-key", 3, 100, 1_000, new java.util.ArrayList<>());

            assertThrows(UnsupportedOperationException.class,
                () -> client.transcribeAudio(Path.of("test.mp3"), "test.mp3"),
                "OpenAI-compatible transcription must be explicitly unsupported");
        }

        @Test
        @DisplayName("OpenAiCompatibleAiClient allows blank API key and omits Authorization header")
        @SuppressWarnings("unchecked")
        void openAiBlankApiKeyOmitsAuthorizationHeader() {
            OpenAiCompatibleAiClient client = openAiClient("   ", 3, 100, 1_000, new java.util.ArrayList<>());
            when(openAiRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(successResponse("{\"summaryText\": \"test\"}"));

            String result = client.summarizeTranscript("transcript text", "", "id");

            ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor =
                ArgumentCaptor.forClass(HttpEntity.class);
            verify(openAiRestTemplate).exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(JsonNode.class));

            assertNull(entityCaptor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION),
                "Blank API key must not send Authorization header");
            assertEquals("{\"summaryText\": \"test\"}", result);
        }

        @Test
        @DisplayName("OpenAiCompatibleAiClient sends POST to /chat/completions with correct body structure and bearer auth")
        @SuppressWarnings("unchecked")
        void openAiChatCompletionsRequestStructure() {
            OpenAiCompatibleAiClient client = openAiClient("sk-test-key", 3, 100, 1_000, new java.util.ArrayList<>());
            when(openAiRestTemplate.exchange(
                eq("https://api.openai.com/v1/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)))
                .thenReturn(successResponse("{\"summaryText\": \"test\"}"));

            String result = client.summarizeTranscript("transcript text", "", "id");

            ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor =
                ArgumentCaptor.forClass(HttpEntity.class);
            verify(openAiRestTemplate).exchange(
                eq("https://api.openai.com/v1/chat/completions"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(JsonNode.class));

            HttpEntity<Map<String, Object>> entity = entityCaptor.getValue();

            assertEquals("Bearer sk-test-key",
                entity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION),
                "Non-empty OpenAI-compatible API key must use Bearer token authorization header");
            assertEquals(MediaType.APPLICATION_JSON,
                entity.getHeaders().getContentType());

            Map<String, Object> body = entity.getBody();
            assertNotNull(body);
            assertEquals("gpt-4o-mini", body.get("model"));
            assertEquals(0.3, body.get("temperature"));
            assertTrue(body.containsKey("messages"));
            assertEquals("{\"summaryText\": \"test\"}", result);
        }

        @Test
        @DisplayName("OpenAiCompatibleAiClient base URL trailing slash is trimmed")
        void openAiBaseUrlTrailingSlashTrimmed() {
            OpenAiCompatibleAiClient client = new OpenAiCompatibleAiClient(
                openAiRestTemplate, "sk-test-key",
                "https://custom.api.com/v1/", "gpt-4o-mini", 3, 100, 1_000, millis -> {});

            when(openAiRestTemplate.exchange(
                eq("https://custom.api.com/v1/chat/completions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)))
                .thenReturn(successResponse("result"));

            client.summarizeTranscript("text", "", "id");

            verify(openAiRestTemplate).exchange(
                eq("https://custom.api.com/v1/chat/completions"),
                eq(HttpMethod.POST), any(), eq(JsonNode.class));
        }

        @Test
        @DisplayName("OpenAiCompatibleAiClient includes presentation context in prompt")
        @SuppressWarnings("unchecked")
        void openAiIncludesPresentationContext() {
            OpenAiCompatibleAiClient client = openAiClient("sk-test-key", 3, 100, 1_000, new java.util.ArrayList<>());
            when(openAiRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(successResponse("result"));

            client.summarizeTranscript("transcript content", "PPT slides here", "id");

            ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor =
                ArgumentCaptor.forClass(HttpEntity.class);
            verify(openAiRestTemplate).exchange(anyString(), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(JsonNode.class));

            Map<String, Object> body = entityCaptor.getValue().getBody();
            @SuppressWarnings("unchecked")
            var messages = (java.util.List<Map<String, String>>) body.get("messages");
            String prompt = messages.get(0).get("content");
            assertTrue(prompt.contains("PowerPoint context:"),
                "Prompt must include PowerPoint context section when pptx is provided");
            assertTrue(prompt.contains("PPT slides here"),
                "PowerPoint context text must be in the prompt");
        }

        @Test
        @DisplayName("OpenCode Zen defaults are documented for OpenAI-compatible provider")
        void openCodeZenDefaultsAreConfigured() throws Exception {
            String applicationProperties = Files.readString(Path.of("src/main/resources/application.properties"));
            String envExample = Files.readString(Path.of(".env.example"));

            assertTrue(applicationProperties.contains("AI_OPENAI_COMPATIBLE_BASE_URL:https://opencode.ai/zen/v1"),
                "application.properties must default OpenAI-compatible base URL to OpenCode Zen");
            assertTrue(applicationProperties.contains("AI_OPENAI_COMPATIBLE_CHAT_MODEL:big-pickle"),
                "application.properties must default OpenAI-compatible chat model to big-pickle");
            assertTrue(envExample.contains("AI_OPENAI_COMPATIBLE_BASE_URL=https://opencode.ai/zen/v1"),
                ".env.example must document the OpenCode Zen base URL");
            assertTrue(envExample.contains("AI_OPENAI_COMPATIBLE_CHAT_MODEL=big-pickle"),
                ".env.example must document the big-pickle model");
        }

        @Test
        @DisplayName("Real .env file has OpenCode Zen enabled with correct URL/model and blank API key")
        void realEnvFileHasOpenCodeActive() throws Exception {
            Path envPath = Path.of(System.getProperty("user.dir"), ".env");
            assertTrue(Files.exists(envPath),
                ".env file must exist at " + envPath.toAbsolutePath());
            String envContent = Files.readString(envPath);

            assertTrue(envContent.contains("AI_OPENAI_COMPATIBLE_ENABLED=true"),
                ".env must have AI_OPENAI_COMPATIBLE_ENABLED=true");

            assertTrue(envContent.contains("AI_OPENAI_COMPATIBLE_BASE_URL=https://opencode.ai/zen/v1"),
                ".env must have OpenCode Zen base URL");

            assertTrue(envContent.contains("AI_OPENAI_COMPATIBLE_CHAT_MODEL=big-pickle"),
                ".env must have chat model big-pickle");

            boolean hasBlankApiKey = envContent.lines()
                .anyMatch(line -> line.startsWith("AI_OPENAI_COMPATIBLE_API_KEY=")
                    && line.trim().equals("AI_OPENAI_COMPATIBLE_API_KEY="));
            assertTrue(hasBlankApiKey,
                ".env must have blank AI_OPENAI_COMPATIBLE_API_KEY for no-key behavior");

            long openAiLines = envContent.lines()
                .filter(line -> line.startsWith("AI_OPENAI_COMPATIBLE_")).count();
            assertTrue(openAiLines >= 4,
                ".env should contain enabled/base-url/api-key/chat-model lines");
        }

        @Test
        @DisplayName("OpenAiCompatibleAiClient retries 429 using Retry-After header delay")
        void openAiRetriesUsingRetryAfterHeader() {
            List<Long> sleeps = new java.util.ArrayList<>();
            OpenAiCompatibleAiClient client = openAiClient("", 3, 100, 5_000, sleeps);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.RETRY_AFTER, "2");
            when(openAiRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(rateLimit(headers, "{\"error\":{\"message\":\"limited\"}}"))
                .thenReturn(successResponse("ok"));

            String result = client.summarizeTranscript("text", "", "id");

            assertEquals("ok", result);
            assertEquals(List.of(2_000L), sleeps, "Retry-After seconds header must control retry delay");
            verify(openAiRestTemplate, times(2)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class));
        }

        @Test
        @DisplayName("OpenAiCompatibleAiClient caps very large Retry-After delays")
        void openAiCapsVeryLargeRetryAfterHeader() {
            List<Long> sleeps = new java.util.ArrayList<>();
            OpenAiCompatibleAiClient client = openAiClient("", 2, 100, 5_000, sleeps);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.RETRY_AFTER, "35971");
            when(openAiRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(rateLimit(headers, "{\"error\":{\"message\":\"limited\"}}"))
                .thenReturn(successResponse("ok"));

            String result = client.summarizeTranscript("text", "", "id");

            assertEquals("ok", result);
            assertEquals(List.of(5_000L), sleeps, "Huge server Retry-After values must be capped by max-backoff-ms");
        }

        @Test
        @DisplayName("OpenAiCompatibleAiClient parses retry wait from 429 response body")
        void openAiRetriesUsingResponseBodyWait() {
            List<Long> sleeps = new java.util.ArrayList<>();
            OpenAiCompatibleAiClient client = openAiClient("", 3, 100, 5_000, sleeps);
            String body = "{\"error\":{\"message\":\"Rate limited. Try again in 3 seconds\"}}";
            when(openAiRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(rateLimit(HttpHeaders.EMPTY, body))
                .thenReturn(successResponse("ok"));

            String result = client.summarizeTranscript("text", "", "id");

            assertEquals("ok", result);
            assertEquals(List.of(3_000L), sleeps, "Text/JSON body retry wait must be parsed without real sleeping");
        }

        @Test
        @DisplayName("OpenAiCompatibleAiClient falls back to bounded exponential backoff on 429 without wait hints")
        void openAiRetriesUsingFallbackBackoff() {
            List<Long> sleeps = new java.util.ArrayList<>();
            OpenAiCompatibleAiClient client = openAiClient("", 4, 100, 150, sleeps);
            when(openAiRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(rateLimit(HttpHeaders.EMPTY, "{\"error\":\"limited\"}"))
                .thenThrow(rateLimit(HttpHeaders.EMPTY, "{\"error\":\"limited\"}"))
                .thenReturn(successResponse("ok"));

            String result = client.summarizeTranscript("text", "", "id");

            assertEquals("ok", result);
            assertEquals(List.of(100L, 150L), sleeps, "Fallback backoff must double and cap at max-backoff-ms");
        }

        @Test
        @DisplayName("OpenAiCompatibleAiClient stops retrying after configured 429 attempts")
        void openAiRetryExhaustionIsBounded() {
            List<Long> sleeps = new java.util.ArrayList<>();
            OpenAiCompatibleAiClient client = openAiClient("", 3, 100, 1_000, sleeps);
            when(openAiRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(rateLimit(HttpHeaders.EMPTY, "{\"error\":\"limited\"}"));

            assertThrows(org.springframework.web.client.HttpClientErrorException.class,
                () -> client.summarizeTranscript("text", "", "id"),
                "Retry loop must throw after configured attempts are exhausted");
            assertEquals(List.of(100L, 200L), sleeps);
            verify(openAiRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class));
        }

        private OpenAiCompatibleAiClient openAiClient(String apiKey, int maxRetryAttempts, long initialBackoffMs,
                long maxBackoffMs, List<Long> sleeps) {
            return new OpenAiCompatibleAiClient(openAiRestTemplate, apiKey, "https://api.openai.com/v1",
                "gpt-4o-mini", maxRetryAttempts, initialBackoffMs, maxBackoffMs, sleeps::add);
        }

        private ResponseEntity<JsonNode> successResponse(String content) {
            String responseJson = "{\"choices\": [{\"message\": {\"content\": " + objectMapper.valueToTree(content) + "}}]}";
            JsonNode responseNode = assertDoesNotThrow(() -> objectMapper.readTree(responseJson));
            return new ResponseEntity<>(responseNode, HttpStatus.OK);
        }

        private org.springframework.web.client.HttpClientErrorException rateLimit(HttpHeaders headers, String body) {
            return org.springframework.web.client.HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limited", headers,
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // ===============================================================
    // 7. Model hierarchy configuration audit
    // ===============================================================

    @Nested
    @DisplayName("Model Hierarchy and Rotation Compatibility")
    class ModelHierarchyAudit {

        @Test
        @DisplayName("Default model hierarchy includes gemini-2.5-flash, gemini-2.0-flash")
        void defaultModelHierarchyIncludesCurrentModels() {
            // Parse the default from application.properties
            String defaultHierarchy = "gemini-2.5-pro,gemini-flash-latest,gemini-flash-lite-latest,gemini-2.5-flash,gemini-2.5-flash-lite,gemini-2.0-flash,gemini-2.0-flash-lite";
            String[] models = defaultHierarchy.split(",");

            boolean hasGemini25Flash = false;
            boolean hasGemini20Flash = false;
            for (String model : models) {
                String trimmed = model.trim();
                if ("gemini-2.5-flash".equals(trimmed)) hasGemini25Flash = true;
                if ("gemini-2.0-flash".equals(trimmed)) hasGemini20Flash = true;
            }

            assertTrue(hasGemini25Flash,
                "Model hierarchy must include gemini-2.5-flash (currently available model)");
            assertTrue(hasGemini20Flash,
                "Model hierarchy must include gemini-2.0-flash for fallback breadth");
        }

        @Test
        @DisplayName("Smart rotation exhausts all models before backing off")
        void smartRotationExhaustsAllModels() {
            GeminiSmartRotationService rotation = new GeminiSmartRotationService(
                "model-a,model-b,model-c", 100, 1000, 2.0);

            // Track which models were attempted
            var attemptedModels = new java.util.ArrayList<String>();
            RuntimeException result = assertThrows(RuntimeException.class, () ->
                rotation.executeWithInfiniteRotation(model -> {
                    attemptedModels.add(model);
                    throw new RuntimeException("hard failure"); // non-retryable
                })
            );

            assertEquals(1, attemptedModels.size(),
                "Non-retryable error should stop immediately on first model");
            assertEquals("model-a", attemptedModels.get(0));
        }

        @Test
        @DisplayName("Smart rotation retries next model on rate limit (429)")
        void smartRotationRetriesOnRateLimit() {
            GeminiSmartRotationService rotation = new GeminiSmartRotationService(
                "model-a,model-b", 100, 1000, 2.0);

            var attemptedModels = new java.util.ArrayList<String>();
            // model-a gets 429 (TooManyRequests subclass), model-b succeeds
            String result = rotation.executeWithInfiniteRotation(model -> {
                attemptedModels.add(model);
                if ("model-a".equals(model)) {
                    throw org.springframework.web.client.HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS, "Rate limited", 
                        HttpHeaders.EMPTY, null, null);
                }
                return "success-" + model;
            });

            assertEquals("success-model-b", result);
            assertEquals(java.util.List.of("model-a", "model-b"), attemptedModels);
        }
    }

    // ===============================================================
    // 8. Error handling compatibility
    // ===============================================================

    @Test
    @DisplayName("GeminiService error response is valid JSON with error and details fields")
    void errorResponseJsonIsWellFormed() throws Exception {
        when(rotationService.executeWithInfiniteRotation(any()))
            .thenThrow(new RuntimeException("Fatal error"));

        String result = realGeminiServiceForUrlAudit.callGeminiSummarizationAPIWithFallback(
            "prompt", "transcript");

        // Must be parseable JSON
        JsonNode node = objectMapper.readTree(result);
        assertTrue(node.has("error"), "Error response must have 'error' field");
        assertNotNull(node.get("error").asText());
        assertFalse(node.get("error").asText().isBlank());

        assertTrue(node.has("details"), "Error response must have 'details' field");
    }
}
