package edu.cit.audioscholar.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Service
public class SupabaseStorageService {

	private static final Logger log = LoggerFactory.getLogger(SupabaseStorageService.class);

	private final RestTemplate restTemplate;
	private final String supabaseUrl;
	private final String serviceRoleKey;
	private final String bucket;

	public SupabaseStorageService(RestTemplate restTemplate, @Value("${supabase.url:}") String supabaseUrl,
			@Value("${supabase.service-role-key:}") String serviceRoleKey,
			@Value("${supabase.storage.bucket:audioscholar}") String bucket) {
		this.restTemplate = restTemplate;
		this.supabaseUrl = trimTrailingSlash(supabaseUrl);
		this.serviceRoleKey = serviceRoleKey;
		this.bucket = bucket;
		if (!StringUtils.hasText(this.supabaseUrl) || !StringUtils.hasText(this.serviceRoleKey)) {
			log.warn("Supabase Storage is not fully configured. Set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY.");
		}
	}

	public String uploadFile(File file, String originalFilename, String contentType) throws IOException {
		if (file == null || !file.exists() || !file.canRead()) {
			throw new IOException("File is null, does not exist, or cannot be read: " + (file != null ? file : "null"));
		}
		String extension = StringUtils.getFilenameExtension(originalFilename != null ? originalFilename : file.getName());
		String objectPath = UUID.randomUUID() + (StringUtils.hasText(extension) ? "." + extension : "");
		String uploadUrl = objectUrl(objectPath);

		HttpHeaders headers = authHeaders();
		headers.setContentType(StringUtils.hasText(contentType) ? MediaType.parseMediaType(contentType)
				: MediaType.APPLICATION_OCTET_STREAM);
		headers.set("x-upsert", "true");

		ResponseEntity<String> response = restTemplate.exchange(uploadUrl, HttpMethod.POST,
				new HttpEntity<>(new FileSystemResource(file), headers), String.class);
		if (!response.getStatusCode().is2xxSuccessful()) {
			throw new IOException("Supabase Storage upload failed with status " + response.getStatusCode());
		}
		return objectPath;
	}

	public void downloadFileToPath(String objectPath, Path targetPath) throws IOException {
		if (!StringUtils.hasText(objectPath)) {
			throw new IllegalArgumentException("Object path cannot be blank.");
		}
		try {
			restTemplate.execute(new URI(getPublicUrl(objectPath)), HttpMethod.GET, null, response -> {
				if (response.getStatusCode() == HttpStatus.OK) {
					try (InputStream inputStream = response.getBody()) {
						Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
						return null;
					}
				}
				throw new IOException("Supabase Storage download failed with status " + response.getStatusCode());
			});
		} catch (Exception e) {
			throw new IOException("Supabase Storage download failed", e);
		}
	}

	public String getPublicUrl(String objectPath) {
		return supabaseUrl + "/storage/v1/object/public/" + bucket + "/" + objectPath;
	}

	public void deleteFile(String objectPath) {
		if (!StringUtils.hasText(objectPath)) {
			return;
		}
		String deleteUrl = supabaseUrl + "/storage/v1/object/" + bucket;
		HttpHeaders headers = authHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		restTemplate.exchange(deleteUrl, HttpMethod.DELETE, new HttpEntity<>(Map.of("prefixes", List.of(objectPath)), headers),
				String.class);
	}

	public boolean isSupabaseUrl(String url) {
		return StringUtils.hasText(url) && StringUtils.hasText(supabaseUrl) && url.startsWith(supabaseUrl);
	}

	private HttpHeaders authHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(serviceRoleKey);
		headers.set("apikey", serviceRoleKey);
		return headers;
	}

	private String objectUrl(String objectPath) {
		return supabaseUrl + "/storage/v1/object/" + bucket + "/" + objectPath;
	}

	private String trimTrailingSlash(String value) {
		if (value == null) {
			return "";
		}
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}
}
