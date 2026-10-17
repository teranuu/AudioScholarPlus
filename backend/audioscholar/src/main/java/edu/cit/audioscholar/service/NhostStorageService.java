package edu.cit.audioscholar.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.cit.audioscholar.exception.StorageRetrievalException;
import edu.cit.audioscholar.exception.StorageUploadException;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class NhostStorageService {
	private static final Logger LOGGER = Logger.getLogger(NhostStorageService.class.getName());

	private final RestTemplate restTemplate;
	private final String nhostStorageUrl;
	private final String nhostAdminSecret;
	private final String nhostBucketId;
	private final ObjectMapper objectMapper;
	@Autowired(required = false)
	private MeterRegistry meterRegistry;

	@Value("${nhost.storage.max-file-size:100MB}")
	private String maxFileSizeValue = "100MB";

	@Value("${nhost.storage.upload-max-attempts:3}")
	private int uploadMaxAttempts = 3;

	@Value("${nhost.storage.upload-retry-delay-ms:2000}")
	private long uploadRetryDelayMs = 2000;

	@Value("${nhost.storage.download-max-attempts:3}")
	private int downloadMaxAttempts = 3;

	@Value("${nhost.storage.download-retry-delay-ms:1000}")
	private long downloadRetryDelayMs = 1000;

	@Value("${multi-source.worker-identity:multi-source-worker}")
	private String workerIdentity = "multi-source-worker";

	public NhostStorageService(RestTemplate restTemplate, @Value("${nhost.storage.url}") String nhostStorageUrl,
			@Value("${nhost.storage.admin-secret}") String nhostAdminSecret,
			@Value("${nhost.storage.bucket-id:default}") String nhostBucketId, ObjectMapper objectMapper) {
		this.restTemplate = restTemplate;
		this.nhostStorageUrl = nhostStorageUrl.endsWith("/v1/files") ? nhostStorageUrl : nhostStorageUrl + "/v1/files";
		this.nhostAdminSecret = nhostAdminSecret;
		this.nhostBucketId = StringUtils.hasText(nhostBucketId) ? nhostBucketId : "default";
		this.objectMapper = objectMapper;

		if (this.nhostAdminSecret == null || this.nhostAdminSecret.isEmpty()
				|| "${NHOST_ADMIN_SECRET}".equals(this.nhostAdminSecret)) {
			LOGGER.log(Level.WARNING,
					"Nhost Admin Secret is not set properly. Check NHOST_ADMIN_SECRET environment variable (Needed for uploads).");
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class NhostFileUploadResponse {
		public String id;
		public List<NhostProcessedFile> processedFiles;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class NhostProcessedFile {
		public String id;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class NhostErrorResponse {
		public NhostError error;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private static class NhostError {
		public JsonNode data;
		public String message;
	}

	public String uploadFile(File file, String originalFilename, String contentType) throws IOException {
		if (file == null || !file.exists() || !file.canRead()) {
			throw new IOException("File is null, does not exist, or cannot be read: "
					+ (file != null ? file.getAbsolutePath() : "null"));
		}
		long maxFileSize = DataSize.parse(maxFileSizeValue).toBytes();
		if (file.length() > maxFileSize) {
			throw new StorageUploadException(
					"File exceeds the configured Nhost upload limit of " + maxFileSizeValue + ".", 413, false, null);
		}
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		headers.set("x-hasura-admin-secret", nhostAdminSecret);

		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		FileSystemResource resource = new FileSystemResource(file);
		HttpHeaders fileHeaders = new HttpHeaders();
		fileHeaders.setContentDisposition(ContentDisposition.formData().name("file[]")
				.filename(StringUtils.hasText(originalFilename) ? originalFilename : file.getName()).build());
		if (StringUtils.hasText(contentType)) {
			fileHeaders.setContentType(MediaType.parseMediaType(contentType));
		}
		body.add("file[]", new HttpEntity<>(resource, fileHeaders));
		body.add("bucket-id", nhostBucketId);

		HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

		String filenameToLog = StringUtils.hasText(originalFilename) ? originalFilename : file.getName();
		LOGGER.log(Level.INFO, "Uploading file {0} ({1} bytes) from path {2} to Nhost URL: {3}",
				new Object[]{filenameToLog, file.length(), file.getAbsolutePath(), nhostStorageUrl});

		StorageUploadException lastFailure = null;
		for (int attempt = 1; attempt <= uploadMaxAttempts; attempt++) {
			try {
				ResponseEntity<String> rawResponse = restTemplate.exchange(nhostStorageUrl, HttpMethod.POST,
						requestEntity, String.class);
				return handleNhostResponse(rawResponse);
			} catch (HttpClientErrorException | HttpServerErrorException e) {
				lastFailure = toStorageUploadException(e);
			} catch (ResourceAccessException e) {
				lastFailure = new StorageUploadException("Nhost upload timed out or could not connect.", 0, true, e);
			} catch (StorageUploadException e) {
				lastFailure = e;
			} catch (Exception e) {
				throw new StorageUploadException("Unexpected error while processing the Nhost upload response.", 0,
						false, e);
			}

			if (!lastFailure.isRetryable() || attempt == uploadMaxAttempts) {
				throw lastFailure;
			}

			LOGGER.log(Level.WARNING, "Nhost upload attempt {0}/{1} failed. Retrying in {2}ms. Reason: {3}",
					new Object[]{attempt, uploadMaxAttempts, uploadRetryDelayMs, lastFailure.getMessage()});
			sleepBeforeRetry(attempt);
		}

		throw lastFailure != null
				? lastFailure
				: new StorageUploadException("Nhost upload failed without a response.", 0, false, null);
	}

	@Deprecated
	public String uploadFile(MultipartFile file) throws IOException {
		LOGGER.log(Level.WARNING, "Deprecated uploadFile(MultipartFile) called. Use uploadFile(File) instead.");
		throw new UnsupportedOperationException("Use uploadFile(File, String, String) instead.");
	}

	private String handleNhostResponse(ResponseEntity<String> rawResponse) throws IOException {
		if (rawResponse.getStatusCode() == HttpStatus.CREATED && rawResponse.getBody() != null) {
			String responseBody = rawResponse.getBody();
			LOGGER.log(Level.INFO, "Received successful Nhost upload response (status 201).");
			try {
				NhostFileUploadResponse parsedResponse = objectMapper.readValue(responseBody,
						NhostFileUploadResponse.class);
				String fileId = extractUploadedFileId(parsedResponse);
				if (StringUtils.hasText(fileId)) {
					LOGGER.log(Level.INFO, "Successfully parsed Nhost response. File ID: {0}", fileId);
					return fileId;
				} else {
					LOGGER.log(Level.SEVERE,
							"Parsed Nhost 201 response, but the 'id' field is missing, null, or empty. Parsed object: {0}",
							parsedResponse);
					throw new RuntimeException("Failed to extract file ID from Nhost response structure.");
				}
			} catch (IOException e) {
				LOGGER.log(Level.SEVERE,
						"Failed to parse Nhost 201 response body into expected structure. Body was: " + responseBody,
						e);
				throw new RuntimeException("Failed to parse Nhost success response.", e);
			}
		} else {
			LOGGER.log(Level.SEVERE, "Failed to upload file to Nhost. Status: {0}, Body: {1}",
					new Object[]{rawResponse.getStatusCode(), rawResponse.getBody()});
			throw new RuntimeException("Failed to upload file to Nhost. Status: " + rawResponse.getStatusCode());
		}
	}

	private String extractUploadedFileId(NhostFileUploadResponse parsedResponse) {
		if (parsedResponse == null) {
			return null;
		}
		if (StringUtils.hasText(parsedResponse.id)) {
			return parsedResponse.id;
		}
		if (parsedResponse.processedFiles != null && !parsedResponse.processedFiles.isEmpty()) {
			NhostProcessedFile processedFile = parsedResponse.processedFiles.get(0);
			if (processedFile != null && StringUtils.hasText(processedFile.id)) {
				return processedFile.id;
			}
		}
		return null;
	}

	private StorageUploadException toStorageUploadException(HttpStatusCodeException e) {
		String errorBody = e.getResponseBodyAsString();
		String providerMessage = "Nhost rejected the upload.";
		try {
			NhostErrorResponse errorResponse = objectMapper.readValue(errorBody, NhostErrorResponse.class);
			if (errorResponse.error != null && StringUtils.hasText(errorResponse.error.message)) {
				providerMessage = errorResponse.error.message;
			}
		} catch (Exception parseException) {
			LOGGER.log(Level.WARNING, "Could not parse Nhost error response body.", parseException);
		}
		int statusCode = e.getStatusCode().value();
		boolean retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500;
		String errorMessage = "Nhost upload failed with status " + statusCode + ": " + providerMessage;
		LOGGER.log(Level.SEVERE, errorMessage, e);
		return new StorageUploadException(errorMessage, statusCode, retryable, e);
	}

	private void sleepBeforeRetry(int attempt) {
		long delayMs = Math.min(uploadRetryDelayMs * (1L << Math.min(attempt - 1, 5)), 30000L);
		try {
			Thread.sleep(delayMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StorageUploadException("Nhost upload retry was interrupted.", 0, false, e);
		}
	}

	public void downloadFileToPath(String fileId, Path targetPath) throws IOException {
		if (!isValidObjectId(fileId))
			throw new StorageRetrievalException("Storage object ID is missing or invalid.",
					"STORAGE_INVALID_IDENTIFIER", 0, false, null);
		if (targetPath == null)
			throw new IllegalArgumentException("Target path cannot be null.");
		String downloadUrl = getPublicUrl(fileId);
		String host = URI.create(downloadUrl).getHost();
		StorageRetrievalException lastFailure = null;
		int unauthorizedRequests = 0;
		for (int attempt = 1; attempt <= Math.max(1, downloadMaxAttempts); attempt++) {
			try {
				LOGGER.log(Level.INFO,
						"Storage retrieval objectId={0}, host={1}, workerIdentity={2}, attempt={3}, credentialMode=DIRECT_SERVICE_IDENTITY, urlExpiration=not-applicable",
						new Object[]{fileId, host, workerIdentity, attempt});
				restTemplate.execute(URI.create(downloadUrl), HttpMethod.GET,
						request -> request.getHeaders().set("x-hasura-admin-secret", nhostAdminSecret), response -> {
							if (!response.getStatusCode().is2xxSuccessful())
								throw retrievalFailure(response.getStatusCode(), fileId, null);
							try (InputStream input = response.getBody()) {
								if (input == null)
									throw new IOException("Storage returned an empty body.");
								return Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING);
							}
						});
				incrementRetrievalMetric("success", 200);
				return;
			} catch (HttpStatusCodeException e) {
				lastFailure = retrievalFailure(e.getStatusCode(), fileId, e);
				incrementRetrievalMetric("http_error", e.getStatusCode().value());
			} catch (ResourceAccessException e) {
				lastFailure = new StorageRetrievalException("Storage request timed out.", "STORAGE_TIMEOUT", 0, true,
						e);
			} catch (StorageRetrievalException e) {
				lastFailure = e;
			} catch (Exception e) {
				lastFailure = new StorageRetrievalException("Unexpected storage retrieval failure.",
						"STORAGE_RETRIEVAL_FAILED", 0, false, e);
			}
			LOGGER.log(Level.WARNING,
					"Storage retrieval failed objectId={0}, host={1}, status={2}, attempt={3}, failureCode={4}",
					new Object[]{fileId, host, lastFailure.getStatusCode(), attempt, lastFailure.getFailureCode()});
			if (lastFailure.getStatusCode() == 401 || lastFailure.getStatusCode() == 403) {
				unauthorizedRequests++;
				if (unauthorizedRequests >= 2)
					throw new StorageRetrievalException("Storage denied the worker service identity.",
							"STORAGE_ACCESS_DENIED", lastFailure.getStatusCode(), false, lastFailure);
				LOGGER.log(Level.WARNING,
						"Storage authorization rejected objectId={0}, host={1}, status={2}; refreshing request once",
						new Object[]{fileId, host, lastFailure.getStatusCode()});
				continue;
			}
			if (!lastFailure.isRetryable() || attempt == downloadMaxAttempts)
				throw lastFailure;
			sleepBeforeDownloadRetry(attempt);
		}
		throw lastFailure;
	}

	private boolean isValidObjectId(String fileId) {
		return StringUtils.hasText(fileId) && fileId.length() <= 256 && fileId.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
	}

	private void incrementRetrievalMetric(String outcome, int status) {
		if (meterRegistry != null)
			meterRegistry
					.counter("audioscholar.storage.retrieval", "outcome", outcome, "status", Integer.toString(status))
					.increment();
	}

	private StorageRetrievalException retrievalFailure(HttpStatusCode status, String fileId, Throwable cause) {
		int code = status.value();
		String failureCode = code == 404
				? "STORAGE_OBJECT_NOT_FOUND"
				: code == 401 || code == 403 ? "STORAGE_ACCESS_DENIED" : "STORAGE_RETRIEVAL_FAILED";
		boolean retryable = code == 408 || code == 429 || code >= 500;
		return new StorageRetrievalException(
				"Storage retrieval failed for object " + fileId + " with HTTP status " + code + ".", failureCode, code,
				retryable, cause);
	}

	private void sleepBeforeDownloadRetry(int attempt) {
		long delayMs = Math.min(downloadRetryDelayMs * (1L << Math.min(attempt - 1, 5)), 30000L);
		try {
			Thread.sleep(delayMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StorageRetrievalException("Storage retry interrupted.", "STORAGE_RETRY_INTERRUPTED", 0, false, e);
		}
	}

	public byte[] downloadFileBytes(String fileId) {
		if (!isValidObjectId(fileId)) {
			LOGGER.log(Level.SEVERE, "Cannot download file with missing or invalid fileId.");
			throw new IllegalArgumentException("File ID is missing or invalid.");
		}

		String downloadUrl = getPublicUrl(fileId);
		LOGGER.log(Level.INFO, "Attempting to download file bytes from Nhost host={0}, objectId={1}",
				new Object[]{URI.create(downloadUrl).getHost(), fileId});

		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM));
		headers.set("x-hasura-admin-secret", nhostAdminSecret);

		try {
			RequestEntity<Void> requestEntity = RequestEntity.get(new URI(downloadUrl)).headers(headers).build();
			ResponseEntity<byte[]> response = restTemplate.exchange(requestEntity, byte[].class);
			if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
				LOGGER.log(Level.INFO, "Successfully downloaded {0} bytes from Nhost for file ID: {1}",
						new Object[]{response.getBody().length, fileId});
				return response.getBody();
			}
			handleDownloadErrorResponse(response.getStatusCode(), fileId);
			return new byte[0];
		} catch (URISyntaxException e) {
			LOGGER.log(Level.SEVERE, "Invalid Nhost download URI for object ID: " + fileId, e);
			throw new RuntimeException("Failed to create download URI.", e);
		} catch (HttpClientErrorException | HttpServerErrorException e) {
			handleDownloadErrorResponse(e.getStatusCode(), fileId);
			return new byte[0];
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE,
					"An unexpected error occurred while downloading Nhost file bytes for file ID: " + fileId, e);
			throw new RuntimeException("An unexpected error occurred during Nhost file download.", e);
		}
	}

	@Deprecated
	public String downloadFileAsBase64(String fileId) throws IOException {
		if (!isValidObjectId(fileId)) {
			LOGGER.log(Level.SEVERE, "Cannot download file with missing or invalid fileId.");
			throw new IllegalArgumentException("File ID is missing or invalid.");
		}
		String downloadUrl = getPublicUrl(fileId);
		LOGGER.log(Level.INFO, "[Deprecated Method] Attempting Nhost download host={0}, objectId={1}",
				new Object[]{URI.create(downloadUrl).getHost(), fileId});
		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM));
		headers.set("x-hasura-admin-secret", nhostAdminSecret);
		RequestEntity<Void> requestEntity;
		try {
			requestEntity = RequestEntity.get(new URI(downloadUrl)).headers(headers).build();
		} catch (URISyntaxException e) {
			LOGGER.log(Level.SEVERE, "Invalid Nhost download URI for object ID: " + fileId, e);
			throw new RuntimeException("Failed to create download URI.", e);
		}
		try {
			ResponseEntity<byte[]> response = restTemplate.exchange(requestEntity, byte[].class);
			if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
				byte[] fileBytes = response.getBody();
				LOGGER.log(Level.INFO,
						"[Deprecated Method] Successfully downloaded {0} bytes from Nhost for file ID: {1}",
						new Object[]{fileBytes.length, fileId});
				return Base64.getEncoder().encodeToString(fileBytes);
			} else {
				handleDownloadErrorResponse(response.getStatusCode(), fileId);
				return null;
			}
		} catch (HttpClientErrorException | HttpServerErrorException e) {
			handleDownloadErrorResponse(e.getStatusCode(), fileId);
			return null;
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE,
					"[Deprecated Method] An unexpected error occurred during Nhost file download for file ID: "
							+ fileId,
					e);
			throw new RuntimeException("An unexpected error occurred during Nhost file download.", e);
		}
	}

	private void handleDownloadErrorResponse(HttpStatusCode statusCode, String fileId) {
		String errorMessage;
		if (statusCode == HttpStatus.FORBIDDEN) {
			errorMessage = "Failed to download file from Nhost (Status: 403 FORBIDDEN). Check that NHOST_ADMIN_SECRET is valid for this Nhost project and available to the backend runtime. File ID: "
					+ fileId;
			LOGGER.log(Level.SEVERE, errorMessage);
			throw new RuntimeException(errorMessage);
		} else {
			errorMessage = "Failed to download file from Nhost. Status: " + statusCode + " for file ID: " + fileId;
			LOGGER.log(Level.SEVERE, errorMessage);
			throw new RuntimeException(errorMessage);
		}
	}

	public String getPublicUrl(String fileId) {
		if (fileId == null || fileId.isEmpty()) {
			LOGGER.log(Level.WARNING, "Generating public URL requested for null or empty fileId.");
			throw new IllegalArgumentException("File ID cannot be null or empty when generating public URL.");
		}
		String baseUrl = nhostStorageUrl.replace("/v1/files", "");
		baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		return baseUrl + "/v1/files/" + fileId;
	}

	public boolean isNhostUrl(String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		// Check if the URL starts with the configured Nhost storage URL
		// The nhostStorageUrl variable ensures it ends with /v1/files
		return url.startsWith(this.nhostStorageUrl);
	}

	public void deleteFile(String fileId) {
		if (fileId == null || fileId.isEmpty()) {
			LOGGER.log(Level.WARNING, "Attempted to delete Nhost file with null or empty ID.");
			// Optionally throw an exception or just return
			return;
		}

		String deleteUrl = nhostStorageUrl + "/" + fileId;
		LOGGER.log(Level.INFO, "Attempting to delete Nhost file with ID: {0} using URL: {1}",
				new Object[]{fileId, deleteUrl});

		HttpHeaders headers = new HttpHeaders();
		headers.set("x-hasura-admin-secret", nhostAdminSecret);
		HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

		try {
			ResponseEntity<String> response = restTemplate.exchange(deleteUrl, HttpMethod.DELETE, requestEntity,
					String.class);
			if (response.getStatusCode().is2xxSuccessful()) {
				LOGGER.log(Level.INFO, "Successfully deleted Nhost file ID: {0} (Status: {1})",
						new Object[]{fileId, response.getStatusCode()});
			} else {
				// This case might not be reached if RestTemplate throws exceptions for non-2xx
				LOGGER.log(Level.WARNING, "Nhost file deletion returned non-success status: {0} for file ID: {1}",
						new Object[]{response.getStatusCode(), fileId});
			}
		} catch (HttpClientErrorException | HttpServerErrorException e) {
			LOGGER.log(Level.SEVERE, "Error deleting Nhost file ID: " + fileId + ". Status: " + e.getStatusCode()
					+ ", Response: " + e.getResponseBodyAsString(), e);
			// Handle specific errors (e.g., 404 Not Found might be acceptable)
			if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
				LOGGER.log(Level.WARNING, "Nhost file ID: {0} not found during deletion attempt (already deleted?).",
						fileId);
			} else {
				// Rethrow or handle other errors as needed
				throw new RuntimeException("Failed to delete Nhost file: " + fileId, e);
			}
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "An unexpected error occurred during Nhost file deletion for ID: " + fileId, e);
			throw new RuntimeException("Unexpected error deleting Nhost file: " + fileId, e);
		}
	}
}
