package edu.cit.audioscholar.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import edu.cit.audioscholar.exception.KeysExhaustedException;
import edu.cit.audioscholar.model.KeyProvider;

@Service
public class KeyRotationManagerImpl implements KeyRotationManager {

	private static final Logger log = LoggerFactory.getLogger(KeyRotationManagerImpl.class);
	// Storage for keys per provider
	private final Map<KeyProvider, List<String>> keyStore = new ConcurrentHashMap<>();

	// Round-robin counters per provider
	private final Map<KeyProvider, AtomicInteger> counters = new ConcurrentHashMap<>();

	// Cooldown tracking: Key -> Timestamp when it becomes available again
	private final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();

	@Value("${gemini.api.keys:}")
	private String geminiKeysRaw;

	@Value("${google.ai.api.key:}")
	private String geminiKeyLegacy;

	@Value("${convertapi.secrets:}")
	private String convertApiSecretsRaw;

	@Value("${convertapi.secret:${CONVERTAPI_SECRET:}}")
	private String convertApiSecretLegacy;

	@Value("${gemini.keys.cooldown:60s}")
	private Duration geminiCooldown = Duration.ofMinutes(1);

	@PostConstruct
	public void init() {
		loadKeys(KeyProvider.GEMINI, geminiKeysRaw, geminiKeyLegacy);
		loadKeys(KeyProvider.CONVERTAPI, convertApiSecretsRaw, convertApiSecretLegacy);
	}

	private void loadKeys(KeyProvider provider, String listRaw, String singleLegacy) {
		List<String> keys = new ArrayList<>();

		// 1. Try loading from comma-separated list
		if (listRaw != null && !listRaw.isBlank()) {
			keys.addAll(Arrays.stream(listRaw.split(",")).map(String::trim).filter(s -> !s.isEmpty())
					.collect(Collectors.toList()));
		}

		// 2. Fallback or Merge: If list is empty, or just to be safe, check legacy key
		if (singleLegacy != null && !singleLegacy.isBlank()) {
			// Avoid duplicates
			if (!keys.contains(singleLegacy)) {
				keys.add(singleLegacy);
			}
		}

		if (keys.isEmpty()) {
			log.warn("No API keys found for provider: {}", provider);
		} else {
			log.info("Loaded {} keys for provider: {}", keys.size(), provider);
		}

		keyStore.put(provider, Collections.unmodifiableList(keys));
		counters.put(provider, new AtomicInteger(0));
	}

	@Override
	public String getKey(KeyProvider provider) {
		List<String> keys = keyStore.getOrDefault(provider, Collections.emptyList());
		if (keys.isEmpty()) {
			throw new RuntimeException("No API keys configured for " + provider);
		}

		AtomicInteger counter = counters.get(provider);
		int size = keys.size();

		// Try to find a non-cooldown key, trying each key at least once
		for (int i = 0; i < size; i++) {
			// Get next index atomically-ish (just round robin)
			// We use getAndIncrement but mod it locally.
			// Race conditions on the exact index don't matter as much as distribution.
			int index = Math.abs(counter.getAndIncrement() % size);
			String candidateKey = keys.get(index);

			if (!isCooldown(candidateKey)) {
				return candidateKey;
			}
		}

		// If all keys are in cooldown, DO NOT force use.
		// Throw a specific exception that the listener can catch to requeue the
		// message.
		log.warn("All keys for {} are in cooldown/rate-limited state.", provider);
		throw new KeysExhaustedException("All API keys for " + provider + " are currently in cooldown.",
				nextAvailableAt(provider));
	}

	@Override
	public void reportError(KeyProvider provider, String key, int statusCode) {
		reportError(provider, key, statusCode, provider == KeyProvider.GEMINI ? geminiCooldown : Duration.ofMinutes(1));
	}

	@Override
	public void reportError(KeyProvider provider, String key, int statusCode, Duration cooldown) {
		if (isRateLimitError(statusCode)) {
			long cooldownMs = Math.max(1, cooldown.toMillis());
			log.warn("Rate limit error ({}) reported for key: ...{}. Putting in cooldown for {}ms.", statusCode,
					maskKey(key), cooldownMs);
			cooldownMap.put(key, System.currentTimeMillis() + cooldownMs);
		}
	}

	@Override
	public void reportSuccess(KeyProvider provider, String key) {
		cooldownMap.remove(key);
	}

	@Override
	public Instant nextAvailableAt(KeyProvider provider) {
		long now = System.currentTimeMillis();
		return keyStore.getOrDefault(provider, Collections.emptyList()).stream().map(cooldownMap::get)
				.filter(java.util.Objects::nonNull).filter(expiry -> expiry > now).min(Long::compareTo)
				.map(Instant::ofEpochMilli).orElse(Instant.ofEpochMilli(now));
	}

	@Override
	public int configuredKeyCount(KeyProvider provider) {
		return keyStore.getOrDefault(provider, Collections.emptyList()).size();
	}

	private boolean isCooldown(String key) {
		Long expiry = cooldownMap.get(key);
		if (expiry == null) {
			return false;
		}
		if (System.currentTimeMillis() > expiry) {
			cooldownMap.remove(key);
			return false;
		}
		return true;
	}

	private boolean isRateLimitError(int statusCode) {
		return statusCode == 429;
	}

	private String maskKey(String key) {
		if (key == null || key.length() < 8)
			return "********";
		return key.substring(key.length() - 4);
	}
}
