package edu.cit.audioscholar.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Compatibility adapter kept so older services compile while the portfolio demo uses Supabase Storage.
 */
@Deprecated
@Service
public class NhostStorageService {

	private final SupabaseStorageService supabaseStorageService;

	public NhostStorageService(SupabaseStorageService supabaseStorageService) {
		this.supabaseStorageService = supabaseStorageService;
	}

	public String uploadFile(File file, String originalFilename, String contentType) throws IOException {
		return supabaseStorageService.uploadFile(file, originalFilename, contentType);
	}

	@Deprecated
	public String uploadFile(MultipartFile file) throws IOException {
		throw new UnsupportedOperationException("Use uploadFile(File, String, String) instead.");
	}

	public void downloadFileToPath(String fileId, Path targetPath) throws IOException {
		supabaseStorageService.downloadFileToPath(fileId, targetPath);
	}

	@Deprecated
	public String downloadFileAsBase64(String fileId) throws IOException {
		throw new UnsupportedOperationException("Base64 download is not used in the Supabase portfolio path.");
	}

	public String getPublicUrl(String fileId) {
		return supabaseStorageService.getPublicUrl(fileId);
	}

	public boolean isNhostUrl(String url) {
		return supabaseStorageService.isSupabaseUrl(url);
	}

	public void deleteFile(String fileId) {
		supabaseStorageService.deleteFile(fileId);
	}
}
