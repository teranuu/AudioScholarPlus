package edu.cit.audioscholar.service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.cloud.Timestamp;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.ListUsersPage;
import com.google.firebase.auth.UserRecord;
import com.google.firebase.auth.UserRecord.UpdateRequest;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;

import edu.cit.audioscholar.exception.FirestoreInteractionException;
import edu.cit.audioscholar.model.AudioMetadata;
import edu.cit.audioscholar.model.LearningRecommendation;
import edu.cit.audioscholar.model.ProcessingStatus;
import edu.cit.audioscholar.persistence.JpaDocumentStore;

@Service
public class FirebaseService {

	private static final Logger log = LoggerFactory.getLogger(FirebaseService.class);
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final String CACHE_METADATA_BY_USER = "audioMetadataByUser";
	private static final String CACHE_METADATA_BY_ID = "audioMetadataById";

	private final String audioMetadataCollectionName;
	private final String recommendationsCollectionName;
	private final FirebaseApp firebaseApp;
	private final UserService userService;
	private final CacheManager cacheManager;
	private final JpaDocumentStore documentStore;

	@Value("${google.oauth.web.client.id:}")
	private String webClientIdFromTokenAud;

	@Value("${google.oauth.android.client.id:}")
	private String androidClientId;

	public FirebaseService(@Value("${firebase.firestore.collection.audiometadata:audio_metadata}") String audioMetadataCollectionName,
			@Value("${firebase.firestore.collection.recommendations:learning_recommendations}") String recommendationsCollectionName,
			FirebaseApp firebaseApp, @Lazy UserService userService, CacheManager cacheManager,
			JpaDocumentStore documentStore) {
		this.audioMetadataCollectionName = audioMetadataCollectionName;
		this.recommendationsCollectionName = recommendationsCollectionName;
		this.firebaseApp = firebaseApp;
		this.userService = userService;
		this.cacheManager = cacheManager;
		this.documentStore = documentStore;
		log.info("Firebase Auth/FCM retained; Firestore persistence replaced by JPA document store.");
	}

	FirebaseAuth getFirebaseAuth() {
		return FirebaseAuth.getInstance(firebaseApp);
	}

	FirebaseMessaging getFirebaseMessaging() {
		return FirebaseMessaging.getInstance(firebaseApp);
	}

	public String getAudioMetadataCollectionName() {
		return audioMetadataCollectionName;
	}

	public FirebaseApp getFirebaseApp() {
		return firebaseApp;
	}

	public FirebaseToken verifyFirebaseIdToken(String idToken) throws FirebaseAuthException {
		if (!StringUtils.hasText(idToken)) {
			throw new IllegalArgumentException("ID token cannot be null or blank.");
		}
		return getFirebaseAuth().verifyIdToken(idToken, true);
	}

	public GoogleIdToken verifyGoogleIdToken(String googleIdTokenString)
			throws GeneralSecurityException, IOException, IllegalArgumentException {
		if (!StringUtils.hasText(googleIdTokenString)) {
			throw new IllegalArgumentException("Google ID token cannot be null or blank.");
		}
		List<String> audiences = new ArrayList<>();
		if (StringUtils.hasText(webClientIdFromTokenAud))
			audiences.add(webClientIdFromTokenAud);
		if (StringUtils.hasText(androidClientId))
			audiences.add(androidClientId);
		if (audiences.isEmpty()) {
			throw new IllegalStateException("Missing Google OAuth Client ID configuration for token verification.");
		}
		GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(),
				GsonFactory.getDefaultInstance()).setAudience(audiences).build();
		return verifier.verify(googleIdTokenString);
	}

	public UserRecord createFirebaseUser(String email, String password, String displayName) throws FirebaseAuthException {
		UserRecord.CreateRequest request = new UserRecord.CreateRequest().setEmail(email).setPassword(password)
				.setDisplayName(displayName).setEmailVerified(false).setDisabled(false);
		return getFirebaseAuth().createUser(request);
	}

	public void updateUserPassword(String uid, String newPassword) throws FirebaseAuthException {
		getFirebaseAuth().updateUser(new UpdateRequest(uid).setPassword(newPassword));
	}

	public boolean isEmailVerified(String uid) throws FirebaseAuthException {
		return getFirebaseAuth().getUser(uid).isEmailVerified();
	}

	public String saveData(String collection, String document, Object dataPojo) {
		try {
			documentStore.save(collection, document, dataPojo);
			return Timestamp.now().toString();
		} catch (Exception e) {
			throw new FirestoreInteractionException("Error saving data to PostgreSQL", e);
		}
	}

	public Map<String, Object> getData(String collection, String document) {
		try {
			return documentStore.get(collection, document);
		} catch (Exception e) {
			throw new FirestoreInteractionException("Failed to get document " + collection + "/" + document, e);
		}
	}

	public String updateData(String collection, String document, Object dataPojo) {
		try {
			documentStore.merge(collection, document, dataPojo);
			return Timestamp.now().toString();
		} catch (Exception e) {
			throw new FirestoreInteractionException("Error updating data in PostgreSQL", e);
		}
	}

	public String updateDataWithMap(String collection, String document, Map<String, Object> data) {
		if (data == null || data.isEmpty()) {
			return "No update performed (empty map)";
		}
		return updateData(collection, document, data);
	}

	public String deleteData(String collection, String document) {
		try {
			documentStore.delete(collection, document);
			return Timestamp.now().toString();
		} catch (Exception e) {
			throw new FirestoreInteractionException("Error deleting data from PostgreSQL", e);
		}
	}

	public List<Map<String, Object>> queryCollection(String collection, String field, Object value) {
		try {
			return documentStore.query(collection, field, value);
		} catch (Exception e) {
			throw new FirestoreInteractionException("Error querying collection in PostgreSQL", e);
		}
	}

	public List<Map<String, Object>> getAllData(String collection) {
		try {
			return documentStore.all(collection);
		} catch (Exception e) {
			throw new FirestoreInteractionException("Error retrieving all documents from " + collection, e);
		}
	}

	public long getCollectionCount(String collectionName) {
		return documentStore.count(collectionName);
	}

	public List<Map<String, Object>> getDocumentsSince(String collectionName, String dateField, Date date) {
		return getAllData(collectionName).stream().filter(item -> isOnOrAfter(item.get(dateField), date))
				.collect(Collectors.toList());
	}

	public List<Map<String, Object>> getProjectedData(String collectionName, String... fields) {
		return getAllData(collectionName).stream().map(item -> {
			Map<String, Object> projected = new HashMap<>();
			for (String field : fields) {
				if (item.containsKey(field))
					projected.put(field, item.get(field));
			}
			return projected;
		}).collect(Collectors.toList());
	}

	public List<Map<String, Object>> getTopRecordings(int limit) {
		return documentStore.topRecordings(limit);
	}

	public void updateAudioMetadataStatus(String metadataId, ProcessingStatus status) {
		Map<String, Object> updates = new HashMap<>();
		updates.put("status", status.name());
		updates.put("lastUpdated", Timestamp.now());
		updateDataWithMap(audioMetadataCollectionName, metadataId, updates);
	}

	public List<AudioMetadata> getAllAudioMetadata() {
		return getAllData(audioMetadataCollectionName).stream().map(AudioMetadata::fromMap).filter(Objects::nonNull)
				.collect(Collectors.toList());
	}

	@Cacheable(value = CACHE_METADATA_BY_USER, key = "#userId", condition = "#userId != null")
	public List<AudioMetadata> getAudioMetadataByUserId(String userId, int pageSize, String lastDocumentId) {
		if (!StringUtils.hasText(userId)) {
			return Collections.emptyList();
		}
		List<AudioMetadata> all = queryCollection(audioMetadataCollectionName, "userId", userId).stream()
				.map(AudioMetadata::fromMap).filter(Objects::nonNull)
				.sorted((a, b) -> compareTimestampDesc(a.getUploadTimestamp(), b.getUploadTimestamp()))
				.collect(Collectors.toList());
		int start = 0;
		if (StringUtils.hasText(lastDocumentId)) {
			for (int i = 0; i < all.size(); i++) {
				if (lastDocumentId.equals(all.get(i).getId())) {
					start = i + 1;
					break;
				}
			}
		}
		int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
		return all.subList(Math.min(start, all.size()), Math.min(start + size, all.size()));
	}

	@Cacheable(value = CACHE_METADATA_BY_ID, key = "#metadataId", unless = "#result == null")
	public AudioMetadata getAudioMetadataById(String metadataId) {
		Map<String, Object> data = getData(audioMetadataCollectionName, metadataId);
		return data == null ? null : AudioMetadata.fromMap(data);
	}

	public AudioMetadata getAudioMetadataByRecordingId(String recordingId) {
		return queryCollection(audioMetadataCollectionName, "recordingId", recordingId).stream().findFirst()
				.map(AudioMetadata::fromMap).orElse(null);
	}

	public void saveLearningRecommendations(List<LearningRecommendation> recommendations) {
		if (recommendations == null || recommendations.isEmpty()) {
			return;
		}
		for (LearningRecommendation recommendation : recommendations) {
			if (!StringUtils.hasText(recommendation.getRecommendationId())) {
				recommendation.setRecommendationId(UUID.randomUUID().toString());
			}
			saveData(recommendationsCollectionName, recommendation.getRecommendationId(), recommendation);
		}
	}

	public List<LearningRecommendation> getLearningRecommendationsByRecordingId(String recordingId) {
		return queryCollection(recommendationsCollectionName, "recordingId", recordingId).stream()
				.map(LearningRecommendation::fromMap).filter(Objects::nonNull).collect(Collectors.toList());
	}

	public MulticastMessage buildProcessingCompleteMessage(String userId, String recordingId, String summaryId) {
		List<String> tokens;
		try {
			tokens = userService.getFcmTokensForUser(userId);
		} catch (FirestoreInteractionException e) {
			log.error("Failed to retrieve FCM tokens for user {}", userId, e);
			return null;
		}
		if (tokens == null || tokens.isEmpty()) {
			return null;
		}
		Map<String, String> dataPayload = Map.of("type", "processingComplete", "recordingId", recordingId, "summaryId",
				summaryId);
		AndroidConfig androidConfig = AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build();
		ApnsConfig apnsConfig = ApnsConfig.builder().putAllHeaders(Map.of("apns-priority", "5", "apns-push-type", "background"))
				.setAps(Aps.builder().setContentAvailable(true).build()).build();
		return MulticastMessage.builder().putAllData(dataPayload).setAndroidConfig(androidConfig).setApnsConfig(apnsConfig)
				.addAllTokens(tokens).build();
	}

	public void sendFcmMessage(MulticastMessage message, List<String> tokens, String userId) {
		if (message == null || tokens == null || tokens.isEmpty()) {
			return;
		}
		List<String> unregisteredTokens = new ArrayList<>();
		try {
			BatchResponse response = getFirebaseMessaging().sendEachForMulticast(message);
			List<SendResponse> responses = response.getResponses();
			for (int i = 0; i < responses.size(); i++) {
				if (!responses.get(i).isSuccessful() && responses.get(i).getException() != null
						&& responses.get(i).getException().getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED
						&& i < tokens.size()) {
					unregisteredTokens.add(tokens.get(i));
				}
			}
		} catch (FirebaseMessagingException e) {
			log.error("Failed to send FCM multicast message for user {}: {}", userId, e.getMessage(), e);
		}
		if (!unregisteredTokens.isEmpty()) {
			userService.removeFcmTokens(userId, unregisteredTokens);
		}
	}

	public ListUsersPage listUsers(int limit, String pageToken) throws FirebaseAuthException {
		int maxResults = limit <= 0 ? DEFAULT_PAGE_SIZE : limit;
		return getFirebaseAuth().listUsers(StringUtils.hasText(pageToken) ? pageToken : null, maxResults);
	}

	public void setUserDisabled(String uid, boolean disabled) throws FirebaseAuthException {
		getFirebaseAuth().updateUser(new UpdateRequest(uid).setDisabled(disabled));
	}

	public void setCustomUserClaims(String uid, Map<String, Object> claims) throws FirebaseAuthException {
		getFirebaseAuth().setCustomUserClaims(uid, claims);
	}

	@CacheEvict(value = CACHE_METADATA_BY_ID, key = "#uid", condition = "#uid != null")
	public String createCustomToken(String uid) throws FirebaseAuthException {
		if (!StringUtils.hasText(uid)) {
			throw new IllegalArgumentException("UID cannot be blank.");
		}
		getFirebaseAuth().getUser(uid);
		return getFirebaseAuth().createCustomToken(uid);
	}

	public List<Map<String, Object>> getUsersFromFirestore(int limit, String lastUserId) {
		List<Map<String, Object>> users = getAllData("users").stream()
				.sorted((a, b) -> String.valueOf(a.get("userId")).compareTo(String.valueOf(b.get("userId"))))
				.collect(Collectors.toList());
		int start = 0;
		if (StringUtils.hasText(lastUserId)) {
			for (int i = 0; i < users.size(); i++) {
				if (lastUserId.equals(String.valueOf(users.get(i).get("userId")))) {
					start = i + 1;
					break;
				}
			}
		}
		int pageSize = limit <= 0 ? DEFAULT_PAGE_SIZE : limit;
		return users.subList(Math.min(start, users.size()), Math.min(start + pageSize, users.size()));
	}

	public void updateAudioMetadataStatusAndReason(String metadataId, @Nullable String userId, ProcessingStatus status,
			String reason) throws ExecutionException, InterruptedException {
		Map<String, Object> updates = new HashMap<>();
		updates.put("status", status.name());
		updates.put("lastUpdated", Timestamp.now());
		if (status == ProcessingStatus.FAILED || status == ProcessingStatus.PROCESSING_HALTED_UNSUITABLE_CONTENT) {
			updates.put("failureReason", reason != null ? reason : "No specific reason provided.");
		} else {
			updates.put("failureReason", null);
		}
		updateDataWithMap(audioMetadataCollectionName, metadataId, updates);
		if (userId != null) {
			Cache cache = cacheManager.getCache(CACHE_METADATA_BY_USER);
			if (cache != null)
				cache.evict(userId);
		}
	}

	private int compareTimestampDesc(Timestamp left, Timestamp right) {
		if (left == null && right == null)
			return 0;
		if (left == null)
			return 1;
		if (right == null)
			return -1;
		return Long.compare(right.toSqlTimestamp().getTime(), left.toSqlTimestamp().getTime());
	}

	private boolean isOnOrAfter(Object value, Date date) {
		if (value instanceof Date found) {
			return !found.before(date);
		}
		if (value instanceof Timestamp timestamp) {
			return !timestamp.toDate().before(date);
		}
		return false;
	}
}
