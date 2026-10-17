package edu.cit.audioscholar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import edu.cit.audioscholar.model.SourceFile;
import edu.cit.audioscholar.model.SourceKind;

class SourceFileServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void persistsStorageObjectIdAndUsesItForWorkerRetrieval() throws Exception {
		StorageService storage = mock(StorageService.class);
		SourceFileRepository repository = mock(SourceFileRepository.class);
		SourceFileService service = new SourceFileService(storage, repository);
		Path upload = tempDir.resolve("source.mp3");
		Files.writeString(upload, "audio");
		MockMultipartFile file = new MockMultipartFile("mediaFiles", "source.mp3", "audio/mpeg", "audio".getBytes());
		when(storage.storeSourceFile(upload, "source.mp3", "audio/mpeg")).thenReturn(
				new StorageService.StoredObject("object-123", "https://storage.example/v1/files/object-123"));

		SourceFile source = service.createSourceFile("job-1", "Source A", SourceKind.MEDIA, file, upload);
		Path download = tempDir.resolve("download.mp3");
		service.retrieveToPath(source.getStorageObjectId(), download);

		assertEquals("object-123", source.getStorageObjectId());
		verify(storage).retrieveSourceFile("object-123", download);
	}
}
