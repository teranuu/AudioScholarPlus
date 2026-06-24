package edu.cit.audioscholar.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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

	private final Environment environment;

	public FirebaseConfig(Environment environment) {
		this.environment = environment;
	}

	@Value("${firebase.database.url:}")
	private String databaseUrl;

	@Value("${firebase.project-id:}")
	private String projectId;

	@Value("${firebase.service-account.file:firebase-service-account.json}")
	private String serviceAccountFile;

	@Value("${firebase.test-mode:false}")
	private boolean firebaseTestMode;

	/**
	 * Initializes FirebaseApp.
	 *
	 * <p>In non-test profiles the bean requires credentials via
	 * {@code GOOGLE_APPLICATION_CREDENTIALS} environment variable (file path) or a
	 * classpath resource.  The classpath fallback is intended <b>only</b> for
	 * local development because the Maven resource filter excludes
	 * {@code firebase-service-account.json} from the packaged jar (see pom.xml).
	 *
	 * <p>Production / staging deployments MUST supply
	 * {@code GOOGLE_APPLICATION_CREDENTIALS} pointing to the service-account JSON
	 * file mounted at a known path outside the classpath.
	 */
	@Bean
	@Profile("!test")
	FirebaseApp firebaseApp() throws IOException {
		if (!FirebaseApp.getApps().isEmpty()) {
			logger.info("FirebaseApp already initialized. Returning existing instance.");
			return FirebaseApp.getInstance();
		}

		FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder();

		InputStream serviceAccountStream = getCredentialsStream();

		if (serviceAccountStream == null) {
			throw new IOException("Could not find Firebase service account credentials via " + GAC_ENV_VAR
					+ " environment variable or configured classpath resource: " + serviceAccountFile);
		}

		try (InputStream stream = serviceAccountStream) {
			optionsBuilder.setCredentials(GoogleCredentials.fromStream(stream));
			logger.info("Successfully configured Firebase credentials.");
		} catch (IOException e) {
			logger.error("Error processing Firebase credentials stream.", e);
			throw e;
		}

		if (StringUtils.hasText(databaseUrl)) {
			optionsBuilder.setDatabaseUrl(databaseUrl);
		}
		if (StringUtils.hasText(projectId)) {
			optionsBuilder.setProjectId(projectId);
		}

		FirebaseOptions options = optionsBuilder.build();
		logger.info("Successfully configured FirebaseApp.");
		return FirebaseApp.initializeApp(options);
	}

	/**
	 * Test-mode FirebaseApp bean — creates an unauthenticated stub for
	 * integration tests. Only active when {@code firebase.test-mode=true}
	 * AND the Spring {@code test} profile is active.
	 */
	@Bean
	@Profile("test")
	FirebaseApp firebaseAppTest() throws IOException {
		if (!FirebaseApp.getApps().isEmpty()) {
			logger.info("FirebaseApp already initialized. Returning existing instance.");
			return FirebaseApp.getInstance();
		}

		if (!firebaseTestMode) {
			throw new IllegalStateException(
					"Firebase test-mode must be enabled (firebase.test-mode=true) when the 'test' Spring profile is active.");
		}

		logger.warn(
				"Firebase test mode enabled for test profile; initializing FirebaseApp without external credentials.");

		FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder()
				.setCredentials(GoogleCredentials.newBuilder().build());

		if (StringUtils.hasText(databaseUrl)) {
			optionsBuilder.setDatabaseUrl(databaseUrl);
		}
		if (StringUtils.hasText(projectId)) {
			optionsBuilder.setProjectId(projectId);
		}

		FirebaseOptions options = optionsBuilder.build();
		logger.info("Successfully configured FirebaseApp (test mode).");
		return FirebaseApp.initializeApp(options);
	}

	/**
	 * Resolves credentials in priority order:
	 * <ol>
	 *   <li>{@code GOOGLE_APPLICATION_CREDENTIALS} env var (filesystem path) —
	 *       required for production/staging.</li>
	 *   <li>Classpath resource (local dev only — excluded from the packaged jar
	 *       by the Maven resource filter).</li>
	 * </ol>
	 */
	private InputStream getCredentialsStream() throws IOException {
		String credentialsPath = System.getenv(GAC_ENV_VAR);

		if (credentialsPath != null && !credentialsPath.isEmpty()) {
			String source = "environment variable " + GAC_ENV_VAR + " (" + credentialsPath + ")";
			try {
				logger.info("Attempting to load Firebase credentials from {}", source);
				return new FileInputStream(credentialsPath);
			} catch (IOException e) {
				logger.warn("Failed to load credentials from {}: {}", source, e.getMessage());
			}
		}

		// Classpath fallback — only works in local dev (excluded from jar by pom.xml)
		String classpathResource = StringUtils.hasText(serviceAccountFile)
				? serviceAccountFile
				: "firebase-service-account.json";
		if (classpathResource.startsWith("classpath:")) {
			classpathResource = classpathResource.substring("classpath:".length());
		}
		String source = "classpath:" + classpathResource;
		try {
			logger.info("Attempting to load Firebase credentials from {} (local-dev classpath fallback)", source);
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

		logger.error("Could not locate Firebase credentials via environment variable or classpath.");
		return null;
	}
}
