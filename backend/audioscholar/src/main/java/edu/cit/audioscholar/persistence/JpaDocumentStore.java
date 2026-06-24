package edu.cit.audioscholar.persistence;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class JpaDocumentStore {

	private final GenericDocumentRepository repository;
	private final DocumentJsonMapper jsonMapper;

	public JpaDocumentStore(GenericDocumentRepository repository, DocumentJsonMapper jsonMapper) {
		this.repository = repository;
		this.jsonMapper = jsonMapper;
	}

	@Transactional
	public void save(String collection, String documentId, Object data) {
		Map<String, Object> map = toMap(data);
		putIdIfMissing(collection, documentId, map);
		GenericDocumentId id = new GenericDocumentId(collection, documentId);
		GenericDocument document = repository.findById(id)
				.orElseGet(() -> new GenericDocument(collection, documentId, "{}"));
		document.setDataJson(jsonMapper.toJson(map));
		repository.save(document);
	}

	@Transactional
	public void merge(String collection, String documentId, Object data) {
		Map<String, Object> existingData = get(collection, documentId);
		if (existingData == null) {
			existingData = new LinkedHashMap<>();
		}
		Map<String, Object> updates = toMap(data);
		for (Map.Entry<String, Object> entry : updates.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			if (value == null || isFirestoreDeleteSentinel(value)) {
				existingData.remove(key);
			} else {
				existingData.put(key, value);
			}
		}
		putIdIfMissing(collection, documentId, existingData);
		save(collection, documentId, existingData);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> get(String collection, String documentId) {
		Optional<GenericDocument> document = repository.findById(new GenericDocumentId(collection, documentId));
		return document.map(value -> withId(collection, value.getId().getDocumentId(), jsonMapper.fromJson(value.getDataJson())))
				.orElse(null);
	}

	@Transactional
	public void delete(String collection, String documentId) {
		repository.deleteById(new GenericDocumentId(collection, documentId));
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> query(String collection, String field, Object expectedValue) {
		return all(collection).stream().filter(map -> valuesEqual(map.get(field), expectedValue)).collect(Collectors.toList());
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> all(String collection) {
		return repository.findByIdCollectionName(collection).stream()
				.map(document -> withId(collection, document.getId().getDocumentId(), jsonMapper.fromJson(document.getDataJson())))
				.collect(Collectors.toCollection(ArrayList::new));
	}

	@Transactional(readOnly = true)
	public long count(String collection) {
		return repository.countByIdCollectionName(collection);
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> topRecordings(int limit) {
		return all("recordings").stream()
				.sorted(Comparator.comparing((Map<String, Object> item) -> numberValue(item.get("favoriteCount"))).reversed())
				.limit(limit).collect(Collectors.toList());
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> toMap(Object data) {
		if (data == null) {
			return new LinkedHashMap<>();
		}
		if (data instanceof Map<?, ?> source) {
			Map<String, Object> result = new LinkedHashMap<>();
			source.forEach((key, value) -> result.put(String.valueOf(key), value));
			return result;
		}
		try {
			Method toMap = data.getClass().getMethod("toMap");
			Object value = toMap.invoke(data);
			if (value instanceof Map<?, ?> map) {
				return toMap(map);
			}
		} catch (NoSuchMethodException ignored) {
			// Fall through to Jackson conversion below.
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to convert data object to a map", e);
		}
		return jsonMapper.fromJson(jsonMapper.toJson((Map<String, Object>) data));
	}

	private Map<String, Object> withId(String collection, String documentId, Map<String, Object> data) {
		putIdIfMissing(collection, documentId, data);
		return data;
	}

	private void putIdIfMissing(String collection, String documentId, Map<String, Object> data) {
		if (!data.containsKey("id")) {
			data.put("id", documentId);
		}
		if ("users".equals(collection) && !data.containsKey("userId")) {
			data.put("userId", documentId);
		}
		if ("recordings".equals(collection) && !data.containsKey("recordingId")) {
			data.put("recordingId", documentId);
		}
		if ("summaries".equals(collection) && !data.containsKey("summaryId")) {
			data.put("summaryId", documentId);
		}
		if ("user_notes".equals(collection) && !data.containsKey("noteId")) {
			data.put("noteId", documentId);
		}
		if ("learning_recommendations".equals(collection) && !data.containsKey("recommendationId")) {
			data.put("recommendationId", documentId);
		}
	}

	private boolean valuesEqual(Object actual, Object expected) {
		if (actual == expected) {
			return true;
		}
		if (actual == null || expected == null) {
			return false;
		}
		return String.valueOf(actual).equals(String.valueOf(expected));
	}

	private long numberValue(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (StringUtils.hasText(String.valueOf(value))) {
			try {
				return Long.parseLong(String.valueOf(value));
			} catch (NumberFormatException ignored) {
				return 0L;
			}
		}
		return 0L;
	}

	private boolean isFirestoreDeleteSentinel(Object value) {
		return value != null && value.getClass().getName().contains("FieldValue");
	}
}
