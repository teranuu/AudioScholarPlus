package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.exception.StorageRetrievalException;
import edu.cit.audioscholar.exception.StorageUploadException;

@ExtendWith(MockitoExtension.class)
class NhostStorageServiceTest {

	private static final String STORAGE_URL = "https://test-subdomain.storage.ap-southeast-1.nhost.run/v1/files";
	private static final String ADMIN_SECRET = "test-admin-secret";
	private static final String BUCKET_ID = "audioscholar";

	@Mock
	private RestTemplate restTemplate;

	private NhostStorageService nhostStorageService;

	@BeforeEach
	void setUp() {
		nhostStorageService = new NhostStorageService(restTemplate, STORAGE_URL, ADMIN_SECRET, BUCKET_ID,
				new ObjectMapper());
		ReflectionTestUtils.setField(nhostStorageService, "uploadRetryDelayMs", 0L);
		ReflectionTestUtils.setField(nhostStorageService, "downloadRetryDelayMs", 0L);
	}

	@Test
	void uploadFile_parsesProcessedFilesResponseAndSendsNhostMultipartFields() throws Exception {
		Path tempFilePath = Files.createTempFile("nhost-upload", ".mp3");
		Files.writeString(tempFilePath, "dummy audio");
		File tempFile = tempFilePath.toFile();

		try {
			String responseBody = """
					{
					  "processedFiles": [
					    {
					      "id": "file-id-123",
					      "name": "lecture.mp3",
					      "bucketId": "audioscholar"
					    }
					  ]
					}
					""";
			when(restTemplate.exchange(eq(STORAGE_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
					.thenReturn(new ResponseEntity<>(responseBody, HttpStatus.CREATED));

			String fileId = nhostStorageService.uploadFile(tempFile, "lecture.mp3", "audio/mpeg");

			assertEquals("file-id-123", fileId);

			ArgumentCaptor<HttpEntity> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
			verify(restTemplate).exchange(eq(STORAGE_URL), eq(HttpMethod.POST), requestCaptor.capture(),
					eq(String.class));

			HttpEntity<?> request = requestCaptor.getValue();
			assertEquals(ADMIN_SECRET, request.getHeaders().getFirst("x-hasura-admin-secret"));
			assertNotNull(request.getBody());
			assertInstanceOf(MultiValueMap.class, request.getBody());
			@SuppressWarnings("unchecked")
			MultiValueMap<String, Object> body = (MultiValueMap<String, Object>) request.getBody();
			assertTrue(body.containsKey("file[]"));
			assertEquals(BUCKET_ID, body.getFirst("bucket-id"));

			Object filePart = body.getFirst("file[]");
			assertInstanceOf(HttpEntity.class, filePart);
			HttpEntity<?> fileEntity = (HttpEntity<?>) filePart;
			HttpHeaders fileHeaders = fileEntity.getHeaders();
			assertEquals("file[]", fileHeaders.getContentDisposition().getName());
			assertEquals("lecture.mp3", fileHeaders.getContentDisposition().getFilename());
		} finally {
			Files.deleteIfExists(tempFilePath);
		}
	}

	@Test
	void uploadFile_retriesTransientServerErrorAndThenSucceeds() throws Exception {
		Path tempFilePath = Files.createTempFile("nhost-retry", ".mp3");
		Files.writeString(tempFilePath, "dummy audio");
		String errorBody = "{\"error\":{\"data\":null,\"message\":\"an internal server error occurred\"},\"processedFiles\":[]}";
		HttpServerErrorException serverError = HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
				"Internal Server Error", HttpHeaders.EMPTY, errorBody.getBytes(StandardCharsets.UTF_8),
				StandardCharsets.UTF_8);
		String successBody = "{\"processedFiles\":[{\"id\":\"file-id-123\"}]}";

		when(restTemplate.exchange(eq(STORAGE_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
				.thenThrow(serverError).thenReturn(new ResponseEntity<>(successBody, HttpStatus.CREATED));

		try {
			assertEquals("file-id-123",
					nhostStorageService.uploadFile(tempFilePath.toFile(), "lecture.mp3", "audio/mpeg"));
			verify(restTemplate, times(2)).exchange(eq(STORAGE_URL), eq(HttpMethod.POST), any(HttpEntity.class),
					eq(String.class));
		} finally {
			Files.deleteIfExists(tempFilePath);
		}
	}

	@Test
	void uploadFile_parsesNestedErrorAndDoesNotRetryClientError() throws Exception {
		Path tempFilePath = Files.createTempFile("nhost-invalid", ".mp3");
		Files.writeString(tempFilePath, "dummy audio");
		String errorBody = "{\"error\":{\"data\":null,\"message\":\"file is too large\"},\"processedFiles\":[]}";
		HttpClientErrorException clientError = HttpClientErrorException.create(HttpStatus.PAYLOAD_TOO_LARGE,
				"Payload Too Large", HttpHeaders.EMPTY, errorBody.getBytes(StandardCharsets.UTF_8),
				StandardCharsets.UTF_8);

		when(restTemplate.exchange(eq(STORAGE_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
				.thenThrow(clientError);

		try {
			StorageUploadException failure = assertThrows(StorageUploadException.class,
					() -> nhostStorageService.uploadFile(tempFilePath.toFile(), "lecture.mp3", "audio/mpeg"));
			assertEquals(413, failure.getStatusCode());
			assertFalse(failure.isRetryable());
			assertTrue(failure.getMessage().contains("file is too large"));
			verify(restTemplate).exchange(eq(STORAGE_URL), eq(HttpMethod.POST), any(HttpEntity.class),
					eq(String.class));
		} finally {
			Files.deleteIfExists(tempFilePath);
		}
	}

	@Test
	void downloadFileToPath_sendsAdminSecretHeaderAndWritesResponseBody() throws Exception {
		String fileId = "9606de1b-3757-488f-b5d3-293002ccb1de";
		String downloadUrl = STORAGE_URL + "/" + fileId;
		Path targetPath = Files.createTempFile("nhost-download", ".mp3");
		Files.deleteIfExists(targetPath);

		ClientHttpRequest clientHttpRequest = mock(ClientHttpRequest.class);
		HttpHeaders requestHeaders = new HttpHeaders();
		when(clientHttpRequest.getHeaders()).thenReturn(requestHeaders);

		ClientHttpResponse clientHttpResponse = mock(ClientHttpResponse.class);
		when(clientHttpResponse.getStatusCode()).thenReturn(HttpStatus.OK);
		when(clientHttpResponse.getBody()).thenReturn(new ByteArrayInputStream("downloaded audio".getBytes()));

		when(restTemplate.execute(eq(new URI(downloadUrl)), eq(HttpMethod.GET), any(RequestCallback.class),
				any(ResponseExtractor.class))).thenAnswer(invocation -> {
					RequestCallback requestCallback = invocation.getArgument(2);
					@SuppressWarnings("unchecked")
					ResponseExtractor<Long> responseExtractor = invocation.getArgument(3);

					requestCallback.doWithRequest(clientHttpRequest);
					return responseExtractor.extractData(clientHttpResponse);
				});

		try {
			nhostStorageService.downloadFileToPath(fileId, targetPath);

			assertEquals(ADMIN_SECRET, requestHeaders.getFirst("x-hasura-admin-secret"));
			assertEquals("downloaded audio", Files.readString(targetPath));
		} finally {
			Files.deleteIfExists(targetPath);
		}
	}

	@Test
	void downloadFileToPath_refreshesUnauthorizedRequestOnceThenFailsPermanently() throws Exception {
		String fileId = "private-object";
		Path targetPath = Files.createTempFile("nhost-denied", ".mp3");
		HttpClientErrorException forbidden = HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
				HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
		when(restTemplate.execute(eq(URI.create(STORAGE_URL + "/" + fileId)), eq(HttpMethod.GET),
				any(RequestCallback.class), any(ResponseExtractor.class))).thenThrow(forbidden);

		try {
			StorageRetrievalException failure = assertThrows(StorageRetrievalException.class,
					() -> nhostStorageService.downloadFileToPath(fileId, targetPath));
			assertEquals("STORAGE_ACCESS_DENIED", failure.getFailureCode());
			assertFalse(failure.isRetryable());
			verify(restTemplate, times(2)).execute(eq(URI.create(STORAGE_URL + "/" + fileId)), eq(HttpMethod.GET),
					any(RequestCallback.class), any(ResponseExtractor.class));
		} finally {
			Files.deleteIfExists(targetPath);
		}
	}

	@Test
	void downloadFileToPath_doesNotRetryMissingObject() throws Exception {
		String fileId = "missing-object";
		Path targetPath = Files.createTempFile("nhost-missing", ".mp3");
		HttpClientErrorException notFound = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
				HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
		when(restTemplate.execute(eq(URI.create(STORAGE_URL + "/" + fileId)), eq(HttpMethod.GET),
				any(RequestCallback.class), any(ResponseExtractor.class))).thenThrow(notFound);

		try {
			StorageRetrievalException failure = assertThrows(StorageRetrievalException.class,
					() -> nhostStorageService.downloadFileToPath(fileId, targetPath));
			assertEquals("STORAGE_OBJECT_NOT_FOUND", failure.getFailureCode());
			verify(restTemplate).execute(eq(URI.create(STORAGE_URL + "/" + fileId)), eq(HttpMethod.GET),
					any(RequestCallback.class), any(ResponseExtractor.class));
		} finally {
			Files.deleteIfExists(targetPath);
		}
	}

	@Test
	void downloadFileToPath_rejectsInvalidObjectIdentifierWithoutRequest() throws Exception {
		Path targetPath = Files.createTempFile("nhost-invalid-id", ".mp3");
		try {
			StorageRetrievalException failure = assertThrows(StorageRetrievalException.class,
					() -> nhostStorageService.downloadFileToPath("../object?token=secret", targetPath));
			assertEquals("STORAGE_INVALID_IDENTIFIER", failure.getFailureCode());
			assertFalse(failure.isRetryable());
			verifyNoInteractions(restTemplate);
		} finally {
			Files.deleteIfExists(targetPath);
		}
	}

	@Test
	void downloadFileToPath_capsRetriesFor429TimeoutAndServerErrors() throws Exception {
		assertTransientFailureRetried(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Rate limited",
				HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8), "STORAGE_RETRIEVAL_FAILED");
		assertTransientFailureRetried(new ResourceAccessException("timed out"), "STORAGE_TIMEOUT");
		assertTransientFailureRetried(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable",
				HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8), "STORAGE_RETRIEVAL_FAILED");
	}

	private void assertTransientFailureRetried(RuntimeException providerFailure, String expectedCode) throws Exception {
		reset(restTemplate);
		String fileId = "transient-object";
		Path targetPath = Files.createTempFile("nhost-transient", ".mp3");
		when(restTemplate.execute(eq(URI.create(STORAGE_URL + "/" + fileId)), eq(HttpMethod.GET),
				any(RequestCallback.class), any(ResponseExtractor.class))).thenThrow(providerFailure);
		try {
			StorageRetrievalException failure = assertThrows(StorageRetrievalException.class,
					() -> nhostStorageService.downloadFileToPath(fileId, targetPath));
			assertEquals(expectedCode, failure.getFailureCode());
			verify(restTemplate, times(3)).execute(eq(URI.create(STORAGE_URL + "/" + fileId)), eq(HttpMethod.GET),
					any(RequestCallback.class), any(ResponseExtractor.class));
		} finally {
			Files.deleteIfExists(targetPath);
		}
	}
}
