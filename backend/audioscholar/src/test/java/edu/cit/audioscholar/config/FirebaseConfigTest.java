package edu.cit.audioscholar.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class FirebaseConfigTest {

	@TempDir
	Path tempDir;

	@Test
	void loadsCredentialsFromJsonEnvironmentVariable() throws Exception {
		String json = "{\"project_id\":\"test-project\"}";
		FirebaseConfig config = configWith("FIREBASE_SERVICE_ACCOUNT_JSON", json);

		assertEquals(json, read(config.getCredentialsStream()));
	}

	@Test
	void loadsCredentialsFromBase64EnvironmentVariable() throws Exception {
		String json = "{\"project_id\":\"test-project\"}";
		String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		FirebaseConfig config = configWith("FIREBASE_SERVICE_ACCOUNT_BASE64", encoded);

		assertEquals(json, read(config.getCredentialsStream()));
	}

	@Test
	void loadsCredentialsFromConfiguredFile() throws Exception {
		String json = "{\"project_id\":\"test-project\"}";
		Path credentialsFile = tempDir.resolve("firebase.json");
		Files.writeString(credentialsFile, json);
		FirebaseConfig config = configWith("GOOGLE_APPLICATION_CREDENTIALS", credentialsFile.toString());

		assertEquals(json, read(config.getCredentialsStream()));
	}

	@Test
	void rejectsMalformedBase64Credentials() {
		FirebaseConfig config = configWith("FIREBASE_SERVICE_ACCOUNT_BASE64", "not valid base64!");

		assertThrows(IOException.class, config::getCredentialsStream);
	}

	private FirebaseConfig configWith(String name, String value) {
		return new FirebaseConfig(new MockEnvironment().withProperty(name, value));
	}

	private String read(InputStream stream) throws IOException {
		try (InputStream input = stream) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
