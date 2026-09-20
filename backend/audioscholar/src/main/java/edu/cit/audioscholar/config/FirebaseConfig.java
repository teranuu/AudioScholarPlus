package edu.cit.audioscholar.config;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StringUtils;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

@Configuration
public class FirebaseConfig {

	private static final Logger logger = LoggerFactory.getLogger(FirebaseConfig.class);

	private static final String GAC_ENV_VAR = "GOOGLE_APPLICATION_CREDENTIALS";
	private static final String FIREBASE_JSON_ENV_VAR = "FIREBASE_SERVICE_ACCOUNT_JSON";
	private static final String FIREBASE_BASE64_ENV_VAR = "FIREBASE_SERVICE_ACCOUNT_BASE64";

	private final Environment environment;

	public FirebaseConfig(Environment environment) {
		this.environment = environment;
	}

	@Bean
	FirebaseApp firebaseApp() throws IOException {
		if (FirebaseApp.getApps().isEmpty()) {
			InputStream serviceAccountStream = getCredentialsStream();

			if (serviceAccountStream == null) {
				throw new IOException("Could not find Firebase service account credentials via " + FIREBASE_JSON_ENV_VAR
						+ ", " + FIREBASE_BASE64_ENV_VAR + ", " + GAC_ENV_VAR
						+ ", or classpath:firebase-service-account.json");
			}

			FirebaseOptions options;
			try (InputStream stream = serviceAccountStream) {
				options = FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(stream))
						.setDatabaseUrl("https://audioscholar-39b22-default-rtdb.firebaseio.com").build();
				logger.info("Successfully configured FirebaseApp.");
			} catch (IOException e) {
				logger.error("Error processing Firebase credentials stream.", e);
				throw e;
			}

			return FirebaseApp.initializeApp(options);
		} else {
			logger.info("FirebaseApp already initialized. Returning existing instance.");
			return FirebaseApp.getInstance();
		}
	}

	InputStream getCredentialsStream() throws IOException {
		String credentialsJson = environment.getProperty(FIREBASE_JSON_ENV_VAR);
		if (StringUtils.hasText(credentialsJson)) {
			logger.info("Loading Firebase credentials from {}.", FIREBASE_JSON_ENV_VAR);
			return new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8));
		}

		String credentialsBase64 = environment.getProperty(FIREBASE_BASE64_ENV_VAR);
		if (StringUtils.hasText(credentialsBase64)) {
			logger.info("Loading Firebase credentials from {}.", FIREBASE_BASE64_ENV_VAR);
			try {
				byte[] decoded = Base64.getDecoder().decode(credentialsBase64.trim());
				return new ByteArrayInputStream(decoded);
			} catch (IllegalArgumentException e) {
				throw new IOException(FIREBASE_BASE64_ENV_VAR + " is not valid Base64", e);
			}
		}

		String credentialsPath = environment.getProperty(GAC_ENV_VAR);
		String source;

		if (StringUtils.hasText(credentialsPath)) {
			source = "environment variable " + GAC_ENV_VAR + " (" + credentialsPath + ")";
			try {
				logger.info("Attempting to load Firebase credentials from {}", source);
				return new FileInputStream(credentialsPath);
			} catch (IOException e) {
				logger.warn("Failed to load credentials from {}: {}", source, e.getMessage());
			}
		}

		String classpathResource = "firebase-service-account.json";
		source = "classpath:" + classpathResource;
		try {
			logger.info("Attempting to load Firebase credentials from {}", source);
			InputStream stream = new ClassPathResource(classpathResource).getInputStream();
			if (stream != null) {
				logger.info("Successfully found credentials in {}", source);
				return stream;
			} else {
				logger.warn("Credentials not found in {}", source);
			}
		} catch (IOException e) {
			logger.warn("Failed to load credentials from {}: {}", source, e.getMessage());
		}

		logger.error("Could not locate Firebase credentials via {}, {}, {}, or classpath.", FIREBASE_JSON_ENV_VAR,
				FIREBASE_BASE64_ENV_VAR, GAC_ENV_VAR);
		return null;
	}
}
